# bench/webrtc: str0m in Chicory

Issue #3 plans to run [str0m](https://github.com/algesten/str0m) (Rust,
sans-IO WebRTC) in the mod as WebAssembly, under
[Chicory](https://chicory.dev) (`libs/chicory`). The risk was speed: DTLS
crypto in software, inside wasm, inside the JVM. The goal is a sustained
1 MB/s (the live event stream).

**Result: about 6.5 MB/s per side, with stock JVM flags, on Java 17, 21 and
25.** That is enough, with margin. Two conditions: the wasm must be compiled
to JVM bytecode at build time (the interpreter does 0.12 MB/s), and the Rust
must be built with `opt-level = "s"` (see below).

## What runs

`rust/sculk-webrtc-bench` makes two str0m peers in one process, joined by an
in-memory link with no loss and no delay. They negotiate over SDP as a
browser would, finish ICE, DTLS and SCTP, and the left peer then sends
16 MB on a reliable, ordered data channel in 16 KiB messages. Time on the
link is virtual. The CPU time spent inside each peer is measured separately:
the mod is one side, the browser the other.

Crypto is `rust/sculk-webrtc-crypto`: str0m-rust-crypto (dimpl for DTLS, RustCrypto
for the rest) without dimpl's `rcgen` feature, which pulls in aws-lc-rs (C)
and so does not build for wasm. It makes the self-signed certificate itself.

`bench/webrtc` loads the wasm (`nix build .#webrtc-bench`, built by breakbulk for
`wasm32-wasip1`) into the relocated Chicory runtime with WASI.

```sh
gradle :bench:webrtc:run --args='--mode aot --mb 16'           # compiled, Java 21
gradle :bench:webrtc:run --args='--mode interp --mb 1'         # interpreter
gradle :bench:webrtc:run -Pbench.java=17 --args='--mode aot'   # another JVM
nix build .#webrtc-bench-native && result/bin/rtc-bench        # native baseline
```

`--mode aot` uses classes that the `aotCompile` task makes at build time
with Chicory's compiler, then relocates to the shaded runtime's package. The
mod would do the same, so neither ASM nor class generation ships in it.

## Results

16 MB, 16 KiB messages, `opt-level = "s"`, third run (warm JIT). One
x86_64 Linux machine.

| Runner | Sender MB/s | Receiver MB/s |
|---|---:|---:|
| native | 82 | 208 |
| wasmtime 50 | 37 | 62 |
| Chicory, build-time compiled, Java 21 | 6.7 | 7.6 |
| Chicory, build-time compiled, Java 17 | 6.5 | 7.8 |
| Chicory, build-time compiled, Java 25 | 6.6 | 7.6 |
| Chicory, build-time compiled, `opt-level = 3` | 1.8 | 1.9 |
| Chicory, build-time compiled, `opt-level = "z"` | 5.1 | 6.1 |
| Chicory interpreter (1 MB) | 0.12 | 0.24 |

The first run in a JVM is about 10% slower (5.8 MB/s). Per side, once warm:
the handshake costs about 15 ms of CPU, and making the peer (a fresh
certificate, and P-256's tables the first time) 150 to 350 ms.

## Why `opt-level = "s"`

HotSpot does not JIT-compile a method of more than 8000 bytes of bytecode
(`-XX:+DontCompileHugeMethods`, the default); it runs it in its own
bytecode interpreter. Chicory makes one JVM method per wasm function, and at
`opt-level = 3` LLVM inlines the AES-CTR keystream loop, str0m's
`do_poll_output` and others into functions far past that size. At
`opt-level = 3`, 57 functions were over the limit and AES-CTR alone took
70% of the time. `-XX:-DontCompileHugeMethods` brought it to 7.7 MB/s, but
a mod cannot set JVM flags. `opt-level = "s"` inlines less: 28 functions are
still over the limit, but the hot crypto is not.

## AES-GCM from the JDK

`--aes host` seals and opens DTLS records with `javax.crypto` through host
imports (`rust/sculk-webrtc-crypto/src/host_aes.rs`, `SculkHost.java`); HotSpot runs
it with AES-NI and CLMUL. `--aes mixed` (host on one side, RustCrypto on
the other) checks that the two interoperate.

Costs are now thread CPU time per MB, which a busy or low-priority machine
does not change much, though it still inflates them (SMT siblings, starved
JIT threads: native read about 40% higher than on an idle machine). So
compare cases within one session; `--vs` alternates two cases in one JVM.

| 8 MB, alternating in one JVM, loaded machine | Sender ms/MB | Receiver ms/MB |
|---|---:|---:|
| `--aes rust` | 260 | 253 |
| `--aes host` | 170 | 145 |

The sender's CPU drops 35% and the receiver's 43%. At 1 MB/s, that is
roughly 17% of a core for the mod's side on this loaded machine, against
26% before.

## Where the time goes now

A JFR profile with `--aes host`: 38% of samples are in methods over
HotSpot's 8000-byte limit, which it interprets; the JDK's GCM is about 3%.

| Share | Bytecode | Function |
|---:|---:|---|
| 19% | 32 KB | str0m `Rtc::do_poll_output` |
| 11% | small | SipHash (std's `RandomState`), from sctp-proto's `HashMap`s |
| 6% | small | sctp-proto `PayloadQueue::get` |
| 5% | 19 KB | sctp-proto `Association::handle_event` |
| 3% | 21 KB | str0m `Rtc::do_handle_timeout` |
| 3% | 8 KB | str0m `Rtc::poll_timeout` |
| 3% | small | CRC-32C (sctp-proto) |
| 2% | 38 KB | str0m `Session::handle_timeout` |

`do_poll_output` is 271 lines of source. It is large because LLVM inlines
the private functions it calls, each called once, and LLVM's bonus for
inlining a function's last call outweighs any inline threshold. Lowering
the threshold does not fix it:

| Build (`--aes host`) | Huge methods | Sender ms/MB | Receiver ms/MB |
|---|---:|---:|---:|
| `opt-level = "s"`, LTO (kept) | 28 | 197–200 | 185–186 |
| `-inline-threshold=25` | 25 | 207 | 208 |
| `-inline-threshold=0` | 23 | 215 | 201 |
| `lto = false` | 20 | 222 | 185 |

(Separate JVMs, so only differences of more than a few percent count.)

## If more speed is needed

- **Keep the hot functions under 8000 bytes**: `#[inline(never)]` on the
  helpers that `do_poll_output`, `do_handle_timeout` and sctp-proto's
  `handle_event` absorb, through patched copies of those crates; or a
  Chicory option that splits a large function across JVM methods.
- **A faster hasher in sctp-proto**: its maps are keyed by small integers
  and need no SipHash; this needs a patch to sctp-proto too.

## Cost in the mod jar

The relocated Chicory runtime and WASI are 423 KiB. The compiled
sculk-webrtc-bench is 997 KiB of classes (880 KiB zipped), and the wasm module is
2.1 MB (Chicory still parses it for its types, data and tables). A real
module without the benchmark's second peer would be about the same size.
