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
| Forge 1.20.1 | Mojang class names, SRG member names | Mixins are remapped through a refmap (handled by the build). The script name bridge needs SRG tables. |
| NeoForge 1.21.1 | Mojang | none |
| Fabric ≤ 1.21.11 | Intermediary | The script name bridge needs intermediary tables. |
| All of 26.x | Unobfuscated | none |

Compiled adapter code always uses Mojang names; the build remaps where
needed. Only reflection on names chosen at runtime (the script bridge) needs
mapping tables.

## The `Platform` seam

`core/src/main/java/sculk/core/platform/Platform.java` is the whole contract.
"Codec" below is the game's own `PacketDecoder`/`PacketEncoder` instance,
which carries the protocol state.

| Method | Era C (NeoForge 1.21.1, Fabric 26.3) | Era A (Forge 1.20.1) |
|---|---|---|
| `describe(packet, codec)` | `ProtocolInfo` from the codec; payload already decoded | protocol from the packet class; payload bytes decoded via `payloadCodec` |
| `encode` / `decode` | `ProtocolInfo.codec()` | `ConnectionProtocol` + `Packet.write` / `createPacket` (state from the channel) |
| `payloadCodec(channel)` | not needed | the SimpleChannel's own decoder, chosen by discriminator byte |
| `modOf(class)` | NeoForge: JPMS module → mod file. Fabric: mod container containing the class file | JPMS module → mod file |

Each platform also has one mixin on its loader's payload registration entry
point (`NetworkRegistry.register`, `PayloadTypeRegistryImpl.register`,
`IndexedMessageCodec.addCodecIndex`). It records the registration and the
call stack in `PayloadRegistry`.

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

Scripts are JavaScript (Rhino, ES6 mode) loaded at startup from
`-Dsculk.scripts` (dev runs: `-Psculk.scripts=...`). The `sculk` global is
`ScriptApi`:

```js
sculk.on('packet', e => {
  // the same fields as logged packet events (below): e.conn, e.frame, e.packet, e.mod, e.injected, e.action
  if (e.frame.dir === 'inbound' && e.mod && e.mod.owner === 'somemod') e.drop();
  if (e.channel === 'somemod:sync') sculk.emit(sculk.tree(e.mod.data)); // shorthands work here too
});
sculk.on('connection', c => sculk.log('connected as ' + c.side));
// c.inject('outbound', bytes), c.injectPacket('inbound', obj), c.replay(event)
// sculk.emit(obj), sculk.schedule(ms, fn), sculk.payloadTypes(), sculk.tree(obj),
// sculk.with(record, 'field', value), sculk.copy(bytes), sculk.hex(bytes), sculk.unhex(str)
```

### Events

`-Dsculk.eventLog=path` writes a JSON Lines log of everything sculk sees:
`start`, `connection`, `disconnect`, `payload_type`, `packet`, `script`,
`script_error`, `script_reload` and `eval`. Every entry has an `id` that
goes up by one per entry, and `kind` and `t` (epoch millis).

Packet events are layered, like a Wireshark dissection: each layer is an
object, and a layer is absent when it does not apply.

```
injected, action          what sculk did: action is pass, drop or replace
conn    {id, side, remote}                          the connection
frame   {seq, dir, len, hex, spans}                 the bytes on the wire
packet  {id, state, flow, type, javaClass, owner}   the game's packet
mod     {channel, owner, registeredVia, javaClass,  custom payloads only:
         roundTrip, data}                           the decoded payload
```

- `frame.spans` lists where fields sit in the frame, as
  `{field, off, len}`: `packet.id`, then `mod.channel` and `mod.data` for
  a custom payload, or `packet.body` for anything else.
- `packet.owner` is the mod whose class the packet is (usually
  `minecraft`); `mod.owner` is the mod that owns a payload's channel.
- `mod.data` is the payload as an object tree; `mod.roundTrip` is `ok`,
  `equivalent`, `mismatch: ...` or `error: ...` (see Attribution above).
- `connection` and `disconnect` events carry `conn`; `payload_type` events
  carry `mod` (with `codecClass`, `protocol`, `flow` and `discriminator`
  instead of `data` and `roundTrip`).

Scripts get the same tree: a path names the same thing in a filter, in the
log and in code. Where the log has a serialised copy, scripts get the live
thing: `e.conn` is the connection (with `inject` and `replay`),
`e.frame.bytes` the frame, `e.packet.object` the packet and `e.mod.data`
the payload object.

Shorthands, in filters and in scripts: `dir` (`frame.dir`), `len`
(`frame.len`), `side` (`conn.side`), `type` (`packet.type`), `channel`
(`mod.channel`) and `owner` (`mod.owner` if there is a mod layer, else
`packet.owner`).

## Control server

`-Dsculk.control=PORT` (dev runs: `-Psculk.control=0` for a free port)
starts one HTTP listener for tools, agents and the web UI: a filterable
live event log, a REPL into the script scope, and an MCP server. It keeps the most recent
events in memory with their whole frames (the event log cuts long frames
short), whether or not `sculk.eventLog` is set.

