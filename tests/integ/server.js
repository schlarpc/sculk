// Integration test, server side. Loaded with -Dsculk.scripts by scripts/integ.py.
//
// 1. Replays (as raw bytes, past the encoder) the first AppleSkin payload the
//    server sends, so the client must receive it twice.
// 2. Takes the first sculktest Sink payload the server sends (NBT, item
//    stacks, ...; see platforms/shared/*/testmod), edits its NBT through the
//    game's own API, and injects the edited copy as a payload object. The
//    client's test mod must decode it and see the edits.
let replayed = false;
let edited = false;

const isSink = e => e.mod && e.mod.javaClass === 'sculktest.Sink';

sculk.on('packet', e => {
  if (e.dir !== 'outbound' || e.injected || !e.frame.bytes) return;
  if (!replayed && e.owner === 'appleskin') {
    replayed = true;
    const replayConn = e.conn; // Rhino: const is per function, so names must differ between the blocks
    const event = e;
    sculk.schedule(500, () => {
      replayConn.replay(event);
      sculk.emit({ step: 'server_replayed', channel: event.mod.channel, hex: sculk.hex(event.frame.bytes) });
    });
  }
  if (!edited && isSink(e)) {
    edited = true;
    // What a script sees of the payload: the same tree as the event log.
    sculk.emit({ step: 'sink_tree', tree: sculk.tree(e.packet.data) });
    const sinkConn = e.conn;
    const channel = e.mod.channel;
    const data = e.packet.data;
    sculk.schedule(500, () => {
      const tag = data.tag().copy();
      tag.putString('edited', 'by server script');
      tag.putInt('int', 7);
      sinkConn.injectPayload('outbound', channel, sculk.with(data, 'tag', tag));
      sculk.emit({ step: 'sink_injected', channel: channel });
    });
  }
});
