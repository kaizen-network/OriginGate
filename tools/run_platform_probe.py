"""Isolated loopback integration checks. No external lookups or real players."""
from pathlib import Path
import argparse, importlib.util, subprocess, os, threading, time, shutil, socket, struct, json, zipfile
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('velocity_probe',ROOT/'tools/run_velocity_probe.py')
v=importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('platform',choices=['bukkit','bungee','paper'])
parser.add_argument('--server',type=Path,required=True,help='Disposable server/proxy JAR to copy into the probe folder')
parser.add_argument('--java',default='java')
parser.add_argument('--bukkit-api',type=Path,help='Bukkit 1.7.2 API JAR for compiling the permission fixture')
parser.add_argument('--modules',type=Path,help='Node modules directory containing minecraft-protocol, required for Paper')
parser.add_argument('--client-version',default='1.21.8',help='Protocol library version; 1.21.8 also connects to Paper 1.21.7')
parser.add_argument('--cache',type=Path,help='Optional Paper folder whose libraries/cache may be copied')
parser.add_argument('--port',type=int)
parser.add_argument('--run-name',help='Unique probe folder name for testing multiple versions')
args=parser.parse_args()
kind=args.platform
if kind!='bungee' and not args.bukkit_api:parser.error('--bukkit-api is required')
if kind=='paper' and not args.modules:parser.error('--modules is required for Paper')
args.server=args.server.resolve()
if args.bukkit_api:args.bukkit_api=args.bukkit_api.resolve()
if args.modules:args.modules=args.modules.resolve()
RUN=ROOT/'.run'/('integration-'+(args.run_name or kind))
if RUN.resolve().parent != (ROOT/'.run').resolve():parser.error('run-name must be a plain folder name')
RUN.mkdir(parents=True,exist_ok=True)
DATA=RUN/'plugins/OriginGate'; DATA.mkdir(parents=True,exist_ok=True)
JAVA8=JAVA21=args.java
PORT=args.port or (25587 if kind=='bukkit' else (25586 if kind=='paper' else 25588))
backend_port=PORT+1
stub=v.Stub()
lines=[]
process=None
checks=[]
def log(): return ''.join(lines)
def waitfor(text,offset=0,seconds=40):
    deadline=time.monotonic()+seconds
    while time.monotonic()<deadline:
        if text in log()[offset:]: return
        if process.poll() is not None: break
        time.sleep(.05)
    raise AssertionError('Missing '+text+'\n'+log()[offset:][-6000:])
def command(text,expected):
    offset=len(log()); process.stdin.write(text+'\n');process.stdin.flush();waitfor(expected,offset,15)
def config(**kw):
    options=dict(dry_run='false',failure='deny',wait=1500,request_timeout=1000,port=stub.port,fixture='test',deny='false',country='false',console_log='all')
    options.update(kw)
    text=v.CONFIG.format(**options)
    text=text.replace('permissions: []','permissions: ["origingate.bypass"]',1)
    (DATA/'config.yml').write_text(text)
def case(label,profile='clean',expected='joined',**kw):
    config(**kw);command('origingate reload','OriginGate reloaded.')
    stub.set(profile,0);command('origingate cache clear all','Cleared')
    name='BypassProbe' if label.startswith('permission') else ('RouteProbe' if label.startswith('routing') else 'Probe'+str(len(checks)))
    outcome,reason=login(name)
    assert outcome==expected,(label,outcome,reason,log()[-2500:])
    checks.append(label);print('PASS',kind,label,flush=True)
def login(name, retry=True):
    if args.modules:
        run=subprocess.run(['node',str(ROOT/'tools/platform_client.cjs'),str(PORT),name,str(args.modules),args.client_version],capture_output=True,text=True,timeout=25)
        assert run.returncode==0,(run.stdout,run.stderr)
        return json.loads(run.stdout.strip().splitlines()[-1])
    protocol=4 if kind=='bukkit' else 47
    s=socket.create_connection(('127.0.0.1',PORT),timeout=12)
    def send(packet,payload):
        body=v.varint(packet)+payload;s.sendall(v.varint(len(body))+body)
    send(0,v.varint(protocol)+v.string('localhost')+struct.pack('>H',PORT)+v.varint(2))
    send(0,v.string(name))
    login_state=True
    try:
        while True:
            body=v.exact(s,v.read_varint(s));packet,index=v.decode_varint(body);payload=body[index:]
            if login_state and packet==2:login_state=False;continue
            if (login_state and packet==0) or (not login_state and packet==0x40):
                size,start=v.decode_varint(payload);reason=v.flatten(json.loads(payload[start:start+size].decode()))
                if retry and 'Please reconnect in a moment.' in reason:
                    s.close();time.sleep(1.7);return login(name,False)
                return ('joined' if 'probe reached backend' in reason else 'kicked'),reason
            if not login_state and packet==1:return 'joined','join game packet'
            if login_state:raise AssertionError(('unexpected login packet',packet,payload[:100]))
    finally:s.close()
