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

1. **Netty is never obfuscated**, and Minecraft's pipeline handler names have
   stayed stable for years. The byte layer (frame taps and raw injection) is
   written against Netty only and lives in core.
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
`encrypt`/`decrypt` (online mode) are added later. In era C the outbound
codec is a placeholder (`outbound_config`) until the protocol state is
known. It is then **replaced** at every state change (configuration ↔ play).
Taps must anchor to handlers that stay put (`splitter`, `prepender`), or
re-anchor on each state change.

Planned tap points:

- **Raw inbound frame:** after `decompress` (or `splitter`), before `decoder`.
  One decrypted, decompressed packet per message.
- **Raw outbound injection:** after `encoder`, before `compress`/`prepender`.
  The game then compresses, frames and encrypts what we write.
- **Object level:** `Connection.send` and `Connection.channelRead0` (mixins).

In-memory connections (singleplayer, and the host in LAN play) have **no
codec handlers**: packets pass as objects. Use a dedicated server for
byte-level work.

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
Today it only identifies the target. The planned views are:

- **ProtocolView:** packet types per state and direction. Map ID ↔ class.
  Encode and decode a vanilla packet. Report the current state of a
  connection.
- **PayloadRegistryView:** registered mod channels: ID, direction, codec (if
  known), owning mod. On a client, also the server's advertised channels.
  This is the attack-surface listing.
- **Attribution:** channel namespace → mod. For vanilla packets a mod causes,
  walk the stack at `Connection.send` and match frames to mod jars.
- **Actions:** about 30 verbs, each in a legit and a raw flavour, for each
  side:
  - client: move, look, `useItemOn`, `attack`, `clickContainer`, chat,
    command, …
  - server: send clientbound packets to a chosen player, spoof entity and
    block updates, open screens, push custom payloads the client never asked
    for, …

Hooks so far (one mixin per platform):

| Hook | Mixin target | Core entry |
|---|---|---|
| Connection opened | `Connection.channelActive` (TAIL) | `Sculk.onChannelActive(Channel, Side)` |

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