```
GET  /status                            platform, connections, startup scripts, last event id
GET  /events?filter=&after=&limit=      JSON Lines; the last `limit` matches, or the first after `after`
GET  /events/stream?filter=&after=      server-sent events, live (backlog first with `after`)
GET  /frame?id=                         a packet event's whole frame, as hex
POST /eval      body: JavaScript        runs in the live script scope: {"ok":true,"result":...}
POST /reload                            drops script globals, listeners and timers; loads startup scripts again
POST /mcp       body: JSON-RPC          Model Context Protocol, Streamable HTTP transport (JSON responses)
GET  /ui/...                            the web UI (no token needed for its static files)
```

`scripts/sculkctl.py` wraps it (`status`, `events`, `tail`, `frame`, `eval`,
`repl`, `reload`) and finds the server through its control file.

The MCP server offers the same powers as tools: `sculk_status`,
`sculk_events`, `sculk_wait_events` (long-polls up to 60 s for new matches,
since tool calls cannot stream), `sculk_frame`, `sculk_eval` and
`sculk_reload`. The tool descriptions carry the filter syntax, event fields
and script API. `scripts/sculkctl.py mcp-config` prints the client
configuration, including the `Authorization` header. The token changes with
every launch; `-Dsculk.controlToken=<16+ chars>` fixes it for a static
client configuration (and then anyone who can read that configuration can
drive the game).

Filters are Wireshark-style:

```
kind == packet && dir == inbound && owner != minecraft
type matches "recipe|chat" and len > 1000
mod.data contains "../" or data.step == done
mod.data.pos.x >= 100 && mod.registeredVia == balm
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

- it is off by default, and listens on loopback only: 127.0.0.1 and `::1`
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

`scripts/sculkctl.py ui` opens `http://127.0.0.1:PORT/ui/#token=...`. The
page moves the token from the fragment, which is never sent to the server,
into session storage and removes it from the address bar. Without a token it
asks for one. It shows the live event table (filter, presets, pause,
double-click a cell to narrow the filter), an event's JSON tree and its whole
frame as a hex dump, and a REPL with history. Static files are the only thing
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
| Smoke | `scripts/smoke-server.sh <target>` | the mod loads, Rhino works, the connection hook fires on a real server, the control server answers `sculkctl.py` and MCP and serves the web UI from the mod |
| Integration | `scripts/integ.py <target>` | a real server and a real headless client with real mods (below) |

The integration test starts a dev server and a dev client (Xvfb plus Mesa
software GL, joining via Quick Play), both with sculk, AppleSkin, Waystones
and Balm (`-Psculk.integ`). `tests/integ/server.js` replays a captured
AppleSkin payload as raw bytes. `tests/integ/client.js` injects a real
movement frame with X nudged by one ulp. It then checks the two event logs:

- connections seen on both sides;
- registry lists AppleSkin and Waystones payloads, with Waystones attributed
  through Balm;
- AppleSkin payloads decoded with AppleSkin's codecs and re-encoded
  byte-for-byte, and no custom payload `mismatch`;
- every payload frame the client received was sent by the server;
- both injections arrived and were decoded by the other side.

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
web/                      control server web UI: TypeScript (src/), static files, default.nix
gradle/platform-common.gradle
                          applied by every platform: compiles core in, embeds relocated libs and the web UI
platforms/
  forge-1.20.1/           MDG Legacy, SRG reobf + mixin refmap, Java 17
  neoforge-1.21.1/        MDG, Java 21
  fabric-26.3/            Loom (non-remapping), Java 25
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
- **No Stonecutter (yet).** Adapters are separate source trees for now. Add
  Stonecutter-style preprocessing once two targets of the same era share
  enough adapter code to be worth it (for example, NeoForge 1.21.1 and 26.x).
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
2. Add it to `settings.gradle`.
3. Make the mixins and `Platform` implementation compile against that version.
4. `scripts/smoke-server.sh <name>` must pass.

## Roadmap

Done:

- Codec hooks: capture every frame with its packet, both directions, both
  sides; drop, replace, and replace-frame verdicts.
- Injection: frames and packets, inbound and outbound; replay.
- Payload registry and attribution (owner, registeredVia), on all three
  targets.
- Decoding mod payloads with the mods' own codecs, including Forge
  SimpleChannel; round-trip checks.
- Scripting API and JSON Lines event log.
- Control server: query, filter and tail events, whole frames, eval in the
  live script scope, script reload; MCP server; `sculkctl.py`; web UI
  (event table, frame hex dump, REPL).
- Unit, smoke and real-mod integration tests for all three targets.

Next:

1. **Dissector:** recording `ByteBuf`, field tree with byte spans.
2. **Vanilla packets caused by mods:** stack attribution at `Connection.send`.
3. **Login phase:** Forge `fml:loginwrapper`, custom queries; NeoForge split
   payloads; in-memory (singleplayer) connections.
4. **Actions** (legit and raw, client and server).
5. **UI:** injection, editing and replay from the web UI (needs endpoints for
   them).
6. **Production jar tests**, especially the reobfuscated Forge jar.
7. **More targets:** Fabric 1.21.1 / 1.20.1, NeoForge 26.x.