def install_permission_probe():
    scratch=RUN/'probe-src';scratch.mkdir(exist_ok=True)
    source='''import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerLoginEvent;
public class PermissionProbe extends JavaPlugin implements Listener {
 public void onEnable() { getServer().getPluginManager().registerEvents(this,this); }
 @EventHandler(priority=EventPriority.LOWEST) public void login(PlayerLoginEvent e) {
  if(e.getPlayer().getName().equals("BypassProbe")) e.getPlayer().addAttachment(this,"origingate.bypass",true);
 }
}'''
    (scratch/'PermissionProbe.java').write_text(source)
    subprocess.run(['javac','--release','8','-cp',str(args.bukkit_api),str(scratch/'PermissionProbe.java')],check=True,capture_output=True)
    with zipfile.ZipFile(RUN/'plugins/PermissionProbe.jar','w') as z:
        z.write(scratch/'PermissionProbe.class','PermissionProbe.class')
        z.writestr('plugin.yml','name: PermissionProbe\nmain: PermissionProbe\nversion: 1\nloadbefore: [OriginGate]\n')
backend=None
def install_routing_probe():
    scratch=RUN/'probe-src';scratch.mkdir(exist_ok=True)
    source='''import net.md_5.bungee.api.plugin.*;
import net.md_5.bungee.api.event.*;
import net.md_5.bungee.event.*;
public class RoutingProbe extends Plugin implements Listener {
 public void onEnable() { getProxy().getPluginManager().registerListener(this,this); }
 @EventHandler(priority=EventPriority.LOWEST) public void login(PostLoginEvent e) {
  if(e.getPlayer().getName().equals("RouteProbe")) e.getPlayer().connect(getProxy().getServerInfo("probe"));
 }
}'''
    (scratch/'RoutingProbe.java').write_text(source)
    cp=str(args.server)
    subprocess.run(['javac','--release','11','-cp',cp,str(scratch/'RoutingProbe.java')],check=True,capture_output=True)
    with zipfile.ZipFile(RUN/'plugins/RoutingProbe.jar','w') as z:
        z.write(scratch/'RoutingProbe.class','RoutingProbe.class')
        z.writestr('bungee.yml','name: RoutingProbe\nmain: RoutingProbe\nversion: 1\n')
