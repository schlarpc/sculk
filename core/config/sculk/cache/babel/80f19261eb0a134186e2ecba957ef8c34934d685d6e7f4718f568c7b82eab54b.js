sculk.on('packet', (e) => {
  if (e.mod && e.mod.channel === 'othermod:secret') {
    sculk.emit({ saw: e.channel, owner: e.owner, dir: e.frame.dir, len: e.frame.bytes.length,
      same: e.len === e.frame.len && e.type === e.packet.type && e.side === e.conn.side && e.side === 'server',
      data: e.packet.data, action: e.action });
    e.drop();
  }
});