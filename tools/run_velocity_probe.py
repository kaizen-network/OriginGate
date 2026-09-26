"""Loopback-only probe: runs the built plugin on a real local Velocity with a stub lookup provider.

Needs a prepared .run/velocity (see docs/04-development.md), Java on PATH, and Python 3.11+.
Build first: gradlew build :platform-velocity:probeJar
With --consentgate and --packetevents, ConsentGate is installed too and the compatibility checks run.
No real account, real API, or real database is used.
"""

import argparse
import hashlib
import http.server
import json
import os
import pathlib
import select
import shutil
import socket
import struct
import subprocess
import threading
import time
import tomllib
import uuid

PROJECT = pathlib.Path(__file__).resolve().parents[1]
PROXY = PROJECT / ".run" / "velocity"
PLUGINS = PROXY / "plugins"
DATA = PLUGINS / "origingate"
PERMISSIONS = PLUGINS / "origingate-probe-permissions" / "permissions.txt"
VERSION = next(line.split("=", 1)[1].strip() for line in (PROJECT / "gradle.properties").read_text().splitlines()
               if line.startswith("version="))
PROTOCOL = 772  # Java 1.21.8
PROXY_PORT = 25590
BACKEND_PORT = 25591


# ---------------------------------------------------------------- Minecraft login client

def varint(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def string(value):
    data = value.encode("utf-8")
    return varint(len(data)) + data


def exact(sock, size):
    data = bytearray()
    while len(data) < size:
        part = sock.recv(size - len(data))
        if not part:
            raise EOFError("connection closed")
        data.extend(part)
    return bytes(data)


def read_varint(sock):
    result = 0
    for shift in range(0, 35, 7):
        byte = exact(sock, 1)[0]
        result |= (byte & 127) << shift
        if not byte & 128:
            return result
    raise ValueError("VarInt too long")


def flatten(component):
    if isinstance(component, str):
        return component
    if isinstance(component, list):
        return "".join(flatten(part) for part in component)
    text = component.get("text", "")
    return text + "".join(flatten(part) for part in component.get("extra", []))


class Client:
    """Offline-mode login. Compression must be off on the test proxy."""

    def __init__(self, name):
        self.name = name
        offline = uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + name).encode()).digest(), version=3)
        self.sock = socket.create_connection(("127.0.0.1", PROXY_PORT), timeout=20)
        self.send(0, varint(PROTOCOL) + string("localhost") + struct.pack(">H", PROXY_PORT) + varint(2))
        self.send(0, string(name) + offline.bytes)

    def send(self, packet, payload=b""):
        body = varint(packet) + payload
        self.sock.sendall(varint(len(body)) + body)

    def receive(self):
        size = read_varint(self.sock)
        body = exact(self.sock, size)
        packet = body[0]
        assert packet < 128
        return packet, body[1:]

    def login(self):
        """Returns ("joined", None) or ("kicked", reason text)."""
        while True:
            packet, body = self.receive()
            if packet == 0:
                length, index = decode_varint(body)
                return "kicked", flatten(json.loads(body[index:index + length].decode("utf-8")))
            if packet == 2:
                self.send(3)  # Login acknowledged: enter configuration.
                return "joined", None
            if packet == 4:  # Login plugin request: answer "not understood".
                message_id, _ = decode_varint(body)
                self.send(2, varint(message_id) + b"\x00")
                continue
            raise AssertionError(f"unexpected login packet {packet}")

    def wait_for_dialog(self, seconds):
        """Configuration state: echo keepalives until a dialog (packet 18) arrives."""
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            packet, body = self.receive()
            if packet == 4:
                self.send(4, body)
            elif packet == 18:
                return True
            elif packet == 2:
                raise AssertionError("disconnected before the dialog: " + body[:200].decode("utf-8", "replace"))
        return False

    def close(self):
        self.sock.close()


def decode_varint(data):
    """Returns (value, bytes used) for a VarInt at the start of data."""
    value = 0
    for index, byte in enumerate(data[:5]):
        value |= (byte & 127) << (7 * index)
        if not byte & 128:
            return value, index + 1
    raise ValueError("VarInt too long")


# ---------------------------------------------------------------- stub lookup provider

