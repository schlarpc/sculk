var _excluded = ["p"];function _objectWithoutProperties(e, t) {if (null == e) return {};var o,r,i = _objectWithoutPropertiesLoose(e, t);if (Object.getOwnPropertySymbols) {var n = Object.getOwnPropertySymbols(e);for (r = 0; r < n.length; r++) o = n[r], -1 === t.indexOf(o) && {}.propertyIsEnumerable.call(e, o) && (i[o] = e[o]);}return i;}function _objectWithoutPropertiesLoose(r, e) {if (null == r) return {};var t = {};for (var n in r) if ({}.hasOwnProperty.call(r, n)) {if (-1 !== e.indexOf(n)) continue;t[n] = r[n];}return t;}((_o$p, _o$nope$q, _o$nope) => {
  var a = [],b = [],c = [],fs = [];
  for (var i = 0; i < 3; i++) {var x = i * 10;a.push(x);} // rhino#326
  for (var j = 0; j < 2; ++j) {var y = void 0;b.push(String(y));y = 1;} // rhino#1386
  var _loop = function () {var kk = k;fs.push(() => kk);};for (var k = 0; k < 3; k++) {_loop();} // closures
  for (var z of [5, 6]) c.push(z); // Rhino cannot parse this
  var o = { p: { q: 1 } };
  var _p$r = { p: { q: 7 }, r: 8 },q = _p$r.p.q,rest = _objectWithoutProperties(_p$r, _excluded);
  return [a.join(','), b.join(','), fs.map((f) => f()).join(','), c.join(','),
  o === null || o === void 0 || (_o$p = o.p) === null || _o$p === void 0 ? void 0 : _o$p.q, (_o$nope$q = o === null || o === void 0 || (_o$nope = o.nope) === null || _o$nope === void 0 ? void 0 : _o$nope.q) !== null && _o$nope$q !== void 0 ? _o$nope$q : 'dflt', q, JSON.stringify(rest), Math.max.apply(Math, [1, 9, 3])].join('|');
})();