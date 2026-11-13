// Integration test, server side. Loaded with -Dsculk.scripts by scripts/integ.py.
//
// Replays (as raw bytes, past the encoder) the first AppleSkin payload the
// server sends, so the client must receive it twice.
let replayed = false;

sculk.on('packet', e => {
  if (replayed || e.dir !== 'outbound' || e.owner !== 'appleskin' || e.injected || !e.frame) return;
  replayed = true;
  const conn = e.connection;
  const event = e;
  sculk.schedule(500, () => {
    conn.replay(event);
    sculk.emit({ step: 'server_replayed', channel: event.channel, hex: sculk.hex(event.frame) });
  });
});
