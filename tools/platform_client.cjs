const protocol = require(require('node:path').resolve(process.argv[4], 'minecraft-protocol'));
const client = protocol.createClient({host:'127.0.0.1', port:Number(process.argv[2]), username:process.argv[3], version:process.argv[5], auth:'offline'});
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