class Stub:
    def __init__(self):
        self.profile = {"kind": "clean", "delay": 0}
        self.requests = []
        stub = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                stub.requests.append(self.path)
                profile = dict(stub.profile)
                if profile["delay"]:
                    time.sleep(profile["delay"])
                if profile["kind"] == "error":
                    self.answer(500, {"status": "error", "message": "stub failure"})
                    return
                ip = self.path.split("?")[0].rsplit("/", 1)[-1]
                self.answer(200, stub.body(ip, profile["kind"]))

            def answer(self, status, body):
                data = json.dumps(body).encode()
                try:
                    self.send_response(status)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                except OSError:
                    pass  # The plugin gave up waiting.

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @staticmethod
    def body(ip, kind):
        country = {"foreign": ("Germany", "DE", "Frankfurt am Main")}.get(kind, ("Canada", "CA", "Toronto"))
        return {"status": "ok", ip: {
            "network": {"asn": "AS64500", "range": "127.0.0.0/8", "hostname": None, "provider": "Probe Hosting",
                        "organisation": "Probe Org", "type": "Hosting" if kind in ("vpn", "proxy") else "Residential"},
            "location": {"country_name": country[0], "country_code": country[1], "region_name": "Probe Region",
                         "city_name": country[2]},
            "detections": {"proxy": kind == "proxy", "vpn": kind == "vpn", "tor": False, "hosting": kind in ("vpn", "proxy")},
            "operator": {"name": "Probe VPN Operator"} if kind == "vpn" else None,
        }, "query_time": 1}

    def set(self, kind, delay=0):
        self.profile = {"kind": kind, "delay": delay}

    def close(self):
        self.server.shutdown()


# ---------------------------------------------------------------- probe

CONFIG = """\
config-version: 1
dry-run: {dry_run}
console-log: {console_log}
lookup:
  skip-private-addresses: false
  on-lookup-failure: {failure}
  wait-millis: {wait}
  proxycheck:
    base-url: "http://127.0.0.1:{port}/v3/"
    api-keys: [probe-key-1, probe-key-2]
    request-timeout-millis: {request_timeout}
storage:
  type: sqlite
  max-age-days: 30
  memory-cache-size: 1000
  sqlite:
    file: probe/{fixture}.db
bypass:
  permissions: []
  players: []
  addresses: []
rules:
  deny-addresses:
    enabled: {deny}
    list: ["127.0.0.0/8"]
    bypass-permissions: []
  vpn:
    enabled: true
    bypass-permissions: ["origingate.bypass.vpn"]
  proxy:
    enabled: true
    allowed-countries: []
    bypass-permissions: ["origingate.bypass.proxy"]
  country:
    enabled: {country}
    mode: allowlist
    countries: [CA]
    bypass-permissions: ["origingate.bypass.country"]
alerts:
  permissions: ["origingate.alerts"]
log-file: true
"""

CONSENT_CONFIG = """\
config-version: 1
enabled: true
scope: probe
gate:
  timeout-seconds: 300
  max-pending: 16
language:
  default: en-US
  use-client-locale: true
bedrock:
  native-forms: false
appearance:
  title-color: gold
  accent-color: gold
  text-color: white
  muted-color: gray
  error-color: red
  button-color: white
documents:
  directory: probe-documents
storage:
  type: sqlite
  sqlite:
    file: probe-consent.db
"""

CONSENT_DOCUMENT = """\
id: test-agreement
version: "probe-1"
required: true
order: 10
translations:
  en-US:
    title: "Probe Agreement"
    summary: "Local automated test content."
    checkbox: "I accept the probe agreement"
    read-button: "Read the probe agreement"
    pages:
      - title: "Only page"
        body: "Local automated test content."
"""


