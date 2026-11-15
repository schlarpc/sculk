# Architecture

## Objective

Sculk is a Minecraft mod for protocol-level security research on modded
Minecraft. It does for game traffic what Burp, Wireshark, Frida and
Greasemonkey do for their domains:

- **Observe:** capture every packet on a connection, vanilla and modded. Say
  which mod each packet belongs to, and decode mod payloads with the mod's own
  codec.
- **Intercept:** hold, edit, drop or replay packets in flight, by hand or by rule.
- **Inject:** send new packets into either direction of a live connection. Send
  them as structured objects or as raw frames, including malformed ones.
- **Act:** drive normal gameplay (move, look, use items, click blocks and
  containers, chat) through the same API. Each action can go through the game
  ("legit", state stays in sync) or straight onto the wire ("raw", client-side
  checks are skipped).
- **Script:** all of the above from JavaScript (Rhino), with one script API
  that works on every supported loader and Minecraft version.

### Both sides of the connection

Sculk runs on the **client and on the server**. Both are first-class
attackers:

| Sculk runs on | Plays the role of | Typical target |
|---|---|---|
| Client | Malicious client | Server-side mod handlers: missing permission checks, trusting client-supplied positions or IDs, unbounded allocations, item duplication, crashes from bad payloads |
| Server | Malicious server | Client-side mod handlers: unsafe deserialisation, unbounded NBT or collections, path or URL handling, client crashes, render-thread lockups |

Every hook, event and API in this document takes a `Side`. A feature that
works on only one side is a bug unless the docs here say why.

Intended use is testing clients and servers you own or are authorised to
test.

## Design rule: a thin seam

The aim is that about 90% of the code is shared by every target. The only
code allowed to touch Minecraft or a mod loader is a small adapter per
target. Everything else is plain Java plus Netty.

```
            ┌───────────────────────────────────────────────┐
  UI  ◄────►│ control server (localhost HTTP + token, MCP)  │   web UI in a browser
            ├───────────────────────────────────────────────┤
            │ scripting (Rhino)  │ rules │ history / replay │
            ├───────────────────────────────────────────────┤   core/
            │ packet model: field tree + byte spans         │   (shared)
            │ read-tracing dissector                        │
            │ intercept queue / injector                    │
            ├───────────────────────────────────────────────┤
            │ PipelineTap: Netty handlers on the game pipe  │
            ╞═══════════════════════════════════════════════╡
            │ Platform: protocol / payloads / attribution / │   platforms/<target>/
            │ actions + a few mixins                        │   (per target)
            └───────────────────────────────────────────────┘
```

### Why the seam can be this thin

1. **The game's codecs are Netty handlers.** `PacketDecoder.decode` and
   `PacketEncoder.encode` override Netty methods, so their names never change
   or get obfuscated. A hook in each one sees every frame (uncompressed,
   unencrypted) together with the packet object it becomes or came from.
   Injection is pure Netty, anchored on those handlers by name. All of it
   lives in core; the platform mixins are a few lines each.
2. **Mod codecs can be called without knowing their types.** The dissector
   wraps the `ByteBuf` in a recording buffer and runs the mod's decoder on a
   copy. Each primitive read is logged with its offset, length and caller.
   That gives Wireshark-style field spans for any codec style: Forge
   `SimpleChannel` lambdas, Fabric inline receivers, or `StreamCodec`s.
3. **Scripts never see Minecraft classes.** They see the core packet model
   and action API, so one script runs on every target.

## Pipelines as observed

These are from `scripts/smoke-server.sh`, server side, at `channelActive`:

```
forge 1.20.1     timeout, legacy_query, splitter, decoder, prepender, encoder,
                 unbundler, bundler, packet_handler
neoforge 1.21.1  timeout, legacy_query, splitter, FlowControlHandler#0, decoder,
                 prepender, outbound_config, hackfix, packet_handler
fabric 26.3      timeout, legacy_query, splitter, FlowControlHandler#0, decoder,
                 prepender, outbound_config, hackfix, packet_handler
```

`compress`/`decompress` (after the login compression packet) and
`encrypt`/`decrypt` (online mode) are added later, **in different places per
version**: 1.20.1 inserts `decompress` before `decoder`, 1.21.1 inserts it
after `splitter`. In era C the codecs are also swapped at every protocol
state change, and `bundler` is added after `decoder` on entering play.

### Why sculk hooks the codecs, not the pipeline

The first design put sculk's own handlers next to `decoder`/`encoder`. The
sources above show why that is brittle: depending on version, a later
`decompress`/`compress` or `bundler` lands between sculk's handler and the
codec, so the tap silently sees compressed or bundled data. Hooking the codec
methods themselves avoids every ordering question:

| Point | Hook | Sees |
|---|---|---|
| Inbound | `PacketDecoder.decode`, HEAD + RETURN | the frame's bytes and the decoded packet(s) |
| Outbound | `PacketEncoder.encode`, HEAD + RETURN | the packet and the bytes it encoded to |
| Connection | `Connection.channelActive`, TAIL | the channel and which side we are |

Listeners can drop a packet, replace it, or replace its frame, in either
direction (`WireHooks`). Injection (`SculkConnection`) writes from the
encoder's context (outbound frame: compressed and encrypted by the game, never
re-encoded), from the tail (outbound packet: encoded by the game), into the
decoder (inbound frame) or from the decoder's context (inbound packet).
Dropping an outbound packet leaves an empty write, which becomes a
zero-length frame that every supported decoder ignores.

## Protocol eras

| Era | Versions | What changes |
|---|---|---|
| A | ≤ 1.20.1 | States: handshake, status, login, play. `ConnectionProtocol` enum with ID maps. Custom payloads are an ID plus a raw `FriendlyByteBuf`, and vanilla never decodes them. |
| B | 1.20.2 – 1.20.4 | Adds the configuration phase and the `CustomPacketPayload` interface. **Not targeted**: few mods use these versions. |
| C | 1.20.5 → 26.x | `StreamCodec` everywhere. Each payload has a `CustomPacketPayload.Type` and a codec registered **before** decode, so payloads reach `packet_handler` already decoded. `ProtocolInfo` replaces the enum. |

