(() => {var c = sculk.connection(10);
  var e = sculk.game.entity(42, c),m = sculk.game.menu(3, c);
  return [e.type, e.spawnPos.y, m.title, sculk.game.entity(43, c), sculk.game.menu(4, c)].join(',');})();