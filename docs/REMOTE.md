# Remote access

How a browser anywhere reaches a running game's control server, with no
port forwarding and nothing in between that can read or forge traffic. The
design and its reasons are in [issue #3](https://github.com/schlarpc/sculk/issues/3);
this page is the wire protocol, which two codebases implement:

- the game: `core/src/main/java/sculk/core/remote/`;
- the shell, the page at <https://sculk-ui.github.io/>:
  [sculk-ui/sculk-ui.github.io](https://github.com/sculk-ui/sculk-ui.github.io).

Status: works end to end with `remote = true` in `config/sculk.toml`: the
game prints its link, a new browser waits for `/sculk remote accept`, one
accepted with `remember` comes back without asking, and the page shows the
game's own web UI. `scripts/smoke-remote.sh` tries all of it against a dev
server and a headless Chromium.

## Overview

1. The game has a persistent X25519 key pair. Its link is
   `https://sculk-ui.github.io/#<server key>`, the key in base64url without
   padding (43 characters). Browsers never send the fragment to any server.
2. Browser and game meet on public Nostr relays and run a Noise XK handshake
   in signed Nostr events. Relays see hashed topics and ciphertext.
3. After the handshake the game knows the browser's static key. A remembered
   key is approved at once; any other waits for approval in game, where both
   sides show the same short code.
4. Once approved, the game answers the browser's WebRTC offer, and the two
   connect directly. DTLS protects the data; the fingerprints it relies on
   came through Noise, so nothing in between could have swapped them.

## Noise

`Noise_XK_25519_AESGCM_SHA256`, revision 34. The browser is the initiator,
the game the responder, and the prologue is the ASCII bytes `sculk remote v1`.

```
<- s
...
-> e, es      browser: message 1
<- e, ee      game:    message 2
-> s, se      browser: message 3
```

After message 3, `Split()` gives each side a sending and a receiving
cipher; later messages are encrypted with these, with empty associated data.

## Nostr

### Relays

Both sides take relays from the same list ([RELAYS](#relays), below),
ordered for each server by
`SHA-256(topic || url)` (the topic as ASCII hex, the URL as UTF-8), lowest
first. The browser publishes and subscribes on the first 5; the game on the
first 8, so that the two still meet when their copies of the list differ a
little. A relay that answers `blocked:`, `restricted:`, `auth-required:` or
`pow:` is dropped for the session, and the next one in order taken.

### Topics and events

- The game's topic is `hex(SHA-256("sculk/v1/server" || server key))`.
- Each session's browser topic is `hex(sid)`, where `sid` is 32 random bytes
  the browser picks per attempt, so relays cannot link one browser's
  attempts together.
- Both directions use the relays of the game's topic: the browser publishes
  on the game's topic and subscribes to its own there, and the game
  publishes its replies there on the browser's topic. So each server has one
  set of relays, whatever the session.

Every message is a Nostr event (NIP-01):

| Field | Value |
|---|---|
| `kind` | `20000 + (first two bytes of the topic, big-endian) mod 10000`: ephemeral, so relays forward it and do not store it |
| `tags` | `[["x", <topic>]]` |
| `created_at` | now, in seconds |
| `content` | the envelope, in standard base64 with padding |
| `pubkey`, `sig` | a throwaway secp256k1 key, one per session per side; BIP-340 |

The game subscribes to `{"kinds": [k], "#x": [<game topic>], "since": now - 60}`,
the browser likewise to its own topic (with its topic's kind). The 60
seconds allow for clocks that disagree.

### Envelope

```
sid (32 bytes) || n (1 byte) || body
```

`sid` is the session's, in both directions; `n` counts each side's messages
from 0. Relays deliver duplicates (each event comes from up to 5 relays) and
can reorder, so the receiver keeps the next `n` it expects from the peer,
drops lower ones, and holds higher ones until the gap fills.

| From | n | Body |
|---|---:|---|
| browser | 0 | Noise message 1, empty payload |
| game | 0 | Noise message 2, empty payload |
| browser | 1 | Noise message 3; payload: `{"sdp": <offer>}` |
| game | 1, 2, ... | a transport message: `{"status": "pending"}`, then `{"status": "approved", "sdp": <answer>}`, `{"status": "denied"}` or `{"status": "expired"}` |

Payloads are UTF-8 JSON. The offer and answer are complete (all ICE
candidates gathered first), so there is no trickle.

### Retransmission

Relays do not store these events, and either side may have been
reconnecting when one went past. So each side publishes its latest message
again every 3 seconds until the peer's next message arrives. A message sent
again is the same envelope, not encrypted afresh (the receiver drops it by
its `n` before decrypting), in a new event: relays drop an event whose id
they have seen. The game keeps
publishing `approved` until the WebRTC connection is up or 30 seconds pass.
A pending session expires after 60 seconds without approval.

## Tunnel

Once connected, the browser's data channel (label `sculk`) carries HTTP to
the game's control server. Every message is

```
stream (u32, big-endian) || type (u8) || data
```

| Type | From | Data |
|---:|---|---|
| 1 request | browser | JSON: `{"method", "path", "headers"}` |
| 2 request body | browser | bytes, at most 16 KiB a message |
| 3 request end | browser | |
| 7 cancel | browser | |
| 4 response | game | JSON: `{"status", "headers"}` |
| 5 response body | game | bytes, at most 16 KiB, as the server writes them |
| 6 response end | game | |
| 8 error | game | UTF-8 text: why there is no response |

The browser opens streams, one per request, and several run at once. The
game (`Tunnel`) replays each request against its control server on
loopback with the control token, which never leaves the game; only
`Accept`, `Accept-Encoding`, `Content-Type` and `Last-Event-ID` pass in, and
`Content-Type`, `Content-Encoding` and `Cache-Control` back. The control
server must be on.

A browser that sends `Accept-Encoding: gzip` gets gzipped bodies, event
streams included (a sync flush per batch of events), and decodes them
itself, with `DecompressionStream("gzip")`: the game's UI sees plain
responses. Compressed bytes are what cross the channel, so DTLS and SCTP
handle a fifth to a tenth of them. A shell that asks for nothing gets plain
bodies.

In the browser, the game's web UI runs in the shell's `frame.html`: a frame
sandboxed without `allow-same-origin`, so with an opaque origin, away from
the shell's keys and storage, whose CSP allows only its own loader and the
`blob:` URLs it makes, and no connections. The loader fetches the UI
(`index.html`, its stylesheet, fonts and script) through the tunnel and
gives it the tunnel as `sculkEmbedding.fetch`, its only way out.

## Approval code

Both sides show `SHA-256("sculk/v1/code" || handshake hash)`, its first four
bytes as a big-endian integer modulo 1,000,000, as six digits. It comes
from the session itself, so someone else holding the link cannot start a
session that shows the same code.

## RELAYS

Trystero's curated default list: relays checked for months of uptime, no
auth, payment or proof of work, and working ephemeral round trips. The
shell's build takes it from a pinned Trystero (`relays.nix` there), compiles
it into the page, makes it the page's CSP `connect-src`, and publishes it
as <https://sculk-ui.github.io/relays.json>. A weekly workflow there takes
Trystero's latest, refusing a list that is malformed, has fewer than 20
relays, or drops more than half of those served.

The game (`RelayList`) fetches that file when remote access starts and
daily, caches the last good copy in `config/sculk/cache/relays.json`, and
falls back to the copy baked into the mod at build time (from the shell
repo, pinned in `flake.lock`). That fetch tells GitHub Pages the server's
address; it is the only thing remote access sends before a browser comes.

Since a long-running game can hold yesterday's list while the shell has
today's, the browser uses 5 relays and the game 8 (above), which still
meet when a few relays have changed.
