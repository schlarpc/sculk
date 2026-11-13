// Integration test, client side. Loaded with -Dsculk.scripts by scripts/integ.py.
//
// Once in play, takes a real "move player position" frame the client sent,
// nudges X by one ulp (flips the low bit of the double's last byte), and
// injects it raw. The server should receive a frame the client never
// naturally produced. Frame layout in every supported version:
// [packet id: 1 byte][x: f64][y: f64][z: f64][on-ground flags: 1 byte].
//
// Event-driven rather than timed: slow (software-rendered) clients can take a
// while after joining before they send their first movement packet.
let joined = false;
let scheduled = false;
let finished = false;

const isMove = e => e.dir === 'outbound' && e.state === 'play' && /move_player_pos$|MovePlayerPacket\$Pos$/.test(e.type);

function finish(delayMillis) {
  if (finished) return;
  finished = true;
  sculk.schedule(delayMillis, () => sculk.emit({ step: 'done' }));
}

sculk.on('packet', e => {
  if (!joined && e.dir === 'inbound' && e.state === 'play') {
    joined = true;
    sculk.emit({ step: 'client_joined' });
    sculk.schedule(90000, () => {
      if (scheduled) return;
      sculk.emit({ step: 'client_inject_skipped', reason: 'no move packet within 90s of joining' });
      finish(0);
    });
  }
  if (joined && !scheduled && isMove(e) && !e.injected && e.frame) {
    scheduled = true;
    const move = e;
    sculk.schedule(1000, () => {
      injectMove(move);
      finish(4000);
    });
  }
});

function injectMove(move) {
  const frame = sculk.copy(move.frame); // Java byte[]: signed elements, index-assignable
  if (frame.length !== 26) {
    sculk.emit({ step: 'client_inject_skipped', reason: 'unexpected move frame length ' + frame.length });
    return;
  }
  frame[8] = frame[8] ^ 1; // least significant byte of x
  move.connection.inject('outbound', frame);
  sculk.emit({ step: 'client_injected', type: move.type, hex: sculk.hex(frame), original: sculk.hex(move.frame) });
}
