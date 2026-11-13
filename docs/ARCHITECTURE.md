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
  UI  ◄────►│ control server (localhost websocket + token)  │   out of process
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
  // e.side, e.dir, e.state, e.type, e.channel, e.owner, e.payload, e.frame, e.injected
  if (e.dir === 'inbound' && e.owner === 'somemod') e.drop();
});
sculk.on('connection', c => sculk.log('connected as ' + c.side));
// c.inject('outbound', bytes), c.injectPacket('inbound', obj), c.replay(event)
// sculk.emit(obj), sculk.schedule(ms, fn), sculk.payloadTypes(), sculk.tree(obj),
// sculk.with(record, 'field', value), sculk.copy(bytes), sculk.hex(bytes), sculk.unhex(str)
```

`-Dsculk.eventLog=path` writes a JSON Lines log of everything sculk sees:
`start`, `connection`, `disconnect`, `payload_type`, `packet` (with owner,
decoded payload tree, frame hex and round-trip result) and `script`.

## Testing

| Layer | Command | What it proves |
|---|---|---|
| Unit | `gradle :core:test` | Netty plumbing against a fake game: pairing, drop/replace, all four injection paths, replay, attribution, scripting |
| Smoke | `scripts/smoke-server.sh <target>` | the mod loads, Rhino works, the connection hook fires on a real server |
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

## Build layout

```
flake.nix                 dev shell: Gradle 9 on JDK 25, JDK 17/21/25 for toolchains, client native libs
settings.gradle           lists core + one subproject per target
build.gradle              declares loader plugins once (apply false)
gradle.properties         shared versions; toolchains come only from JAVA*_HOME
core/                     shared engine (Java 17, Netty + SLF4J compileOnly)
libs/rhino/               Rhino relocated to sculk.shaded.org.mozilla (Shadow)
gradle/platform-common.gradle
                          applied by every platform: compiles core in, embeds relocated libs
platforms/
  forge-1.20.1/           MDG Legacy, SRG reobf + mixin refmap, Java 17
  neoforge-1.21.1/        MDG, Java 21
  fabric-26.3/            Loom (non-remapping), Java 25
scripts/
  smoke-server.sh         boot a dev server, connect once, check that sculk hooked it
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
  Burp-style UI wants a real window. The control server binds to localhost
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

1. **Byte taps:** inbound and outbound raw frame capture, re-anchored across
   state changes. Raw injection.
2. **Object hooks:** `send` / `channelRead0`, packet type and state
   resolution (`ProtocolView`).
3. **Payload registry and attribution:** list every mod channel with its
   codec and owner, on both sides.
4. **Dissector:** recording `ByteBuf`, field tree with byte spans, re-encode.
5. **Intercept queue and rules engine**, then **scripting API** over all of
   the above.
6. **Control server and UI.**
7. **Actions** (legit and raw, client and server).
8. **More targets:** Fabric 1.21.1 / 1.20.1, NeoForge 26.x.
