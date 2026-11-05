// Usage: node platform_staff_client.cjs <port> <name> <node_modules> <version> <seconds>
// Joins, stays online, and prints one JSON line per chat message it receives, to check staff alerts in game.
const protocol = require(require('node:path').resolve(process.argv[4], 'minecraft-protocol'));
const client = protocol.createClient({host:'127.0.0.1', port:Number(process.argv[2]), username:process.argv[3], version:process.argv[5], auth:'offline'});
function text(value) {
  if (typeof value === 'string') return value;
  if (Array.isArray(value)) return value.map(text).join('');
  // NBT values arrive as {type, value}; only the value holds text.
  if (value && typeof value === 'object') return 'type' in value && 'value' in value ? text(value.value) : Object.values(value).map(text).join('');
  return '';
}
client.on('login', () => console.log(JSON.stringify({joined: true})));
client.on('system_chat', packet => console.log(JSON.stringify({chat: text(packet.content)})));
client.on('kick_disconnect', p => { console.log(JSON.stringify({kicked: JSON.stringify(p.reason)})); process.exit(0); });
client.on('error', e => { console.error(e); process.exit(1); });
setTimeout(() => { client.end(); setTimeout(() => process.exit(0), 100); }, Number(process.argv[6]) * 1000);
