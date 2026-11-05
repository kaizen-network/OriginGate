"""Isolated loopback integration checks. No external lookups or real players."""
from pathlib import Path
import argparse, importlib.util, subprocess, os, threading, time, shutil, socket, struct, json, zipfile, re
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
    return log()[offset:]
def config(**kw):
    options=dict(dry_run='false',failure='deny',wait=1500,request_timeout=1000,port=stub.port,fixture='test',deny='false',country='false',console_log='all',extra_lookup='',extra_rules='')
    options.update(kw)
    text=v.CONFIG.format(**options)
    text=text.replace('permissions: []','permissions: ["origingate.bypass"]',1)
    (DATA/'config.yml').write_text(text)
def passed(label):
    checks.append(label);print('PASS',kind,label,flush=True)
def case(label,profile='clean',expected='joined',**kw):
    config(**kw);command('origingate reload','OriginGate reloaded.')
    stub.set(profile,0);command('origingate cache clear all','Cleared')
    name='BypassProbe' if label.startswith('permission') else ('RouteProbe' if label.startswith('routing') else 'Probe'+str(len(checks)))
    outcome,reason=login(name)
    assert outcome==expected,(label,outcome,reason,log()[-2500:])
    passed(label)
def login(name, retry=True, source=None):
    if args.modules:
        run=subprocess.run(['node',str(ROOT/'tools/platform_client.cjs'),str(PORT),name,str(args.modules),args.client_version]+([source] if source else []),capture_output=True,text=True,timeout=25)
        assert run.returncode==0,(run.stdout,run.stderr)
        return json.loads(run.stdout.strip().splitlines()[-1])
    protocol=4 if kind=='bukkit' else 47
    s=socket.create_connection(('127.0.0.1',PORT),timeout=12,source_address=(source,0) if source else None)
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
                    s.close();time.sleep(1.7);return login(name,False,source)
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
  if(e.getPlayer().getName().equals("StaffProbe")) e.getPlayer().addAttachment(this,"origingate.alerts",true);
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
    cp=[str(args.server)]
    with zipfile.ZipFile(args.server) as z:
        # Current BungeeCord builds nest their API jars under META-INF/libraries/; older builds and Waterfall do not.
        cp+=[z.extract(n,scratch) for n in z.namelist() if n.startswith('META-INF/libraries/bungeecord-') and n.endswith('.jar')]
    subprocess.run(['javac','--release','11','-cp',os.pathsep.join(cp),str(scratch/'RoutingProbe.java')],check=True,capture_output=True)
    with zipfile.ZipFile(RUN/'plugins/RoutingProbe.jar','w') as z:
        z.write(scratch/'RoutingProbe.class','RoutingProbe.class')
        z.writestr('bungee.yml','name: RoutingProbe\nmain: RoutingProbe\nversion: 1\n')
java=ready=stop=None
jvm_options=[]
def no_network_sink():
    """A local socket that closes every connection. BungeeCord posts statistics to mcstats.spigotmc.org with no
    setting to turn it off, so its HTTPS requests are sent here as a proxy and never leave the machine."""
    sink=socket.socket();sink.bind(('127.0.0.1',0));sink.listen()
    def drop():
        while True:
            try:c,_=sink.accept();c.close()
            except OSError:return
    threading.Thread(target=drop,daemon=True).start()
    return sink
