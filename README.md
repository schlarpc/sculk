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

Early, but working on every target below: capture, drop, replace and inject
frames and packets in both directions on both sides; attribute payloads to
the mod that owns them; decode them with the mod's own codec; script all of
it in JavaScript; and drive it live from a local control server (CLI, web UI,
MCP). Unit, smoke and real-mod integration tests cover each target. See the
roadmap in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#roadmap) for what is
next.

| Target | Loader | Minecraft | Java | Why |
|---|---|---|---|---|
| `platforms/forge-1.18.2` | Forge 40 | 1.18.2 | 17 | long-lived modpack version |
| `platforms/forge-1.19.2` | Forge 43 | 1.19.2 | 17 | long-lived modpack version |
| `platforms/forge-1.20.1` | Forge 47 | 1.20.1 | 17 | largest modpack install base |
| `platforms/neoforge-1.21.1` | NeoForge 21.1 | 1.21.1 | 21 | largest active mod ecosystem |
| `platforms/fabric-1.21.1` | Fabric 0.20 | 1.21.1 | 21 | the same mods' Fabric builds; intermediary names |
| `platforms/fabric-26.3` | Fabric 0.20 | 26.3 | 25 | latest release, unobfuscated |
| `platforms/neoforge-26.3` | NeoForge 26.3 | 26.3 | 25 | latest release on NeoForge |

## Web UI

`scripts/sculkctl.py ui`, or `/sculk ui` in game, opens a web UI onto the
running game. Agents get the same over MCP.

![The events page: filtered mod traffic, a decoded Waystones packet and its bytes, and the JavaScript console](docs/images/events.png)

**Events.** Watch every packet live or scroll back through the whole session,
with Wireshark-style filters. Mod payloads are decoded with the mod's own
codec, next to the raw bytes. Right-click to replay a packet or drop ones like
it.

**In game.** `/sculk` works from chat:

| Command | |
|---|---|
| `/sculk mark <description>` | drops a `mark` event into the stream at that moment; its reply's **[show]** opens the web UI there |
| `/sculk script list` | the installed scripts and their state |
| `/sculk script run <name>`, `stop <name>` | start or stop one (tab completes the names) |
| `/sculk script reload` | restart all scripts from disk |
| `/sculk mcp list`, `accept [n]`, `revoke <n>` | approve MCP clients that connect without the token (see below) |
| `/sculk mcp token` | print the control token in chat |
| `/sculk ui [event]` | open the web UI in your browser, signed in, at that event if given; on a dedicated server, say how to reach it |

In a game client these are client commands, so they work on any server; a
dedicated server offers them to operators.

**On a dedicated server**, the web UI listens on the server machine's
loopback only (`http://127.0.0.1:25580/`), and there is no browser there to
open. The server log says how to reach it at startup, and so does `/sculk ui`
at the server console. In short: forward the port from your computer
(`ssh -L 25580:127.0.0.1:25580 you@server`), open `http://127.0.0.1:25580/`,
and paste the token that `/sculk mcp token` prints (it is also in
`sculk-control.json` in the server directory). A player whose own game also
runs sculk reaches their client's `/sculk`, not the server's; use the server
console for the server's.

**Without SSH** (many game hosts offer none), set `controlAddress` to
`0.0.0.0` and open the control port on the host, then browse to
`http://<server address>:25580/` and paste the token. Anyone who can reach
that port and has the token can run code in your server, and the connection
is plain HTTP, token included, so put a TLS proxy in front if you can, and
fix the port (`control = "25580"`) to the one the host forwards. MCP clients
must then send the token (`Authorization: Bearer <token>`): pairing in game
is off beyond loopback.

![The scripts page: installed scripts, one open in the editor, and its live output](docs/images/scripts.png)

**Scripts.** Write, run and toggle JavaScript in the game, with completions
and live output. Scripts sit in `config/sculk/scripts/`, so a modpack can
ship them.

## Settings

Sculk's settings are in `config/sculk.toml`, the same file on every loader,
written with defaults and comments on first launch.

| Setting | Default | |
|---|---|---|
| `control` | `""` | control server port: empty for the first free one from 25580, a number for that port, `off` for none |
| `controlAddress` | `""` | where the control server listens: empty for loopback only; an address such as `0.0.0.0` lets other machines reach it, token required (no MCP pairing) and plain HTTP |
| `controlFile` | `sculk-control.json` | where the port and token are written |
| `controlToken` | `""` | a fixed token (16+ characters) instead of a random one per launch |
| `captureBytes` | 2 GiB | how much of the session to keep on disk, in a temporary directory deleted on exit; `0` for memory only |
| `captureDir` | system temp | where that directory goes |
| `controlEvents`, `controlFrameBytes` | 50000, 128 MiB | how many recent events, and frame bytes, stay in memory |
| `scriptDir` | `config/sculk/scripts` | installed scripts |
| `watch` | `true` | reload scripts when their files change |
| `eventLog` | `""` | also append every event to this JSON Lines file (grows without limit) |

A JVM option `-Dsculk.<setting>=<value>` overrides the file.

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
scripts/integ.py fabric-1.21.1         # real server + headless client + real mods + sculk's test mod

# control server (on by default, port 25580): query and tail events, run code in the live game
gradle :platforms:neoforge-1.21.1:runServer
scripts/sculkctl.py tail 'kind == packet && owner != minecraft'
scripts/sculkctl.py repl
scripts/sculkctl.py ui              # the web UI, signed in
scripts/sculkctl.py mcp-config      # connect an MCP client (e.g. Claude Code) to the live game
```

### Connecting an MCP client

The MCP endpoint is `http://127.0.0.1:25580/mcp`. The port stays 25580
unless another game already holds it. Add the server once:

```
claude mcp add --transport http sculk http://127.0.0.1:25580/mcp
```

or put it in `.mcp.json` (or another client's configuration):

```json
{
  "mcpServers": {
    "sculk": { "type": "http", "url": "http://127.0.0.1:25580/mcp" }
  }
}
```

`scripts/sculkctl.py mcp-config` prints the right configuration for the
running game.

Clients pair instead of holding the token. When the agent first calls a
tool, it asks you to type `/sculk mcp accept <n>` in game (sculk also says so
in your chat, with an **[accept]** button). That first call waits for you,
up to five minutes, and runs as soon as you accept, so the agent does not
have to try again. Approval lasts until the game
exits. After a restart the client reconnects and asks again, with no change
to its configuration.

To skip pairing, set `controlToken` (16+ characters) in sculk's config and
send that token in an `Authorization: Bearer <token>` header;
`scripts/sculkctl.py mcp-config --token` prints that configuration. Anyone
who can read the configuration can then drive the game.

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
platforms/    one thin adapter per (loader, Minecraft version); shared/ holds game code
              common to every loader of one version, and the integration test mod
scripts/      dev tooling
web/          control server web UI (TypeScript, built by Nix)
docs/         architecture and design notes
```