try:
    if kind in ('bukkit','paper'):
        shutil.copy2(ROOT/'platform-bukkit/build/libs/OriginGate-Bukkit-0.1.0.jar',RUN/'plugins/OriginGate.jar')
        shutil.copy2(args.server,RUN/'server.jar')
        (RUN/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={PORT}\nonline-mode=false\nlevel-type=FLAT\nview-distance=3\nallow-nether=false\nspawn-monsters=false\nspawn-animals=false\ngenerate-structures=false\nsnooper-enabled=false\nmax-players=10\nnetwork-compression-threshold=-1\n')
        (RUN/'bukkit.yml').write_text('settings:\n  connection-throttle: -1\n  update-folder: update\nauto-updater:\n  enabled: false\n  on-broken: []\n  on-update: []\n')
        install_permission_probe();java=JAVA8 if kind=='bukkit' else JAVA21;ready='Done (';stop='stop'
        if kind=='paper':
            # Copy only public server libraries to avoid downloading them again.
            if args.cache:
                for name in ('libraries','cache'):
                    if (args.cache/name).is_dir():shutil.copytree(args.cache/name,RUN/name,dirs_exist_ok=True)
            (RUN/'plugins/bStats').mkdir(exist_ok=True)
            (RUN/'plugins/bStats/config.yml').write_text('enabled: false\n')
    else:
        shutil.copy2(ROOT/'platform-bungeecord/build/libs/OriginGate-BungeeCord-0.1.0.jar',RUN/'plugins/OriginGate.jar')
        shutil.copy2(args.server,RUN/'server.jar')
        (RUN/'config.yml').write_text(f'''online_mode: false
ip_forward: false
network_compression_threshold: -1
connection_throttle: -1
listeners:
- host: 127.0.0.1:{PORT}
  query_enabled: false
  ping_passthrough: false
  priorities: [probe]
  force_default_server: true
  bind_local_address: true
  motd: Local probe
  max_players: 10
servers:
  probe:
    address: 127.0.0.1:{backend_port}
    restricted: false
    motd: Local probe
groups:
  BypassProbe: [bypass]
permissions:
  default: []
  bypass: [origingate.bypass]
''')
        # Prevent optional module downloads during this isolated check.
        (RUN/'modules.yml').write_text('modules: []\n')
        java=JAVA21;ready='Listening on';stop='end'
        install_routing_probe()
        backend=socket.socket();backend.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);backend.bind(('127.0.0.1',backend_port));backend.listen();backend.settimeout(.2)
        def backend_loop():
            while backend.fileno()!=-1:
                try:c,_=backend.accept()
                except (TimeoutError,OSError):continue
                with c:
                    try:
                        c.settimeout(3);v.exact(c,v.read_varint(c));v.exact(c,v.read_varint(c))
                        payload=b'\x00'+v.string(json.dumps({'text':'probe reached backend'}));c.sendall(v.varint(len(payload))+payload)
                    except OSError:pass
        threading.Thread(target=backend_loop,daemon=True).start()
    config()
    (RUN/'eula.txt').write_text('eula=true\n')
    process=subprocess.Popen([java,'-Xms128m','-Xmx512m','-Dterminal.jline=false','-jar','server.jar','nogui'],cwd=RUN,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,errors='replace',creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    def drain():
        with (RUN/'probe-console.log').open('w',encoding='utf8') as out:
            for line in process.stdout:lines.append(line);out.write(line);out.flush()
    threading.Thread(target=drain,daemon=True).start()
    waitfor(ready,seconds=90);waitfor('OriginGate is running.')
    case('clean connection')
    case('VPN denial before admission','vpn','kicked')
    if kind=='bungee':
        case('routing plugin cannot bypass pending lookup','vpn','kicked')
        case('routing plugin can connect after allowance','clean')
    case('permission bypass with VPN','vpn')
    case('permission bypass after lookup failure','error')
    case('dry run allows VPN','vpn',dry_run='true')
    case('lookup failure denies','error','kicked')
    case('lookup failure allows','error',failure='allow')
    case('deny-address rule','clean','kicked',deny='true')
    case('country denial','foreign','kicked',country='true')
    (DATA/'config.yml').write_text('config-version: 999\n')
    command('origingate reload','previous settings stay active')
    assert login('InvalidReload')[0]=='kicked';checks.append('invalid reload preserves active settings')
    print('PASS',kind,checks[-1],flush=True)
    config();command('origingate reload','OriginGate reloaded.')
    stub.set('vpn',2);command('origingate cache clear all','Cleared')
    outcome=[]
    previous=len(stub.requests)
    thread=threading.Thread(target=lambda:outcome.append(login('SlowProbe')));thread.start()
    deadline=time.monotonic()+15
    while len(stub.requests)==previous and time.monotonic()<deadline:time.sleep(.01)
    assert len(stub.requests)>previous,'slow lookup did not start'
    start=time.monotonic();command('origingate cache stats','Usage:')
    assert time.monotonic()-start<1,'main thread blocked during lookup'
    thread.join(15);assert outcome and outcome[0][0]=='kicked',outcome
    checks.append('slow lookup bounded and console responsive');print('PASS',kind,checks[-1],flush=True)
    (RUN/'results.json').write_text(json.dumps({'platform':kind,'passed':checks},indent=2))
finally:
    if process and process.poll() is None:
        try:process.stdin.write(stop+'\n');process.stdin.flush();process.wait(timeout=25)
        except (OSError,subprocess.TimeoutExpired):process.kill();process.wait(timeout=10)
    if backend:backend.close()
    stub.server.shutdown();stub.server.server_close()
