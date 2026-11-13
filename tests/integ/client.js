// Integration test, client side. Loaded with -Dsculk.scripts by scripts/integ.py.
//
// Once in play, takes a real "move player position" frame the client sent,
// nudges X by one ulp (flips the low bit of the double's last byte), and
// injects it raw. The server should receive a frame the client never
// naturally produced. Frame layout in every supported version:
// [packet id: 1 byte][x: f64][y: f64][z: f64][on-ground flags: 1 byte].
let joined = false;
let lastMove = null;
let injected = false;

const isMove = e => e.dir === 'outbound' && e.state === 'play' && /move_player_pos$|MovePlayerPacket\$Pos$/.test(e.type);

sculk.on('packet', e => {
  if (isMove(e) && !e.injected && e.frame) lastMove = e;
  if (!joined && e.dir === 'inbound' && e.state === 'play') {
    joined = true;
    sculk.emit({ step: 'client_joined' });
    sculk.schedule(5000, injectMove);
    sculk.schedule(9000, () => sculk.emit({ step: 'done' }));
  }
});

function injectMove() {
  if (!lastMove) {
    sculk.emit({ step: 'client_inject_skipped', reason: 'no move packet seen' });
    return;
  }
  const frame = sculk.copy(lastMove.frame); // Java byte[]: signed elements, index-assignable
  if (frame.length !== 26) {
    sculk.emit({ step: 'client_inject_skipped', reason: 'unexpected move frame length ' + frame.length });
    return;
  }
  frame[8] = frame[8] ^ 1; // least significant byte of x
  lastMove.connection.inject('outbound', frame);
  injected = true;
  sculk.emit({ step: 'client_injected', type: lastMove.type, hex: sculk.hex(frame), original: sculk.hex(lastMove.frame) });
}