def start_server():
    global process
    lines.clear()
    process=subprocess.Popen([java,'-Xms128m','-Xmx512m','-Dterminal.jline=false']+jvm_options+['-jar','server.jar','nogui'],cwd=RUN,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,errors='replace',creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    def drain():
        with (RUN/'probe-console.log').open('a',encoding='utf8') as out:
            for line in process.stdout:lines.append(line);out.write(line);out.flush()
    threading.Thread(target=drain,daemon=True).start()
    waitfor(ready,seconds=120);waitfor('OriginGate is running with')
def stop_server():
    if process and process.poll() is None:
        try:process.stdin.write(stop+'\n');process.stdin.flush();process.wait(timeout=40)
        except (OSError,subprocess.TimeoutExpired):process.kill();process.wait(timeout=10)
HOSTING_RULE='  hosting:\n    enabled: true\n    bypass-permissions: ["origingate.bypass.hosting"]\n'
def run_110():
    """Checks for the 1.1.0 changes. The test server runs in offline mode, as the probe needs."""
    assert 'This server or proxy runs in offline mode' in log(),'no offline-mode warning at startup'
    passed('offline-mode warning at startup')
    current_messages=(DATA/'messages.yml').read_text(encoding='utf-8')
    try:
        shutil.copyfile(ROOT/'core/src/test/resources/upgrade/config-1.0.0.yml',DATA/'config.yml')
        shutil.copyfile(ROOT/'core/src/test/resources/upgrade/messages-1.0.0.yml',DATA/'messages.yml')
        requests=len(stub.requests)
        command('origingate reload','OriginGate reloaded.')
        outcome=login('UpgradeProbe')
        assert outcome[0]=='joined',outcome
        assert len(stub.requests)==requests,'the 1.0.0 default config skips private addresses, so no lookup'
        status=command('origingate status','On lookup failure:')
        assert 'Budget: no limit' in status and 'over the budget: allow' in status,status
        passed('unchanged 1.0.0 config.yml and messages.yml load and let a private address in')
    finally:
        (DATA/'messages.yml').write_text(current_messages,encoding='utf-8')
    case('hosting rule kicks a stubbed Hosting network','hosting','kicked',extra_rules=HOSTING_RULE)
    assert 'DENY rule=hosting' in log(),log()[-2000:]
    case('hosting rule lets a residential network in','clean','joined',extra_rules=HOSTING_RULE)
    offset=len(log())
    config(extra_lookup='  max-lookups-per-minute: 1\n');command('origingate reload','OriginGate reloaded.')
    stub.set('clean',0);command('origingate cache clear all','Cleared')
    assert login('BudgetOne')[0]=='joined'
    command('origingate cache clear all','Cleared');requests=len(stub.requests)
    outcome=login('BudgetTwo')
    assert outcome[0]=='kicked' and 'could not be checked' in outcome[1],outcome
    assert len(stub.requests)==requests,'a login over the budget must not reach the provider'
    waitfor('Alert: The lookup budget of 1 lookup per minute is used up',offset)
    config(extra_lookup='  max-lookups-per-minute: 1\n  on-budget-exceeded: allow\n');command('origingate reload','OriginGate reloaded.')
    command('origingate cache clear all','Cleared');assert login('BudgetThree')[0]=='joined'
    command('origingate cache clear all','Cleared');assert login('BudgetFour')[0]=='joined'
    waitfor('ALLOW rule=lookup-failure player=BudgetFour',offset)
    status=command('origingate status','On lookup failure:')
    assert 'Budget: 1 of 1 lookups in the last minute' in status and 'On lookup failure: deny, over the budget: allow' in status,status
    passed('over the budget follows on-lookup-failure (deny), or on-budget-exceeded: allow when set, with one alert')
    config(failure='allow');command('origingate reload','OriginGate reloaded.')
    offset=len(log());stub.set('error',0)
    for i in range(4):assert login('FailProbe'+str(i))[0]=='joined'
    waitfor('Alert: proxycheck failed 3 lookups in a row',offset)
    stub.set('clean',0);assert login('FailBackProbe')[0]=='joined'
    waitfor('Alert: proxycheck answers lookups again.',offset)
    assert log()[offset:].count('Alert: proxycheck failed 3 lookups in a row')==1
    assert log()[offset:].count('Alert: proxycheck answers lookups again.')==1
    passed('3 failed lookups in a row send one alert, and the next answer one recovery alert')
    staff=None
    if args.modules:
        staff=subprocess.Popen(['node',str(ROOT/'tools/platform_staff_client.cjs'),str(PORT),'StaffProbe',str(args.modules),args.client_version,'150'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
        chat=[]
        threading.Thread(target=lambda:[chat.append(line) for line in staff.stdout],daemon=True).start()
        deadline=time.monotonic()+30
        while not any('"joined"' in line for line in chat) and time.monotonic()<deadline:time.sleep(.1)
        assert any('"joined"' in line for line in chat),chat
    offset=len(log());stub.set('refuse',0);command('origingate cache clear all','Cleared')
    assert login('PausedProbe')[0]=='joined'
    waitfor('Alert: proxycheck refused the request',offset)
    requests=len(stub.requests);assert login('StillPausedProbe')[0]=='joined'
    assert len(stub.requests)==requests,'a paused provider must not be asked'
    status=command('origingate status','On lookup failure:')
    assert 'proxycheck: paused for' in status,status
    time.sleep(61)
    stub.set('clean',0);command('origingate cache clear all','Cleared')
    assert login('ResumedProbe')[0]=='joined'
    waitfor('Alert: proxycheck answers lookups again.',offset)
    text=log()[offset:]
    assert text.count('Alert: proxycheck refused the request')==1,text
    assert text.count('Alert: proxycheck answers lookups again.')==1,text
    assert 'proxycheck: ok' in command('origingate status','On lookup failure:')
    if staff:
        time.sleep(1);staff.kill();staff.wait(10)
        received=[json.loads(line)['chat'] for line in chat if line.startswith('{"chat"')]
        problems=[t for t in received if 'refused the request' in t];recoveries=[t for t in received if 'answers lookups again' in t]
        assert len(problems)==1 and len(recoveries)==1,received
        print('Staff client chat:',problems+recoveries,flush=True)
        passed('a paused provider sends one alert to staff in game and one recovery alert after the pause')
    else:
        passed('a paused provider sends one alert and one recovery alert after the pause')
    config(failure='allow',wait=1000,request_timeout=3000);command('origingate reload','OriginGate reloaded.')
    stub.set('clean',2.5);command('origingate cache clear all','Cleared');requests=len(stub.requests)
    outcomes=[]
    def flood(i):outcomes.append(login('FloodProbe'+str(i),False,'127.0.0.'+str(10+i))[0])
    threads=[threading.Thread(target=flood,args=(i,)) for i in range(8)]
    for t in threads:t.start()
    for t in threads:t.join(30)
    time.sleep(4)
    sent=len(stub.requests)-requests
    status=command('origingate status','On lookup failure:')
    assert outcomes==['joined']*8,outcomes
    assert sent==4,(sent,status)
    assert '4 dropped after every login stopped waiting' in status,status
    print(status,flush=True)
    passed('8 addresses at once with 4 workers: 4 lookups ran, the 4 queued past their login wait were dropped unsent')
def forwarding_checks():
    """Paper only: no warning behind Velocity modern forwarding, a warning behind BungeeCord IP forwarding."""
    global_config=RUN/'config/paper-global.yml';spigot=RUN/'spigot.yml'
    original_global=global_config.read_text(encoding='utf-8');original_spigot=spigot.read_text(encoding='utf-8')
    try:
        modern=re.sub(r'(\n  velocity:\n    enabled: )false',r'\1true',original_global,count=1)
        modern=re.sub(r"(\n  velocity:\n(?:    .*\n)*?    secret: )''",r"\1probe-forwarding-secret",modern,count=1)
        assert modern!=original_global,'could not turn on Velocity forwarding in paper-global.yml'
        global_config.write_text(modern,encoding='utf-8');start_server()
        assert 'offline mode' not in log() and 'BungeeCord IP forwarding' not in log(),log()[-3000:]
        stop_server();passed('no offline-mode warning on a Paper backend behind Velocity modern forwarding')
        global_config.write_text(original_global,encoding='utf-8')
        legacy=original_spigot.replace('  bungeecord: false','  bungeecord: true',1)
        assert legacy!=original_spigot,'could not turn on bungeecord in spigot.yml'
        spigot.write_text(legacy,encoding='utf-8');start_server()
        assert 'BungeeCord IP forwarding' in log(),log()[-3000:]
        stop_server();passed('warning on a Paper backend behind BungeeCord IP forwarding')
    finally:
        global_config.write_text(original_global,encoding='utf-8');spigot.write_text(original_spigot,encoding='utf-8')
try:
    if kind in ('bukkit','paper'):
        shutil.copy2(ROOT/f'platform-bukkit/build/libs/OriginGate-Bukkit-{v.VERSION}.jar',RUN/'plugins/OriginGate.jar')
        shutil.copy2(args.server,RUN/'server.jar')
        (RUN/'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={PORT}\nonline-mode=false\nlevel-type=FLAT\nview-distance=3\nallow-nether=false\nspawn-monsters=false\nspawn-animals=false\ngenerate-structures=false\nsnooper-enabled=false\nmax-players=20\nnetwork-compression-threshold=-1\n')
        (RUN/'bukkit.yml').write_text('settings:\n  connection-throttle: -1\n  update-folder: update\nauto-updater:\n  enabled: false\n  on-broken: []\n  on-update: []\n')
        # Spigot's own metrics: opt out, so nothing leaves the machine.
        (RUN/'plugins/PluginMetrics').mkdir(parents=True,exist_ok=True)
        (RUN/'plugins/PluginMetrics/config.yml').write_text('opt-out: true\n')
        install_permission_probe();java=JAVA8 if kind=='bukkit' else JAVA21;ready='Done (';stop='stop'
        if kind=='paper':
            # Copy only public server libraries to avoid downloading them again.
            if args.cache:
                for name in ('libraries','cache'):
                    if (args.cache/name).is_dir():shutil.copytree(args.cache/name,RUN/name,dirs_exist_ok=True)
    else:
        shutil.copy2(ROOT/f'platform-bungeecord/build/libs/OriginGate-BungeeCord-{v.VERSION}.jar',RUN/'plugins/OriginGate.jar')
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
  max_players: 20
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
        sink=no_network_sink();jvm_options=['-Dhttps.proxyHost=127.0.0.1','-Dhttps.proxyPort='+str(sink.getsockname()[1])]
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
    # Keeps test servers out of bStats; OriginGate and Paper both read this file.
    (RUN/'plugins/bStats').mkdir(exist_ok=True)
    (RUN/'plugins/bStats/config.yml').write_text('enabled: false\n')
    config()
    (RUN/'eula.txt').write_text('eula=true\n')
    (RUN/'probe-console.log').write_text('',encoding='utf8')
    start_server()
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
    assert login('InvalidReload')[0]=='kicked';passed('invalid reload preserves active settings')
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
    passed('slow lookup bounded and console responsive')
    run_110()
    if kind=='paper':
        stop_server();forwarding_checks()
    (RUN/'results.json').write_text(json.dumps({'platform':kind,'passed':checks},indent=2))
    print('All',len(checks),kind,'checks passed.',flush=True)
finally:
    stop_server()
    if backend:backend.close()
    stub.server.shutdown();stub.server.server_close()