class Probe:
    def __init__(self, stub, fixture):
        self.stub = stub
        self.fixture = fixture
        self.process = None
        self.log_file = PROXY / "logs" / "latest.log"
        self.passed = 0

    def write_config(self, dry_run=False, failure="allow", wait=3000, request_timeout=2500, deny=False, country=False,
                     console_log="all", raw=None):
        text = raw if raw is not None else CONFIG.format(
            dry_run=str(dry_run).lower(), failure=failure, wait=wait, request_timeout=request_timeout,
            port=self.stub.port, fixture=self.fixture, deny=str(deny).lower(), country=str(country).lower(),
            console_log=console_log)
        (DATA / "config.yml").write_text(text, encoding="utf-8")

    def log_text(self):
        return self.log_file.read_text(encoding="utf-8", errors="replace")

    def command(self, text, expected, seconds=10):
        offset = len(self.log_text())
        self.process.stdin.write(text + "\n")
        self.process.stdin.flush()
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            new = self.log_text()[offset:]
            if expected in new:
                return new
            time.sleep(0.1)
        raise AssertionError(f"'{text}' did not print '{expected}'. Output:\n{self.log_text()[offset:]}")

    def wait_log(self, expected, seconds=10, offset=0):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if expected in self.log_text()[offset:]:
                return
            time.sleep(0.1)
        raise AssertionError("log never showed: " + expected)

    def fresh(self, kind, delay=0):
        self.stub.set(kind, delay)
        self.command("origingate cache clear all", "Cleared")

    def ok(self, message):
        self.passed += 1
        print("PASS: " + message, flush=True)

    def start(self):
        self.process = subprocess.Popen(
            ["java", "-Xms128m", "-Xmx384m", "-Dterminal.jline=false", "-jar", "velocity.jar"],
            cwd=PROXY, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, text=True,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError("proxy exited; see .run/velocity/logs/latest.log")
            with socket.socket() as check:
                if check.connect_ex(("127.0.0.1", PROXY_PORT)) == 0:
                    return
            time.sleep(0.2)
        raise TimeoutError("proxy did not start within 60 seconds")

    def stop(self):
        if self.process is None:
            return
        try:
            if self.process.poll() is None:
                self.process.stdin.write("shutdown\n")
                self.process.stdin.flush()
                self.process.wait(timeout=20)
        except (OSError, subprocess.TimeoutExpired):
            self.process.kill()
            self.process.wait(timeout=10)


def expect_join(backend, name, consent=False):
    client = Client(name)
    try:
        result, reason = client.login()
        assert result == "joined", f"{name} was kicked: {reason}"
        if consent:
            assert client.wait_for_dialog(15), f"{name} never saw the ConsentGate dialog"
            assert not select.select([backend], [], [], 0.5)[0], "backend contacted before consent"
            return
        backend.settimeout(10)
        connection, _ = backend.accept()
        with connection:
            connection.settimeout(5)
            assert exact(connection, read_varint(connection))[0] == 0, "backend did not receive a handshake"
    finally:
        client.close()


def expect_kick(backend, name, *texts):
    client = Client(name)
    try:
        result, reason = client.login()
        assert result == "kicked", f"{name} was let in"
        for text in texts:
            assert text in reason, f"kick message for {name} lacks {text!r}: {reason!r}"
        assert not select.select([backend], [], [], 0.3)[0], "backend contacted by a kicked player"
        return reason
    finally:
        client.close()


def run(probe, backend, consentgate):
    stub = probe.stub

    probe.fresh("clean")
    expect_join(backend, "ProbeAllow", consent=consentgate)
    probe.wait_log("ALLOW rule=- player=ProbeAllow")
    probe.ok("clean address joins" + (" and still gets the ConsentGate dialog" if consentgate else " and reaches the backend"))

    probe.fresh("vpn")
    requests = len(stub.requests)
    reason = expect_kick(backend, "ProbeVpn", "VPN connections are not allowed", "Probe Hosting / Probe VPN Operator",
                         "Canada, Toronto", "Username: ProbeVpn")
    assert "127.0.0.1" not in reason, "default kick screen must not show the IP"
    assert len(stub.requests) == requests + 1
    probe.ok("VPN is kicked at login with its message" + (", before ConsentGate" if consentgate else ""))

    if consentgate:
        return

    probe.fresh("clean", delay=1.5)
    client = Client("ProbeHold")
    try:
        ready = select.select([backend, client.sock], [], [], 1.2)[0]
        assert not ready, "login finished or backend contacted before the lookup answered"
        result, reason = client.login()
        assert result == "joined", reason
        backend.settimeout(10)
        connection, _ = backend.accept()
        connection.close()
    finally:
        client.close()
    probe.ok("login is held while the lookup runs; no backend contact before the decision")

    offset = len(probe.log_text())
    probe.fresh("vpn")
    PERMISSIONS.write_text("ProbeBypass origingate.bypass.vpn\n", encoding="utf-8")
    expect_join(backend, "ProbeBypass")
    probe.wait_log("BYPASS rule=vpn player=ProbeBypass", offset=offset)
    probe.ok("VPN bypass permission lets the player in")

    probe.fresh("proxy")
    expect_kick(backend, "ProbeProxy", "Proxy connections are not allowed")
    probe.ok("proxy is kicked")

    probe.write_config(country=True)
    probe.command("origingate reload", "OriginGate reloaded.")
    probe.fresh("foreign")
    expect_kick(backend, "ProbeForeign", "Connections from your country (Germany) are not allowed")
    probe.fresh("clean")
    expect_join(backend, "ProbeHome")
    probe.ok("country allowlist kicks other countries and lets listed ones in")

    probe.write_config(deny=True)
    probe.command("origingate reload", "OriginGate reloaded.")
    probe.fresh("clean")
    requests = len(stub.requests)
    expect_kick(backend, "ProbeDenied", "Your connection is not allowed on this server")
    assert len(stub.requests) == requests, "deny-addresses must not call the API"
    probe.ok("deny-addresses kicks without an API call")

    probe.write_config(dry_run=True)
    probe.command("origingate reload", "Dry run is on")
    offset = len(probe.log_text())
    probe.fresh("vpn")
    expect_join(backend, "ProbeDryRun")
    probe.wait_log("WOULD-DENY rule=vpn player=ProbeDryRun", offset=offset)
    probe.ok("dry-run lets a VPN in and logs WOULD-DENY")

    probe.write_config()
    probe.command("origingate reload", "OriginGate reloaded.")
    offset = len(probe.log_text())
    probe.fresh("error")
    expect_join(backend, "ProbeFailAllow")
    probe.wait_log("ALLOW rule=lookup-failure player=ProbeFailAllow", offset=offset)
    probe.ok("lookup failure with on-lookup-failure: allow lets the player in")

    probe.write_config(failure="deny", wait=1500, request_timeout=1000)
    probe.command("origingate reload", "OriginGate reloaded.")
    probe.fresh("error")
    expect_kick(backend, "ProbeFailDeny", "could not be checked")
    probe.fresh("clean", delay=4)
    started = time.monotonic()
    expect_kick(backend, "ProbeSlow", "could not be checked")
    assert time.monotonic() - started < 4, "slow lookup was not cut off"
    probe.ok("lookup failure and timeout with on-lookup-failure: deny kick the player")

    probe.write_config(raw=CONFIG.replace("wait-millis: {wait}", "wait-millis: 5").format(
        dry_run="false", failure="deny", request_timeout=1000, port=stub.port, fixture=probe.fixture, deny="false",
        country="false", console_log="all"))
    probe.command("origingate reload", "Reload failed, the previous settings stay active")
    probe.fresh("error")
    expect_kick(backend, "ProbeKeepsOld", "could not be checked")
    probe.ok("invalid reload is refused and the previous settings stay active")

    messages = DATA / "messages.yml"
    original = messages.read_text(encoding="utf-8")
    try:
        messages.write_text(original.replace("VPN connections are not allowed.", "PROBE-CHANGED VPN text."), encoding="utf-8")
        probe.write_config()
        probe.command("origingate reload", "OriginGate reloaded.")
        probe.fresh("vpn")
        expect_kick(backend, "ProbeNewText", "PROBE-CHANGED VPN text.")
    finally:
        messages.write_text(original, encoding="utf-8")
    probe.ok("valid reload applies changed messages")

    keys = [path.split("key=")[-1] for path in stub.requests if "key=" in path]
    assert "probe-key-1" in keys and "probe-key-2" in keys, keys
    assert all("?vpn" not in path and "?asn" not in path for path in stub.requests)
    probe.ok("API keys are sent as a proper key parameter and used in turn")

    probe.command("origingate check 127.0.0.1", "Provider: Probe Hosting")
    logs = list((DATA / "logs").glob("*.log"))
    assert logs and "DENY rule=vpn player=ProbeVpn" in "".join(p.read_text(encoding="utf-8") for p in logs)
    probe.ok("check command works and kicks are written to the daily log file")

    probe.write_config(console_log="kicks")
    probe.command("origingate reload", "OriginGate reloaded.")
    offset = len(probe.log_text())
    probe.fresh("clean")
    expect_join(backend, "ProbeQuiet")
    probe.fresh("vpn")
    expect_kick(backend, "ProbeLoud", "VPN connections are not allowed")
    probe.wait_log("DENY rule=vpn player=ProbeLoud", offset=offset)
    assert "player=ProbeQuiet" not in probe.log_text()[offset:], "console-log: kicks printed a normal join"
    probe.ok("console-log: kicks prints kicks only")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consentgate", type=pathlib.Path, help="ConsentGate Velocity JAR for the compatibility run")
    parser.add_argument("--packetevents", type=pathlib.Path, help="PacketEvents Velocity JAR, required by ConsentGate")
    args = parser.parse_args()
    if bool(args.consentgate) != bool(args.packetevents):
        parser.error("--consentgate and --packetevents go together")

    with (PROXY / "velocity.toml").open("rb") as source:
        settings = tomllib.load(source)
    assert settings["bind"] == f"127.0.0.1:{PROXY_PORT}", "only the loopback test proxy is allowed"
    assert settings["online-mode"] is False
    assert settings["advanced"]["compression-threshold"] == -1
    assert settings["servers"]["backend"] == f"127.0.0.1:{BACKEND_PORT}"
    with socket.socket() as check:
        assert check.connect_ex(("127.0.0.1", PROXY_PORT)) != 0, "test port already in use"

    artifact = PROJECT / "platform-velocity" / "build" / "libs" / f"OriginGate-Velocity-{VERSION}.jar"
    permissions = PROJECT / "platform-velocity" / "build" / "libs" / f"OriginGate-ProbePermissions-{VERSION}.jar"
    for path in (artifact, permissions):
        assert path.exists(), f"missing {path}; run gradlew build :platform-velocity:probeJar"
    for jar in PLUGINS.glob("*.jar"):
        jar.unlink()
    shutil.copyfile(artifact, PLUGINS / "OriginGate.jar")
    shutil.copyfile(permissions, PLUGINS / "OriginGate-ProbePermissions.jar")
    consentgate = args.consentgate is not None
    if consentgate:
        shutil.copyfile(args.consentgate, PLUGINS / "ConsentGate.jar")
        shutil.copyfile(args.packetevents, PLUGINS / "packetevents.jar")
        consent = PLUGINS / "consentgate"
        shutil.rmtree(consent, ignore_errors=True)
        (consent / "probe-documents").mkdir(parents=True)
        (consent / "config.yml").write_text(CONSENT_CONFIG, encoding="utf-8")
        (consent / "probe-documents" / "agreement.yml").write_text(CONSENT_DOCUMENT, encoding="utf-8")
        print("ConsentGate " + hashlib.sha256(args.consentgate.read_bytes()).hexdigest(), flush=True)
    PERMISSIONS.parent.mkdir(parents=True, exist_ok=True)
    PERMISSIONS.write_text("", encoding="utf-8")
    DATA.mkdir(parents=True, exist_ok=True)
    shutil.rmtree(DATA / "logs", ignore_errors=True)

    stub = Stub()
    probe = Probe(stub, uuid.uuid4().hex)
    probe.write_config()
    try:
        with socket.socket() as backend:
            backend.bind(("127.0.0.1", BACKEND_PORT))
            backend.listen()
            probe.start()
            probe.wait_log("OriginGate is running with sqlite storage", seconds=30)
            run(probe, backend, consentgate)
        print(f"All {probe.passed} probe checks passed.", flush=True)
    finally:
        probe.stop()
        stub.close()
        if consentgate:
            (PLUGINS / "ConsentGate.jar").unlink(missing_ok=True)
            (PLUGINS / "packetevents.jar").unlink(missing_ok=True)
        print("Velocity stopped. Log: .run/velocity/logs/latest.log", flush=True)


if __name__ == "__main__":
    main()
