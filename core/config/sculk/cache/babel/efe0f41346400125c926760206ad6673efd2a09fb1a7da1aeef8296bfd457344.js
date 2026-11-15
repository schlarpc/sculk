var seen = [];
sculk.on('packet', 'channel == "othermod:filtered" && packet.data != skip && dir == inbound', (e) => seen.push(String(e.packet.data)));
var conns = 0;
sculk.on('connection', 'conn.side == server', (c) => conns++);