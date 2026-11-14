// Stress test, both sides. Loaded with -Dsculk.scripts by scripts/integ.py --stress.
//
// Replays (as raw bytes, past the encoder) the first play-state payload this
// side sends on every mod channel, so the other side receives each one twice.
// This drives injection through every mod codec in the run; a mod that cannot
// take a duplicate shows up as a disconnect or an error in the logs.
// Startup scripts share one global scope (server.js has its own `replayed`), so keep to a function.
(function () {
  const replayed = {};

  sculk.on('packet', e => {
    if (!e.mod || e.dir !== 'outbound' || e.injected || !e.frame.bytes || e.packet.state !== 'play') return;
    const channel = e.mod.channel;
    // server.js already replays an AppleSkin payload; vanilla and loader channels are not under test.
    if (replayed[channel] || ['appleskin', 'minecraft', 'neoforge', 'forge', 'fml', 'fabric', 'c'].indexOf(e.owner) >= 0) return;
    replayed[channel] = true;
    const conn = e.conn;
    const event = e;
    sculk.schedule(500, () => {
      conn.replay(event);
      sculk.emit({ step: 'stress_replayed', side: conn.side, channel: channel, owner: event.owner,
                   hex: sculk.hex(event.frame.bytes) });
    });
  });
})();
