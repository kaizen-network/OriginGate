// Usage: node platform_client.cjs <port> <name> <node_modules> <version> [local address]
// The optional local address (such as 127.0.0.5) lets one machine log in from several IPs.
const net = require('node:net');
const protocol = require(require('node:path').resolve(process.argv[4], 'minecraft-protocol'));
const port = Number(process.argv[2]);
const localAddress = process.argv[6];
const options = {host:'127.0.0.1', port, username:process.argv[3], version:process.argv[5], auth:'offline'};
if (localAddress) options.connect = client => {
  client.setSocket(net.connect({host:'127.0.0.1', port, localAddress}));
  client.emit('connect');
};
const client = protocol.createClient(options);
let finished=false;
function done(outcome,reason) {
  if(finished)return;finished=true;
  console.log(JSON.stringify([outcome,reason]));
  client.end();setTimeout(()=>process.exit(0),100);
}
client.on('login',()=>done('joined','join game packet'));
client.on('kick_disconnect',p=>done('kicked',JSON.stringify(p.reason)));
client.on('disconnect',p=>done('kicked',JSON.stringify(p.reason)));
client.on('error',e=>{if(!finished){console.error(e);process.exit(1);}});
setTimeout(()=>{if(!finished){console.error('Login timed out');process.exit(1);}},20000);
