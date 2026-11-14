# sculk

A scriptable intercepting proxy for modded Minecraft, running inside the game.
It is for finding and reproducing protocol-level security bugs in mods.

Sculk captures, decodes, edits and injects game traffic, vanilla and modded,
and says which mod each packet belongs to. It decodes mod payloads with each
mod's own codec, and everything is scriptable in JavaScript. It runs on the
**client** (malicious client against server-side mods) and on the **server**
(malicious server against client-side mods).

Only use it against clients and servers you own or are authorised to test.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for goals, design and roadmap.

## Status

Early, but working on all three targets: capture, drop, replace and inject
frames and packets in both directions on both sides; attribute payloads to
the mod that owns them; decode them with the mod's own codec; script all of
it in JavaScript; and drive it live from a local control server (CLI, web UI,
MCP). Unit, smoke and real-mod integration tests cover each target. See the
roadmap in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#roadmap) for what is
next.

| Target | Loader | Minecraft | Java | Why |
|---|---|---|---|---|
| `platforms/forge-1.20.1` | Forge 47 | 1.20.1 | 17 | largest modpack install base |
| `platforms/neoforge-1.21.1` | NeoForge 21.1 | 1.21.1 | 21 | largest active mod ecosystem |
| `platforms/fabric-26.3` | Fabric 0.20 | 26.3 | 25 | latest release, unobfuscated |

## Web UI

The control server (`-Dsculk.control=PORT`) serves a web UI on localhost;
`scripts/sculkctl.py ui` opens it, signed in. The same powers are there for
agents over MCP.

![The events page: a Wireshark-style filter over mod payloads, a decoded Waystones teleport packet in the inspector with its frame bytes, and the JavaScript console](docs/images/events.png)

**Events.** Every event of the session is captured (to a temporary
directory, 2 GiB by default) and the table scrolls through all of it, live.
Filters are Wireshark-style (`kind == packet && owner != minecraft`,
`mod.data contains "../"`); double-click or right-click a cell to narrow on
it. The inspector shows a payload decoded by its mod's own codec, the frame
with each field's bytes marked, and whether re-encoding gives the same bytes.
Right-click to replay a packet or drop ones like it. The console runs
JavaScript in the game.

![The scripts page: installed scripts with their state and enable switches, a script open in the editor with API completions, and its live output](docs/images/scripts.png)

**Scripts.** Scripts live in the game's `config/sculk/scripts/`, so a
modpack can ship them. Each runs in its own scope and owns its listeners and
timers, so it starts, stops and restarts alone; a `// ==Sculk==` header
names it and says whether it runs at startup or only when asked. The editor
checks syntax with the game's own JavaScript engine and completes the
`sculk` API; below it, the script's log, emits and errors stream live.

(These screenshots are of `web/tools/mock-server.py`, which plays a made-up
modded game for working on the UI.)

## Development

Everything you need (Gradle, JDK 17/21/25, client native libraries) comes from
the Nix flake:

```sh
nix develop            # or: direnv allow
gradle build           # all targets; jars in platforms/*/build/libs/
gradle :core:test      # engine unit tests, no Minecraft needed

gradle :platforms:neoforge-1.21.1:runClient
gradle :platforms:neoforge-1.21.1:runServer

scripts/smoke-server.sh forge-1.20.1   # boot a server, connect, check the hook fired

# control server: query and tail events, run code in the live game
gradle :platforms:neoforge-1.21.1:runServer -Psculk.control=0
scripts/sculkctl.py tail 'kind == packet && owner != minecraft'
scripts/sculkctl.py repl
scripts/sculkctl.py ui              # the web UI, signed in
scripts/sculkctl.py mcp-config      # connect an MCP client (e.g. Claude Code) to the live game
```

`gradle build` also runs `nix build .#web` for the control server's web UI
(`web/`); pass `-Psculk.webDir=<dir>` to use a prebuilt one instead.

To work on the web UI without Minecraft, run `web/tools/mock-server.py`: it
serves the UI from source (rebuilt on reload) against a made-up game.

The first build of each target downloads and decompiles Minecraft and takes a
few minutes.

Without Nix: install Gradle 9.8+ and JDKs 17, 21 and 25, and export
`JAVA17_HOME`, `JAVA21_HOME` and `JAVA25_HOME`. The build never auto-downloads
toolchains.

## Layout

```
core/         shared engine, no Minecraft dependencies (~90% of the code, eventually)
libs/         third-party libraries, relocated so they cannot clash with other mods
platforms/    one thin adapter per (loader, Minecraft version)
scripts/      dev tooling
web/          control server web UI (TypeScript, built by Nix)
docs/         architecture and design notes
```
