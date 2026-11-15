sculk.decoder('sculk.core.ScriptApiTest$Blob', (obj, tree) => {
  var r = sculk.reader(obj.data());
  tree.fields = [];
  while (r.readable() > 0) tree.fields.push({ key: r.u16(), name: r.string() });
  delete tree.data;
});