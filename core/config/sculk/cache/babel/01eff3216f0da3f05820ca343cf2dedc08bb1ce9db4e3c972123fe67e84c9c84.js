(() => {
  var w = sculk.writer().varInt(300).i32(-2).string('é').bool(true).i64('1152921504606846976').
  varLong(1).uuid('00000000-0000-0001-0000-000000000002').f32(1.5).byteArray('cafe').i16(-1);
  var r = sculk.reader(w.toBytes());
  return [w.hex(), w.length(), r.varInt(), r.i32(), r.string(), r.bool(), r.i64(), r.varLong(), r.uuid(),
  r.f32(), sculk.hex(r.byteArray()), r.i16(), r.readable()].join(',');
})();