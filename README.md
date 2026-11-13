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

Scaffold. Every target builds, loads, starts the Rhino script engine, and
hooks new game connections. `scripts/smoke-server.sh` checks this on a
dedicated server. The client uses the same hook, but it has not been
exercised yet. No interception yet.

| Target | Loader | Minecraft | Java | Why |
|---|---|---|---|---|
| `platforms/forge-1.20.1` | Forge 47 | 1.20.1 | 17 | largest modpack install base |
| `platforms/neoforge-1.21.1` | NeoForge 21.1 | 1.21.1 | 21 | largest active mod ecosystem |
| `platforms/fabric-26.3` | Fabric 0.20 | 26.3 | 25 | latest release, unobfuscated |

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
scripts/sculkctl.py mcp-config      # connect an MCP client (e.g. Claude Code) to the live game
```

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
docs/         architecture and design notes
```
