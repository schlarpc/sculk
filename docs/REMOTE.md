# Remote access

How a browser anywhere reaches a running game's control server, with no
port forwarding and nothing in between that can read or forge traffic. The
design and its reasons are in [issue #3](https://github.com/schlarpc/sculk/issues/3);
this page is the wire protocol, which two codebases implement:

- the game: `core/src/main/java/sculk/core/remote/`;
- the shell, the page at <https://sculk-ui.github.io/>:
  [sculk-ui/sculk-ui.github.io](https://github.com/sculk-ui/sculk-ui.github.io).

Status: works end to end with `remote = true` in `config/sculk.toml`: the
game prints its link, a new browser shows a code and waits for
`/sculk remote accept <code>`, one accepted with `remember` comes back
without asking, and the page shows the
game's own web UI. The shell keeps each game it reaches (its key, named
after `/api/status`'s platform until the user renames it) in IndexedDB, and
lists them when opened without a link; a game that does not answer in 30
seconds is said to be down or rekeyed, with a way to forget it.
`scripts/smoke-remote.sh` tries all of it against a dev server and a
headless Chromium.

## Overview

1. The game has a persistent X25519 key pair. Its link is
   `https://sculk-ui.github.io/#<server key>`, the key in base64url without
   padding (43 characters). Browsers never send the fragment to any server.
2. Browser and game meet on public Nostr relays and run a Noise XK handshake
   in signed Nostr events. Relays see hashed topics and ciphertext.
3. After the handshake the game knows the browser's static key. A remembered
   key is approved at once; any other waits for approval in game: its page
   shows a short code, and the player types it.
4. Once approved, the game answers the browser's WebRTC offer, and the two
   connect directly. DTLS protects the data; the fingerprints it relies on
   came through Noise, so nothing in between could have swapped them.

## Noise

`Noise_XK_25519_AESGCM_SHA256`, revision 34. The browser is the initiator,
the game the responder, and the prologue is the ASCII bytes `sculk remote v1`
followed by the session's `sid` (below): a first message someone recorded
opens no session under another `sid`.

```
<- s
...
-> e, es      browser: message 1
<- e, ee      game:    message 2
-> s, se      browser: message 3
```

After message 3, `Split()` gives each side a sending and a receiving
cipher; later messages are encrypted with these, with empty associated data.
Its HKDF is asked for a third output, the session's `secret`, which the
[approval code](#approval-code) comes from.

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

`sid` and `n` are in the clear and nothing signs them, so anyone watching a
relay can send a message that claims to be a session's next. One that does
not authenticate is dropped and changes nothing: the Noise state is left as
it was, and the real message, sent again, is still read.

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
`blob:` URLs it makes, and no fetches or loads from anywhere. (A peer
connection is not a fetch, and no CSP that browsers honour stops one: a
game's UI could still send its own game's data out that way, and nothing
else is within its reach.) The loader does nothing unless its
origin is opaque, so another site that frames `frame.html` without the
sandbox gets nothing to run with the shell's origin. The loader fetches the UI
(`index.html`, its stylesheet, fonts and script) through the tunnel and
gives it the tunnel as `sculkEmbedding.fetch`, its only way out.

## Approval code

The browser's page shows `SHA-256("sculk/v1/code" || secret)`, its first
four bytes as a big-endian integer modulo 1,000,000, as six digits, and the
player types it: `/sculk remote accept <code>`, with `remember` to let the
browser back in next time. The game computes the same code for each waiting
browser, shows it nowhere, and lets in the one whose code it is given.

`secret` is the third output of `Split()`'s HKDF, which only the two ends of
a session have. (The handshake hash would not do: it is a hash of the
server's key and of what the relays carried, so anyone holding the link can
compute a session's, and steer their own by varying their last message.)
So someone else holding the link cannot learn the code on the player's
page, and typing it cannot let in a browser the player is not looking at.
They can still guess: each of their waiting sessions (at most 8) is one
chance in a million, and two waiting browsers with one code are both
refused.

## What approval grants

An approved browser has the control server, as the control token would give
it: the tunnel passes every path. That includes scripts, which run with the
game's own rights, so **accepting a browser lets it run code on the game's
computer**; the prompt says so. The header list and the token staying in
the game keep the tunnel's requests well formed, and are not a limit on
what an approved browser can do.

`/sculk remote revoke <number>` forgets a browser, closes its connections,
and refuses any it was still making. It cannot undo what the browser did
while it was in: after revoking one that was not to be trusted, delete
`sculk/remote/server.key` in the game directory (the game makes a new key and link), look
through `browsers.json` there and the scripts directory, and restart.

On a dedicated server the `/sculk` commands need permission level 4 or the
console, since they hand out this access.

Two more things are trusted, and worth knowing:

- **The page.** The browser's key lives in the page at `sculk-ui.github.io`,
  so whoever can change what that origin serves can act as every browser a
  game remembers. The game cannot check the page. Its build is reproducible
  (the shell's README), and a game only prints the link: nothing stops a
  cautious operator serving the same files elsewhere.
- **`sculk/remote/` in the game directory.** `server.key` is the game's
  identity and `browsers.json` its remembered browsers; whoever is given
  both has the game's link answer to them and its browsers let in. They are
  kept out of `config/` (and moved from `config/sculk/remote/`, where they
  were at first) because modpack exports take `config/` along. Launchers
  have no way for a mod to mark files as not for export, so when exporting
  an instance, leave the `sculk` folder unticked.

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
address. Before any browser comes, remote access also connects to its
relays (which see the game's address and its topic, a hash of its key),
and asks Google's and Cloudflare's STUN servers for its public address.

Since a long-running game can hold yesterday's list while the shell has
today's, the browser uses 5 relays and the game 8 (above), which still
meet when a few relays have changed.
