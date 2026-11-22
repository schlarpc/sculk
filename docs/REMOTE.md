# Remote access

How a browser anywhere reaches a running game's control server, with no
port forwarding and nothing in between that can read or forge traffic. The
design and its reasons are in [issue #3](https://github.com/schlarpc/sculk/issues/3);
this page is the wire protocol, which two codebases implement:

- the game: `core/src/main/java/sculk/core/remote/`;
- the shell, the page at <https://sculk-ui.github.io/>:
  [sculk-ui/sculk-ui.github.io](https://github.com/sculk-ui/sculk-ui.github.io).

Status: the Noise handshake and BIP-340 signing are built on both sides;
signaling over Nostr is next, then WebRTC.

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

Both sides take relays from the same list ([RELAYS](#relays), below), ordered for each server by
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

Every message is a Nostr event (NIP-01):

| Field | Value |
|---|---|
| `kind` | `20000 + (first two bytes of the topic, big-endian) mod 10000`: ephemeral, so relays forward it and do not store it |
| `tags` | `[["x", <topic>]]` |
| `created_at` | now, in seconds |
| `content` | the envelope, in standard base64 with padding |
| `pubkey`, `sig` | a throwaway secp256k1 key, one per session per side; BIP-340 |

The game subscribes to `{"kinds": [k], "#x": [<game topic>], "since": now - 60}`,
the browser likewise to its own topic. The 60 seconds allow for clocks that
disagree.

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
again every 3 seconds until the peer's next message arrives. The game keeps
publishing `approved` until the WebRTC connection is up or 30 seconds pass.
A pending session expires after 60 seconds without approval.

## Approval code

Both sides show `SHA-256("sculk/v1/code" || handshake hash)`, its first four
bytes as a big-endian integer modulo 1,000,000, as six digits. It comes
from the session itself, so someone else holding the link cannot start a
session that shows the same code.

## RELAYS

Trystero's curated default list (from its `packages/nostr`, MIT, November
2026): 30 relays checked for months of uptime, no auth, payment or proof of
work, and working ephemeral round trips. It lives in code on both sides,
`Nostr.RELAYS` in the game and `RELAYS` in the shell's `src/nostr.ts`; keep
the two equal, and when updating, take Trystero's list again rather than
editing by hand.
