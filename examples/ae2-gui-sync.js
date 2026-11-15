// ==Sculk==
// @name        AE2 GUI sync decoder
// @description Shows AE2's GuiDataSyncPacket fields by name instead of a byte blob
// @run         startup
// @dist        client
// ==/Sculk==

// AE2 (19.x, 1.21.1) syncs a menu's @GuiSync fields to the client as one
// byte blob: for each changed field, its @GuiSync id as a short, then its
// value, ending with the short -1. How a value is written depends on the
// Java type of the field (appeng.menu.guisync.SynchronizedField), so this
// reads the ids and types off the client's open menu: it decodes packets
// for the menu that is open as they arrive, on the client. Fields of other
// types (GenericStack, PacketWritable) stop the walk; what is left shows as
// bytes. Field names are read by reflection, so this wants a NeoForge game
// (Mojang names at run time).

const GuiSync = Packages.appeng.menu.guisync.GuiSync;
const GenericStack = Packages.appeng.api.stacks.GenericStack;
const Minecraft = Packages.net.minecraft.client.Minecraft;

/** {id: {name, type}} for the @GuiSync fields of a menu's class and superclasses. */
function syncedFields(menu) {
  const out = {};
  for (let c = menu.getClass(); c != null; c = c.getSuperclass()) {
    const declared = c.getDeclaredFields();
    for (let i = 0; i < declared.length; i++) {
      const f = declared[i];
      const ann = f.getAnnotation(GuiSync);
      if (ann != null && out[ann.value()] === undefined) out[ann.value()] = { name: String(f.getName()), type: f.getType() };
    }
  }
  return out;
}

/** One value as SynchronizedField writes it for a field of this type; undefined when it cannot say. */
function readValue(r, type) {
  const name = String(type.getName());
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
    const ordinal = r.i16();
    const constants = type.getEnumConstants();
    return ordinal >= 0 && ordinal < constants.length ? String(constants[ordinal]) : ordinal;
  }
  if (name === 'appeng.api.stacks.GenericStack') {
    // GenericStack.readBuffer needs the game's buffer, which a reader does not hand out: show the rest.
    return undefined;
  }
  return undefined;
}

sculk.decoder('appeng.core.network.clientbound.GuiDataSyncPacket', (obj, tree) => {
  const player = Minecraft.getInstance().player;
  const menu = player == null ? null : player.containerMenu;
  if (menu == null || menu.containerId !== obj.containerId()) return; // not the open menu: leave the bytes
  tree.menu = String(menu.getClass().getSimpleName());
  const fields = syncedFields(menu);
  const r = sculk.reader(obj.syncData()); // a record: its components are methods
  tree.fields = {};
  while (r.readable() >= 2) {
    const id = r.i16();
    if (id === -1) break;
    const f = fields[id];
    const value = f === undefined ? undefined : readValue(r, f.type);
    if (value === undefined) {
      tree.fields[f ? f.name : '#' + id] = { $unread: f ? String(f.type.getName()) : 'unknown id', rest: sculk.hex(r.rest()) };
      break;
    }
    tree.fields[f.name] = value;
  }
  delete tree.syncData;
});
