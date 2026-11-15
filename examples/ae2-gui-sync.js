// ==Sculk==
// @name        AE2 GUI sync decoder
// @description Shows AE2's GuiDataSyncPacket fields by name instead of a byte blob
// @run         startup
// @dist        client
// ==/Sculk==

// AE2 (19.x, 1.21.1) syncs a menu's @GuiSync fields to the client as one
// byte blob: for each changed field, its @GuiSync id as a short, then its
// value, ending with the short -1. How a value is written depends on the
// Java type of the field (appeng.menu.guisync.SynchronizedField), so the
// blob can only be read knowing the menu's class.
//
// The packet names only a container id. sculk.game.menu(id) knows the
// menu's type from the open-screen payload that came first (the client's
// own open menu is no good: it opens on the game thread, after the first
// syncs are decoded here). The class comes from the type: AE2 builds each
// MenuType with MenuTypeBuilder.create(XMenu::new, ...), and Java names
// that lambda's class after the class it was written in, XMenu.
//
// Fields of types this cannot read (GenericStack, PacketWritable) stop the
// walk; what is left shows as hex. Names are read by reflection, so this
// wants a NeoForge game (Mojang names at run time).

let BuiltInRegistries = Packages.net.minecraft.core.registries.BuiltInRegistries;
let ResourceLocation = Packages.net.minecraft.resources.ResourceLocation;
let GuiSync = Packages.appeng.menu.guisync.GuiSync;

/** Menu type "ns:path" -> {menu class name, fields {id: {name, type}}}, or null when unknown. */
let menus = {};

function field(obj, name) {
  if (obj == null) return null;
  for (let c = obj.getClass(); c != null; c = c.getSuperclass()) {
    try {
      let f = c.getDeclaredField(name);
      f.setAccessible(true);
      return f.get(obj);
    } catch (e) { /* not on this class */ }
  }
  return null;
}

/** The menu class for an AE2 menu type, from the XMenu::new lambda its builder keeps. */
function menuClass(typeKey) {
  let type = BuiltInRegistries.MENU.get(ResourceLocation.parse(typeKey));
  let factory = field(field(field(type, 'constructor'), 'arg$1'), 'factory');
  if (factory == null) return null;
  let lambda = String(factory.getClass().getName());
  let cut = lambda.indexOf('$$Lambda');
  if (cut < 0) return null;
  return java.lang.Class.forName(lambda.substring(0, cut), false, factory.getClass().getClassLoader());
}

/** {id: {name, type}} for the @GuiSync fields of a class and its superclasses. */
function syncedFields(cls) {
  let out = {};
  for (let c = cls; c != null; c = c.getSuperclass()) {
    let declared = c.getDeclaredFields();
    for (let i = 0; i < declared.length; i++) {
      let f = declared[i];
      let ann = f.getAnnotation(GuiSync);
      if (ann != null && out[ann.value()] === undefined) out[ann.value()] = { name: String(f.getName()), type: f.getType() };
    }
  }
  return out;
}

function menuInfo(typeKey) {
  if (!(typeKey in menus)) {
    let cls = null;
    try { cls = menuClass(typeKey); } catch (e) { sculk.log('no menu class for ' + typeKey + ': ' + e); }
    menus[typeKey] = cls == null ? null : { cls: String(cls.getSimpleName()), fields: syncedFields(cls) };
  }
  return menus[typeKey];
}

/** One value as SynchronizedField writes it for a field of this type; undefined when it cannot say. */
function readValue(r, type) {
  let name = String(type.getName());
  switch (name) {
    case 'boolean': case 'java.lang.Boolean': return r.bool();
    case 'int': case 'java.lang.Integer': return r.i32();
    case 'long': case 'java.lang.Long': return r.i64();
    case 'double': case 'java.lang.Double': return r.f64();
    case 'java.lang.String': return r.string();
    case 'net.minecraft.resources.ResourceLocation': return r.bool() ? r.identifier() : null;
    case 'net.minecraft.network.chat.Component': return r.bool() ? r.component() : null;
  }
  if (type.isEnum()) {
    let ordinal = r.i16();
    let constants = type.getEnumConstants();
    return ordinal >= 0 && ordinal < constants.length ? String(constants[ordinal]) : ordinal;
  }
  return undefined;
}

sculk.decoder('appeng.core.network.clientbound.GuiDataSyncPacket', (obj, tree) => {
  let open = sculk.game.menu(obj.containerId());
  if (open == null) return; // a menu we did not see open: leave the bytes
  let info = menuInfo(open.type);
  tree.menu = info == null ? open.type : open.type + ' (' + info.cls + ')';
  if (info == null) return;
  let r = sculk.reader(obj.syncData()); // a record: its components are methods
  tree.fields = {};
  while (r.readable() >= 2) {
    let id = r.i16();
    if (id === -1) break;
    let f = info.fields[id];
    let value = f === undefined ? undefined : readValue(r, f.type);
    if (value === undefined) {
      tree.fields[f ? f.name : '#' + id] = { $unread: f ? String(f.type.getName()) : 'unknown id', rest: sculk.hex(r.rest()) };
      break;
    }
    tree.fields[f.name] = value;
  }
  delete tree.syncData;
});