## Loader networking

| | Registration | Wire format | Where the decoders are |
|---|---|---|---|
| Forge 1.20.1 | `SimpleChannel` `messageBuilder` / `registerMessage(index, cls, enc, dec, handler)` | Custom payload on `modid:name`. First byte is the discriminator. FML handshake on `fml:loginwrapper` / `fml:handshake` | `IndexedMessageCodec` (reflection) |
| NeoForge 1.21.1+ | `RegisterPayloadHandlersEvent` → `PayloadRegistrar.playToServer(type, codec, handler)` | Vanilla custom payload. Channels negotiated in configuration. Large payloads are split | `NetworkRegistry` (`PayloadRegistration` records) |
| Fabric 1.20.1 | `ServerPlayNetworking.registerGlobalReceiver(id, handler)` | Raw buffer. Channels announced via `minecraft:register` | Often none: parsing is inline in the receiver, so use the read-tracing dissector |
| Fabric 1.20.5+ | `PayloadTypeRegistry.playC2S().register(id, codec)` | Vanilla custom payload | `PayloadTypeRegistryImpl` |

## Runtime names

| Target | Names at runtime | Consequence |
|---|---|---|
| Forge 1.18.2 – 1.20.1 | Mojang class names, SRG member names | Mixins are remapped through a refmap (handled by the build). The script name bridge needs SRG tables. |
| NeoForge 1.21.1 | Mojang | none |
| Fabric ≤ 1.21.11 (sculk: 1.21.1) | Intermediary | Loom's remapping plugin (`net.fabricmc.fabric-loom-remap`) builds against Mojang names and remaps the jar. Dev runs use Mojang names. Trees of types the renderer does not know show `class_1234`/`field_5678` names in production; the script name bridge needs intermediary tables. |
| All of 26.x | Unobfuscated | none |

Compiled adapter code always uses Mojang names; the build remaps where
needed. Only reflection on names chosen at runtime (the script bridge) needs
mapping tables.

## The `Platform` seam

`core/src/main/java/sculk/core/platform/Platform.java` is the whole contract.
"Codec" below is the game's own `PacketDecoder`/`PacketEncoder` instance,
which carries the protocol state.

| Method | Era C (1.21.1 and 26.3, both loaders) | Era A (Forge 1.18.2, 1.19.2, 1.20.1) |
|---|---|---|
| `describe(packet, codec)` | `ProtocolInfo` from the codec; payload already decoded | protocol from the packet class; payload bytes decoded via `payloadCodec` |
| `encode` / `decode` | `ProtocolInfo.codec()` | `ConnectionProtocol` + `Packet.write` / `createPacket` (state from the channel) |
| `payloadCodec(channel)` | not needed | the SimpleChannel's own decoder, chosen by discriminator byte |
| `payloadPacket(channel, payload, clientbound)` | wraps the payload object in a `Clientbound`/`ServerboundCustomPayloadPacket` | encodes it with the channel's codec (discriminator first), wraps the bytes |
| `renderer()` | `sculk.mc.GameTrees` (below) | the same, with NBT-tagged item stacks |
| `modOf(class)` | NeoForge 1.21.1: JPMS module → mod file. NeoForge 26.3 (FML 12, no per-mod modules) and Fabric: the mod file that contains the class file | JPMS module → mod file |

The era C protocol methods are the same code for both loaders of a
version: `platforms/shared/mc-<version>/main` (`sculk.mc.Protocols`,
`GameTrees`, `Chat`), compiled into each platform named by its
`shared_mc` property. The platform classes keep only what is the
loader's own: mod lists, config, commands, chat delivery, registration
mixins.

### Game types in trees

`packet.data` (and `sculk.tree`) turns packets and payload objects into trees by
reflection, which shows game types badly: an NBT compound is a hash map of
tag objects, an item stack drags in its item's whole class graph, and an
identifier is `{namespace, path}`. Each platform's renderer
(`Platform.renderer`, `sculk.mc.GameTrees`) shows them the way a person
would write them; it also applies to map keys:

| Type | Tree |
|---|---|
| NBT | JSON: compounds as objects with keys sorted, lists and int/long arrays as lists, byte arrays as `{"$bytes": hex}`, numbers and strings as themselves. Its own depth budget (32), whatever the tree depth. |
| item stack | `{item, count, components}` (1.20.5+: each component as its codec writes it, as in `/give`; by reflection where the codec needs registries; `"!id": null` for a removed default) or `{item, count, tag}` (1.20.1) |
| identifier, resource key, registered holder, anything in a built-in registry (item, block, entity type, ...) | `"ns:path"` |
| block position, vector | `{x, y, z}` |
| block state | `"ns:block[prop=value,...]"` |
| text component | JSON text (`{"text": ..., "color": ...}`, or a plain string) |
| byte array, Netty `ByteBuf` (core) | `{"$bytes": hex}` (of a buffer's readable bytes), with `$len` when the hex is cut short (1 KB per array, 8 KB per tree) and `$text` holding its runs of 4+ printable characters, one per line |

Beyond the renderers, core shows Guava multimaps and tables as their map
views (`asMap()`, `rowMap()`) rather than their internals, a lambda as
`"(lambda) Interface"` rather than what it captured, and JDK classes
(`java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`) as their string. One tree
holds at most 4096 values and 8 KB of hex; past that, values show as `…`
and the tree's top gets `"$cut": true`, so a filter or reader can tell
it is partial (`packet.data.$cut`). Only the view is cut: scripts hold the
whole live object, and `sculk.tree(x, {nodes, depth})` makes a bigger view.

The tree is a view: types are lost (an NBT byte and int are both
numbers). Scripts keep the live objects: `e.packet.data.tag().copy()` is the
game's own `CompoundTag`. A renderer that throws falls back to
reflection for that value.

Each platform also has one mixin on its loader's payload registration entry
point (`NetworkRegistry.register`, `PayloadTypeRegistryImpl.register`,
`IndexedMessageCodec.addCodecIndex`). It records the registration and the
call stack in `PayloadRegistry`.

Two more methods face the player rather than the wire:

- `registerCommands()` registers `/sculk` (`SculkCommand`, generic over the
  command source): a client command in a game client, for operators on a
  dedicated server.
- `chat(ChatLine)` shows a line only the player sees: the local chat in a
  game client, the console on a dedicated server. A `ChatLine` is text with
  buttons, each running a `/sculk` command when clicked (green, in
  brackets); each platform's `Chat` class builds the game's component
  (26.3: `ClickEvent.RunCommand` records; earlier: `ClickEvent` with an
  action). A click reaches the client command on Fabric (Fabric API hooks
  `sendUnattendedCommand`) and NeoForge (`sendUnsignedCommand`); Forge
  1.20.1 sends clicks straight to the server, so a client mixin there hands
  `sculk` commands to `ClientCommandHandler` first (1.19.2 clicks go
  through `LocalPlayer.commandUnsigned` instead; 1.18.2 already offers
  clicks to client commands, so it needs no mixin).

### Attribution

`Attribution` reports two things per payload channel:

- **owner:** the channel namespace when it is a loaded mod; otherwise the mod
  that defines the payload class; otherwise the outermost mod on the
  registration stack;
- **registeredVia:** every non-loader mod on the registration stacks.

Observed with real mods: Waystones registers its 24 payload types through
Balm, from Balm's own event handler, so no Waystones code is on the stack.
The result is `owner: waystones, registeredVia: [balm]`.

### Round trips

For every custom payload sculk re-encodes the decoded packet and compares
it with the wire bytes: `ok` (identical), `equivalent` (different bytes,
same content once collection order is ignored) or `mismatch`. With real mods
on NeoForge, all AppleSkin and Waystones payloads are `ok`. NeoForge's own
channel negotiation payloads are `equivalent`: they carry hash-ordered sets
and maps keyed by enums, whose iteration order differs between JVMs. Editing
and re-sending such a payload changes its byte order, which matters for
anything that hashes or signs payload bytes.

### Still planned

- **Actions:** about 30 verbs, each in a legit and a raw flavour, for each
  side:
  - client: move, look, `useItemOn`, `attack`, `clickContainer`, chat,
    command, …
  - server: send clientbound packets to a chosen player, spoof entity and
    block updates, open screens, push custom payloads the client never asked
    for, …
- **Read-tracing dissector:** field-level byte spans (above).
- **Vanilla packets caused by mods:** attribute by walking the stack at
  `Connection.send`.
- **Login-phase payloads** (`fml:loginwrapper`, custom queries) and NeoForge
  split payloads.

## Scripting

Scripts are JavaScript (Rhino, ES6 mode), run through Babel first
(`Transpiler`): only the transforms for what Rhino gets wrong or lacks.
Rhino gives a loop body's `const` and `let` one binding for the whole loop
(mozilla/rhino#326, #1386; a `const` keeps its first value), and does not
parse `for (const x of ...)`, `?.`, `??` or some destructuring and spread;
Babel's block scoping gives each pass its own binding (a function per pass
where a closure needs one, as Rhino's maintainers suggest) and lowers the
rest. `retainLines` keeps every line where it was, so errors and the
syntax check point at the script's own lines. Babel (`@babel/standalone`,
pinned by an npm lockfile in nix/babel/ and fetched by Nix's importNpmLock,
packed under `sculk/babel/`) takes Rhino about two seconds and 18 MiB to
load: it loads once, on a background thread at startup, in a sealed scope.
Output is kept by a hash of the source, in memory and under
`<config>/sculk/cache/babel/`, so unchanged scripts are not transformed
again, even after a restart. The syntax check reports Rhino's problems
with the script as written when Rhino can parse it, else Babel's, else
Rhino's with Babel's output.

Installed scripts are the `.js`
files in `<config>/sculk/scripts/` (or `-Dsculk.scriptDir`), so a modpack
can ship them in its `config/` folder. `x.js.disabled` is script `x.js`,
disabled, the way launchers disable mod jars. Files named in
`-Dsculk.scripts` (dev runs: `-Psculk.scripts=...`) are scripts too, started
first; they are managed where they live, not from the UI.

A script may start with a metadata block, like a userscript's:

```js
// ==Sculk==
// @name        Drop exhaustion sync
// @description Stops AppleSkin's exhaustion packets reaching the client
// @run         startup    (startup: run at launch and on reload; manual: only when asked)
// @dist        client     (optional: client = a game client and its integrated server; server = a dedicated server)
// ==/Sculk==
```

Every running script has its own top-level scope (on a shared scope of
built-ins, Rhino's sharing-scopes pattern) and owns the listeners and timers
it registers, so one script starts, stops or restarts without touching the
others (`ScriptManager`). The console (`/api/eval`, the REPL, `sculk_eval`)
has a scope of its own; `sculk.scope('x.js')` reaches a running script's
globals from anywhere.

What happens to a script is one set of rules (`Lifecycle`, a pure planner;
`ScriptManager` carries out its plans), whoever changed the file: the web
UI, sculkctl, MCP, or an editor, `git checkout` and the like. sculk polls
the script files (every 500 ms; `-Dsculk.watch=false` turns it off,
`-Dsculk.watchMillis` sets the period) and applies a change once the file
has held still for one poll.

Each script is **stopped** (with why: `not-started`, `user`, `disabled`,
`deleted`, `wrong-dist`), **running** a source with a given hash, or
**failed**: wanted, but its source threw or did not compile.

- A running script runs its file: a new version restarts it. Changes are
  compared by content, so a file that comes back to the running source
  changes nothing.
- A new version that does not compile never stops a working script: the
  old one keeps running and the new one shows as `rejected`.
- A failed script is still wanted: the next version of its file is tried.
- A stopped script stays stopped however its file changes, unless the file
  becomes runnable (created, enabled, or its `@dist` now matches) and is a
  startup script, which then starts.
- Deleting or disabling a running script stops it; renaming one restarts
  it under its new name (a delete and a create with the same content in
  one poll is a rename).
- Run always runs, whatever the mode; a script that throws while starting
  is stopped at once, so it never runs half-registered.

The `sculk` global is that script's `ScriptApi`:

```js
sculk.on('packet', e => {
  // the same fields as logged packet events (below): e.conn, e.frame, e.packet, e.mod, e.injected, e.action
  if (e.frame.dir === 'inbound' && e.mod && e.mod.owner === 'somemod') e.drop();
  if (e.channel === 'somemod:sync') sculk.emit(sculk.tree(e.packet.data)); // shorthands work here too
});
sculk.on('connection', c => sculk.log('connected as ' + c.side));
// sculk.on('packet', 'channel ~ "^ae2:" && dir == inbound', e => ...): a filter (the events
//   filter language) picks events before the script runs; it builds packet.data, frame.hex
//   and mod.roundTrip only if it reads them. Events have no id yet when listeners run.
// c.inject('outbound', bytes), c.injectPacket('inbound', obj), c.replay(event); sculk.connection(id) finds one
// c.injectPayload('outbound', channel, payload): a mod payload object, wrapped for the side and direction
// e.replacePayload(sculk.with(e.packet.data, 'field', value)): edit a mod payload in flight, same channel
//   (re-encoded with the mod's codec; on a channel whose round trip is mismatch this logs a
//   warning event once, since what is sent may differ: e.replaceFrame(bytes) sends exact bytes)
// sculk.emit(obj), sculk.schedule(ms, fn), sculk.payloadTypes(), sculk.tree(obj),
// sculk.with(record, 'field', value), sculk.copy(bytes), sculk.hex(bytes), sculk.unhex(str)
// sculk.log(msg) (a script_log event), sculk.chat(msg) (a chat line only the player sees; a chat event),
// sculk.scope(name), sculk.name, sculk.dist ('client' or 'server')

// Decoders: show a class's objects your way wherever sculk makes a tree (packet.data in the
// log and the web UI, sculk.tree), for mod data kept as raw bytes.
sculk.decoder('appeng.core.network.clientbound.GuiDataSyncPacket', (obj, tree) => {
  const r = sculk.reader(obj.syncData()); // bytes, a buffer or a hex string (a record: components are methods)
  tree.fields = [];
  while (r.readable() > 0) tree.fields.push(r.varInt());
  delete tree.syncData;                    // change tree in place, or return a new one
});
// readers: varInt varLong bool i8 u8 i16 u16 i32 i64 f32 f64 string uuid bytes(n) byteArray rest
// skip(n) readable position, and game types read by the game's codecs (where the platform
// supports it: 1.21.1 and 26.3): nbt identifier blockPos itemStack component
// sculk.writer(): the same names, chained, writing (game types also take plain forms: "ns:path",
//   {x, y, z}, SNBT text, a string for text); then toBytes(), hex(), length(). For exact bytes:
e.replaceFrame(sculk.writer().varInt(0x19).string('ae2:me_inventory_update').bytes(patched).toBytes());

// What the packets have said about the game, so ids in packets mean something:
sculk.game.entity(37850)   // {id, type: 'minecraft:zombie', uuid, spawnPos, name (players), self (ours)} or null
sculk.game.menu(5)         // {containerId, type: 'ae2:pattern_provider', title} or null
sculk.game.menus(), sculk.game.summary()   // each lookup also takes a connection
```

Each connection keeps a `GameState` (`sculk.core.game`), fed before
scripts see a packet by `Platform.observe`, from what packets mean
rather than their direction (a server-side connection learns from what
it sends): spawned and removed entities, the player list, opened and
closed menus (vanilla, and NeoForge's `advanced_open_screen`, which mods
like AE2 use), the login and respawns. A lookup without a connection asks
the one whose packet is being handled, else the game client's own.

A decoder runs when the tree is made: for logged events, as the packet
passes, so it shows on packets captured after it starts, not before. It
gets the plain tree (as sculk would show it) to change or replace; live
game objects in what it returns are shown as trees too. It matches the
class and its subclasses, the newest decoder for a class wins, it leaves
with its script, and one that throws logs a `script_error` and the plain
tree is shown. Game types that need registries (item stacks, text) use
those of the connection whose packet is being handled, else the client's.

### Events

`-Dsculk.eventLog=path` writes a JSON Lines log of everything sculk sees:
`start`, `connection`, `disconnect`, `payload_type`, `packet`, `eval`,
`mark` (`text`, and `by`: `command` for `/sculk mark`), `chat` (`text`
shown to the player, and `script` when a script said it), `mcp_client`
(`action`: requested, accepted or revoked; `client`: `{number, client,
since, approved}`), and
for scripts `script` (`sculk.emit`), `script_log` (`sculk.log`),
`script_error`, `script_run`, `script_stop`, `script_file` (created, saved,
changed, renamed, enabled, disabled, deleted; `by` is `api` or `disk`) and
`script_reload`. Script events carry `script`, the script's name (`console`
for the console); `script_run`, `script_stop` and `script_error` also carry
`cause` (`file`, `user` or `launch`), and a `script_error` whose `callback`
is `compile` with `kept` means a change was refused and the old version
kept running. Every entry has an `id` that
goes up by one per entry, and `kind` and `t` (epoch millis).

Packet events are layered, like a Wireshark dissection: each layer is an
object, and a layer is absent when it does not apply.

```
injected, action          what sculk did: action is pass, drop or replace
conn    {id, side, remote}                          the connection
frame   {seq, dir, len, hex, spans}                 the bytes on the wire
packet  {id, state, flow, type, javaClass, owner,   the game's packet, and
         data}                                      what it carries
mod     {channel, owner, registeredVia, javaClass,  custom payloads only:
         roundTrip, roundTripDetail}                the channel and its mod
```

- `frame.spans` lists where fields sit in the frame, as
  `{field, off, len}`: `packet.id`, then (for a custom payload)
  `mod.channel`, then `packet.data`.
- `packet.owner` is the mod whose class the packet is (usually
  `minecraft`); `mod.owner` is the mod that owns a payload's channel.
- `packet.data` is what the packet carries, as an object tree: for a
  custom payload, the decoded payload (`mod.javaClass` is its class; null
  when nothing decoded it), else the packet itself. `mod.roundTrip` is `ok`,
  `equivalent`, `mismatch`, `opaque`, `error` or `n/a` (see Attribution
  above), with `mod.roundTripDetail` saying more for mismatch (the
  re-encoded hex), opaque and error.
- `connection` and `disconnect` events carry `conn`; `payload_type` events
  carry `mod` (with `codecClass`, `protocol`, `flow` and `discriminator`
  instead of `roundTrip`).

Scripts get the same tree: a path names the same thing in a filter, in the
log and in code. Where the log has a serialised copy, scripts get the live
thing: `e.conn` is the connection (with `inject` and `replay`),
`e.frame.bytes` the frame, `e.packet.object` the packet (for
`replacePacket`) and `e.packet.data` the payload object (for
`replacePayload`), or the packet again for a vanilla packet.

Shorthands, in filters and in scripts: `dir` (`frame.dir`), `len`
(`frame.len`), `side` (`conn.side`), `type` (`packet.type`), `channel`
(`mod.channel`) and `owner` (`mod.owner` if there is a mod layer, else
`packet.owner`).

## Control server

The control server is one HTTP listener for tools, agents and the web UI: a
filterable live event log, a REPL, a script editor, and an MCP server. It
is on by default, on port 25580; when another game on the machine holds
that, the next free one up to 25589, else any free port. The port it got is
in `sculk-control.json` and the game log, and `/sculk ui` opens it.
`-Dsculk.control=PORT` asks for exactly that port (0: any free one) and
`-Dsculk.control=off` turns the server off (dev runs: `-Psculk.control=...`).

It captures every event of the session with its whole frame (the event log
cuts long frames short), whether or not `sculk.eventLog` is set, so queries
reach back to the start of the session, like a capture file that is still
being written (`EventStore`):

- **Spool.** A writer thread appends events to 64 MiB segment files in a
  temporary capture directory (`-Dsculk.captureDir`, default the system
  temp directory), in zstd-compressed blocks of about 256 KiB (JSON and
  frames compress about ten to one; a block is written when full, when
  the writer goes quiet, or before the hot ring would have to let its
  events go). The index points at each event's block and its place in it;
  reading one back unpacks its block, and the last few unpacked blocks are
  kept for scans. Past the cap (`-Dsculk.captureBytes`, default 2 GiB) the
  oldest segment is deleted. The directory goes when the game exits, and
  ones left by a crashed game are cleaned up at the next launch. Captures
  do not persist across launches. `-Dsculk.captureBytes=0` keeps only the
  newest events in memory.
- **Index.** In memory, about 47 bytes per event: id, t, kind, conn.id,
  conn.side, frame.dir, frame.len, packet.type, packet.owner, packet.state,
  mod.channel, mod.owner, injected, action and script. A filter that reads
  only those (shorthands included) runs on the index, at about 2 million
  events a second; one that reads anything else (`packet.data...`,
  `frame.hex`) reads events back from disk, at about 400 thousand a
  second. An event whose shape the index cannot mirror exactly is always
  read whole, so both give the same answers.
- **Hot cache.** The newest events (`-Dsculk.controlEvents`, default 50000,
  and `-Dsculk.controlFrameBytes`, default 128 MiB) stay whole in memory
  for live tails, and for anything the writer has not reached.

```
GET  /api/status                        platform, connections, running scripts, last event id, capture use
GET  /api/events?filter=&after=&before=&limit=   JSON Lines; the last `limit` matches (before `before`), or the first after `after`
GET  /api/events/stats?filter=&by=&after=&before=&limit=   count, frame bytes, first and last match, grouped by a field
GET  /api/events/page?filter=&from=&count=  matches by position: {"start", "total", "from", "events"}; positions stay put while held
GET  /api/events/position?filter=&id=   the position of the first match at or after an id
GET  /api/events/stream?filter=&after=  server-sent events, live (backlog first with `after`)
GET  /api/frame?id=                     a packet event's whole frame, as hex
POST /api/eval  body: JavaScript        runs in the console's scope: {"ok":true,"result":...}
POST /api/reload                        stops every script, drops all script state; starts the startup scripts again
GET  /api/scripts                       installed scripts: metadata, enabled, source hash, status (stopped/running/failed)
GET  /api/scripts/source?name=          one script with its source
POST /api/scripts/save?name=&base=      body: source; create or replace (base: the hash it was edited from, "" = new; 409 if stale)
POST /api/scripts/check body: source    syntax errors and warnings from Rhino's parser, without running
POST /api/scripts/run|stop|delete?name= run: (re)start from the file, any run mode; returns its result or error
POST /api/scripts/rename?name=&to=
POST /api/scripts/enable?name=&enabled= disabling renames to .disabled and stops it
POST /mcp       body: JSON-RPC          Model Context Protocol, Streamable HTTP transport
DELETE /mcp                             ends an MCP session
GET  /...                               anything else: the web UI (no token needed for its static files)
```

`scripts/sculkctl.py` wraps it (`status`, `events`, `tail`, `frame`, `eval`,
`repl`, `reload`, `script ls|cat|push|run|stop|enable|disable|rm|mv`) and
finds the server through its control file.

The MCP server speaks both eras of the protocol on the one endpoint
(`McpHandler`, `McpStateless`):

- **2026-07-28** (stateless): no `initialize`, no sessions. Every request
  carries its version and client capabilities in `_meta`, and mirrors its
  version, method and tool name into `MCP-Protocol-Version`, `Mcp-Method`
  and `Mcp-Name` headers, which must match the body (else 400, `-32020`).
  It serves `server/discover`, `tools/list` and `tools/call`; results carry
  `resultType` and the server's `serverInfo`, and list results `ttlMs` and
  `cacheScope`. A version it does not speak gets 400 `-32022` with the
  supported list; an unknown method 404 `-32601`. Closing a streamed
  answer cancels the call. This era needs the token: it has no session to
  pair, and the spec rules out trusting what a client says about itself. A
  request without the token gets a 400 that carries no modern error code,
  so a client that speaks both eras falls back to `initialize` and pairs.
- **2025-11-25 back to 2024-11-05**: JSON-RPC batches only for the versions
  that have them; a request whose `MCP-Protocol-Version` header names any
  other version gets 400. `initialize` hands out an `Mcp-Session-Id`;
  sessions are optional, and an unknown one gets 404.

Claude Code tries 2026-07-28 first (`server/discover`) and falls back on
its own. Most calls answer with plain JSON. A long
`sculk_wait_events` switches to a server-sent event stream when the client
accepts one: it sends `notifications/progress` every 2 s when the call
carries a `progressToken` (keep-alives otherwise), and stops when the client
sends `notifications/cancelled` or goes away. Each tool carries a title and
read-only, destructive, idempotent and open-world hints. It offers the same
powers as tools: `sculk_status`,
`sculk_events` (paging forward or back through the whole capture;
`fields` picks paths instead of whole events, and frame hex is left out
unless `hex` is true), `sculk_wait_events` (long-polls up to 5 minutes for new matches,
since tool calls cannot stream), `sculk_stats` (counts, bytes and first
and last appearance grouped by any field, over the whole capture),
`sculk_frame`, `sculk_eval`,
`sculk_reload`, and for installed scripts `sculk_scripts`,
`sculk_script_read` (with line numbers and hash), `sculk_script_write`
(whole source; optional `base` hash and `run`), `sculk_script_edit` (exact
text replacement, like an editor's find and replace; a running script
restarts) and `sculk_script_control` (run, stop, enable, disable, rename,
delete). Writes and edits report Rhino's syntax errors. The tool descriptions carry the filter syntax, event fields
and script API.

An MCP client gets in one of two ways:

- **Pairing** (`scripts/sculkctl.py mcp-config`): the client's configuration
  holds only the URL, so it never changes; the default port stays the same
  across launches unless another game holds it (`-Dsculk.control=<port>`
  pins one). A client that connects without the token gets
  a session that waits for the player: it can initialize and list tools,
  but a tool call is held until the player approves it (up to 5 minutes,
  reporting progress that names what to type, `/sculk mcp accept <n>`) and
  then runs; if the wait runs out it answers with what to type, for its
  model to pass on. The player approves it in game (or at a
  dedicated server's console); `/sculk mcp list` and `revoke <n>` manage
  approvals, which last until the game exits. After a game restart the
  client finds its session gone (404) and initializes again, often two or
  three times within a second (Claude Code does); sessions from a client of
  the same name share one waiting number, so a single accept covers
  whichever it keeps. They never join a number already approved. Waiting sessions are kept
  apart and few (8), so callers without the token cannot push out approved
  ones, and they cannot send batches, which could carry a tool call past
  the check. The Host and Origin checks apply as everywhere.
- **The token** (`mcp-config --token`): the `Authorization` header, as for
  the HTTP API. The token changes with every launch;
  `-Dsculk.controlToken=<16+ chars>` fixes it for a static client
  configuration (and then anyone who can read that configuration can drive
  the game). `/sculk mcp token` prints it in chat.

Filters are Wireshark-style:

```
kind == packet && dir == inbound && owner != minecraft
type matches "recipe|chat" and len > 1000
packet.data contains "../" or data.step == done
packet.data.pos.x >= 100 && mod.registeredVia == balm
mod && action != pass
```

Fields are entry keys, with dots into nested objects and lists (a list
matches when any element does), plus the shorthands above.
A layer name alone (`mod`) asks whether the event has that layer. Operators are `== != < <= > >=`,
`contains` (substring; on objects, of their JSON) and `matches`/`~`
(regex). A field alone means present and not false. Combine with
`&& || !` or `and or not`, and parentheses. Unquoted values are numbers,
`true`, `false`, `null` or strings.

Anyone who can call this server can run code in the game and inject packets,
so:

- it listens on loopback only (`-Dsculk.control=off` turns it off): 127.0.0.1 and `::1`
  on the same port, bound explicitly (Forge and NeoForge dev runs make the
  JVM prefer IPv6, so "the" loopback address would be `::1` alone). Without
  IPv6 loopback it serves IPv4 only;
- every request needs `Authorization: Bearer <token>`. The token is random
  per launch and is written, with the port, to `sculk-control.json` in the
  game directory (or `-Dsculk.controlFile`), readable by the user only, and
  deleted on exit;
- the `Host` header must be `127.0.0.1`, `[::1]` or `localhost` with this port,
  which stops DNS rebinding; a request with any other `Origin` is refused,
  which stops browsers' cross-site requests;
- anything that changes state is a `POST`; every eval (HTTP or MCP) is
  logged. MCP shares all of these checks.

### Web UI

`scripts/sculkctl.py ui` opens `http://127.0.0.1:PORT/#token=...`. The
page moves the token from the fragment, which is never sent to the server,
into local storage (shared by every tab, replaced when a new launch's token
is entered) and removes it from the address bar. The pages have real paths,
`/events`, `/scripts` and `/scripts/<name>`, which the server answers
with `index.html` and the page routes with the History API, so back, forward,
reload and open-in-new-tab all work. Without a token it
asks for one. It shows the live event table (filter, presets, pause,
double-click a cell to narrow the filter), an inspector, and a REPL with
history. The inspector leads with what matters for the event's kind: for a
packet, a summary line, the decoded payload with its round trip, the whole
frame as a hex dump tinted by `frame.spans`, then the layers as a table
(each label's tooltip is its filter path) and the raw event. Right-click any
field, in the table or the inspector (including a payload tree's leaves),
to filter on it, narrow, exclude or widen the filter, or copy it; a packet
also offers "show in context" (clears the filter and scrolls to it), and
replaying it or dropping packets like it, which put a script in the REPL to
check and run rather than acting directly. Static files are the only thing
served without the token. They are sent with a strict CSP (`script-src
'self'`, no inline code, `frame-ancestors 'none'`), and the Host and Origin
checks still apply.

The UI lives in `web/`: TypeScript with no npm dependencies, so there is no
lock file. `nix build .#web` (`web/default.nix`) type-checks it with `tsc`,
bundles it with `esbuild`, and subsets the Monocraft font (OFL-1.1, from
nixpkgs, licence shipped alongside) to woff2. Gradle's root `webUi` task runs
that build. Each platform's `processResources` then copies the result into the
jar under `sculk/web/`, where the server reads it from the classpath.
`-Psculk.webDir=<dir>` uses a UI that is already built instead of running
Nix. Flakes only see files that git tracks, so `git add` new files under
`web/` before you build. To iterate, run `gradle processResources` against a
running dev server: dev runs read resources from the build directory, so a
browser reload is enough.

The look is a block-game GUI (bevelled panels and buttons, a pixel font,
drop-shadowed text) in the deep dark's palette: sculk teal-black, glowing
cyan, shrieker bone. The textures are not game assets. `web/src/textures.ts`
draws seeded 16×16 noise in those colours at startup (sculk, panel stone,
bone, veins), and CSS scales them up with pixelated rendering. The header
icon is a hand-drawn sculk sensor that pulses as events arrive.

Memory is bounded by `-Dsculk.controlEvents` (default 100000) and
`-Dsculk.controlFrameBytes` (default 256 MiB); the oldest events go first.
Every `sculk.control*` property can be given to dev runs as a Gradle
property (`-Psculk.control=0`).

## Testing

| Layer | Command | What it proves |
|---|---|---|
| Unit | `gradle :core:test` | Netty plumbing against a fake game: pairing, drop/replace, all four injection paths, replay, attribution, scripting; control server and MCP over real HTTP, including token, Host and Origin checks; event filters |
| Web UI | `nix build .#web` (or `npm ci`, then `esbuild` and `node --test` as in `web/default.nix`) | the events table's geometry and its per-filter data (`web/test`): scaling, anchoring as rows are dropped, stale answers after a filter change |
| Smoke | `scripts/smoke-server.sh <target>` | the mod loads, Rhino works, the connection hook fires on a real server, the control server answers `sculkctl.py` and MCP and serves the web UI from the mod |
| Integration | `scripts/integ.py <target>` | a real server and a real headless client with real mods and sculk's test mod (below) |

The integration test starts a dev server and a dev client (Xvfb plus Mesa
software GL, joining via Quick Play), both with sculk, AppleSkin, Waystones
and Balm, and sculk's own test mod, `sculktest` (`-Psculk.integ`).
`tests/integ/server.js` replays a captured AppleSkin payload as raw bytes.
`tests/integ/client.js` injects a real movement frame with X nudged by one
ulp. It then checks the two event logs:

- connections seen on both sides;
- registry lists AppleSkin and Waystones payloads, with Waystones attributed
  through Balm;
- AppleSkin payloads decoded with AppleSkin's codecs and re-encoded
  byte-for-byte, and no custom payload `mismatch`;
- every payload frame the client received was sent by the server;
- both injections arrived and were decoded by the other side.

Each game JVM runs with a capped heap (server 2G, client 3G; 3G and 4G
with `--stress`), passed as `-Psculk.jvmArgs`, which adds JVM arguments to
any dev run. The Gradle daemons that launch them get 1.5G. Unset, every JVM
may grow to a quarter of RAM, and a run has four.

Real mods send whatever they send, so the test mod covers the structures
mod payloads are built from, with values known in advance. Its payloads
(`platforms/shared/mc-<version>/testmod`; per loader, only registration
and events in `platforms/<target>/src/testmod`):

- `Sink`, clientbound, sent two seconds after a player joins: an NBT
  compound with every tag type (edge values, unicode, nested compounds,
  lists of lists, empty ones), item stacks (named, enchanted, with custom
  data; a stack of 64; the empty stack), a block position at the world
  border, a UUID, an identifier, styled text, varints at their limits,
  present and empty optionals, an enum, a byte array and an ordered map.
  The client's test mod compares it with the expected value field by
  field and logs the result.
- `Report`, serverbound: the client's verdict, with the sink echoed back,
  so the server side decodes the same structures.

`tests/integ/server.js` copies the sink's NBT with the game's API, edits
it, and injects the copy with `conn.injectPayload`; `client.js` rewrites
the report about it in flight with `e.replacePayload`. `integ.py` checks:

- the sink's tree in the event log, and as a script sees it, equals the
  expected tree in `integ.py` (`EXPECTED_SINK`) on every platform, item
  stacks apart (era-specific);
- every test payload re-encodes byte for byte;
- the edited sink reached the client's mod with the edits and nothing
  else changed; the replaced report reached the server's mod;
- the server decoded both reports and their echoed sinks.

The integration test runs the dev environment (Mojang names). It does not yet
cover the reobfuscated Forge jar.

Rhino note for script authors: a `const` inside a loop body is bound once per
function, not once per iteration. Use `let` in loops.

## Build layout

```
flake.nix                 dev shell: Gradle 9 on JDK 25, JDK 17/21/25 for toolchains, client native libs;
                          packages.web: the web UI
settings.gradle           lists core + one subproject per target
build.gradle              declares loader plugins once (apply false); webUi task (nix build .#web)
gradle.properties         shared versions; toolchains come only from JAVA*_HOME
core/                     shared engine (Java 17, Netty + SLF4J compileOnly)
libs/rhino/               Rhino relocated to sculk.shaded.org.mozilla (Shadow)
libs/aircompressor/       aircompressor's pure-Java zstd relocated to sculk.shaded.io.airlift.compress
web/                      control server web UI: TypeScript (src/), static files, default.nix
gradle/platform-common.gradle
                          applied by every platform: compiles core in, embeds relocated libs and the web UI
platforms/
  forge-1.18.2/           MDG Legacy, as 1.20.1; joins with --server/--port (no Quick Play before 1.20)
  forge-1.19.2/           MDG Legacy, as 1.20.1; joins with --server/--port
  forge-1.20.1/           MDG Legacy, SRG reobf + mixin refmap, Java 17
  neoforge-1.21.1/        MDG, Java 21
  fabric-1.21.1/          Loom (remapping: Mojang names in source, intermediary jar), Java 21
  fabric-26.3/            Loom (non-remapping), Java 25
  neoforge-26.3/          MDG, Java 25
  <target>/src/testmod/   the integration test mod's loader glue (-Psculk.integ)
  shared/mc-<version>/    game code common to every loader of a version (shared_mc in a
                          target's gradle.properties): main/ is compiled into sculk,
                          testmod/ into the test mod
scripts/
  smoke-server.sh         boot a dev server, connect once, check that sculk hooked it
  sculkctl.py             command-line client for the control server
  integ.py                server + headless client with real mods, assert over event logs
tests/integ/              scripts the integration test loads into each side
```

Decisions:

- **Core sources are compiled into each platform jar** (`srcDir`, via
  `gradle/platform-common.gradle`). They are not shipped as a nested jar.
  That avoids per-loader jar-in-jar and dev-run classpath quirks. `core` is still its own project, so the
  Java 17 / no-Minecraft rules are enforced and unit tests run without a game.
- **Rhino is relocated and embedded** (`libs/rhino`, to
  `sculk.shaded.org.mozilla`). Forge/NeoForge JarJar and Fabric jar-in-jar
  each choose one version of a library across all mods, so a bundled
  `org.mozilla:rhino` could be swapped for another mod's copy. Relocation is
  the ecosystem norm (KubeJS ships its fork as `dev.latvian.mods.rhino`). The
  relocated classes go into each mod's own output, not a nested jar, so dev
  runs and shipped jars load the same thing. `ScriptHost.selfTest()` runs at
  startup and checks the two things relocation can break: the
  ServiceLoader-provided RegExp engine and the message resource bundles.
  Rhino is MPL-2.0. The shaded jar's manifest records where it came from.
  The capture's zstd (`libs/aircompressor`, Apache-2.0) is relocated and
  embedded the same way: aircompressor 2.x, pure Java on Java 8+ through
  `sun.misc.Unsafe` (3.x needs Java 22, past Minecraft 1.21.1's 21), only
  its zstd package. Java 24+ prints a one-time warning about Unsafe.
- **Shared source trees per Minecraft version, no Stonecutter (yet).** Two
  loaders of one version share their game code by compiling the same
  directory (`platforms/shared/mc-<version>`); what differs between
  versions stays in separate copies. Mixins stay per target (their
  package is the target's). Add Stonecutter-style preprocessing if the
  copies between versions grow past small adapters.
- **UI out of process.** In-game GUI APIs change between versions, and a
  Burp-style UI wants a real window, so the UI is a web page served by the
  control server. The control server binds to localhost
  and requires a token, because a reachable packet injector is a
  vulnerability in its own right.
- **No `nix build` of the mods.** Loom and MDG download and decompile
  Minecraft during the build. Packaging that as fixed-output derivations is
  possible but brittle. The flake pins the toolchain and Gradle pins the rest.

### Adding a target

1. Copy the closest `platforms/<loader>-<version>/` and adjust its
   `gradle.properties` and plugin. Keep the
   `apply from: rootProject.file('gradle/platform-common.gradle')` line.
   Set `shared_mc`: reuse `platforms/shared/mc-<version>` if another
   loader already targets that version, else copy the closest one.
2. Add it to `settings.gradle`.
3. Make the mixins, `Platform` implementation and test mod glue compile
   against that version.
4. `scripts/smoke-server.sh <name>` and `scripts/integ.py <name>` must pass.

## Roadmap

Done:

- Codec hooks: capture every frame with its packet, both directions, both
  sides; drop, replace, and replace-frame verdicts.
- Injection: frames and packets, inbound and outbound; replay.
- Payload registry and attribution (owner, registeredVia), on all
  targets.
- Decoding mod payloads with the mods' own codecs, including Forge
  SimpleChannel; round-trip checks.
- Scripting API and JSON Lines event log.
- Control server: query, filter and tail events, whole frames, eval in the
  live script scope, script reload; MCP server; `sculkctl.py`; web UI
  (event table, frame hex dump, REPL).
- Unit, smoke and real-mod integration tests for all targets, plus a test
  mod whose NBT, item stack and other payloads must decode to known trees
  and survive script edits in both directions.
- Targets: Forge 1.18.2, 1.19.2 and 1.20.1, NeoForge 1.21.1 and 26.3,
  Fabric 1.21.1 and 26.3.
- Game types (NBT, item stacks, identifiers, ...) shown as readable trees;
  `injectPayload` / `replacePayload` for edited payload objects.

Next:

1. **Dissector:** recording `ByteBuf`, field tree with byte spans.
2. **Vanilla packets caused by mods:** stack attribution at `Connection.send`.
3. **Login phase:** Forge `fml:loginwrapper`, custom queries; NeoForge split
   payloads; in-memory (singleplayer) connections.
4. **Actions** (legit and raw, client and server).
5. **UI:** injection, editing and replay from the web UI (needs endpoints for
   them).
6. **Production jar tests**, especially the reobfuscated Forge jar.
7. **More targets:** Fabric 1.20.1 (raw-buffer receivers: needs the
   dissector, as nothing decodes those payloads but the receiver itself).
