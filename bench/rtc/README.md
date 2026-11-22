# bench/rtc: str0m in Chicory

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

`rust/rtc-bench` makes two str0m peers in one process, joined by an
in-memory link with no loss and no delay. They negotiate over SDP as a
browser would, finish ICE, DTLS and SCTP, and the left peer then sends
16 MB on a reliable, ordered data channel in 16 KiB messages. Time on the
link is virtual. The CPU time spent inside each peer is measured separately:
the mod is one side, the browser the other.

Crypto is `rust/rtc-crypto`: str0m-rust-crypto (dimpl for DTLS, RustCrypto
for the rest) without dimpl's `rcgen` feature, which pulls in aws-lc-rs (C)
and so does not build for wasm. It makes the self-signed certificate itself.

`bench/rtc` loads the wasm (`nix build .#rtc-bench`, built by breakbulk for
`wasm32-wasip1`) into the relocated Chicory runtime with WASI.

```sh
gradle :bench:rtc:run --args='--mode aot --mb 16'           # compiled, Java 21
gradle :bench:rtc:run --args='--mode interp --mb 1'         # interpreter
gradle :bench:rtc:run -Pbench.java=17 --args='--mode aot'   # another JVM
nix build .#rtc-bench-native && result/bin/rtc-bench        # native baseline
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

## Where the time goes now

A JFR profile of the `"s"` build, by crate: AES and AES-CTR 28%, str0m 27%,
sctp-proto 20%, GHASH 5%, dimpl 4%, CRC-32C 3%.

The hottest single function (15%) is str0m's `Rtc::do_poll_output`, still
32 KB of bytecode, so it runs in HotSpot's interpreter. Others over the
limit include `Rtc::do_handle_timeout`, `Session::handle_timeout` and
sctp-proto's `Association::handle_event`.

## If more speed is needed

Neither of these is needed for 1 MB/s.

- **AES-GCM from the JDK, through host imports**: the crypto provider
  (`rust/rtc-crypto`) calls a host function, and the JDK's AES-GCM uses
  AES-NI and CLMUL. This removes about a third of the time.
- **Smaller functions**: a lower LLVM inline threshold for the str0m and
  sctp-proto crates, or a Chicory option that splits huge functions. This
  would let HotSpot compile the rest of the hot path.

## Cost in the mod jar

The relocated Chicory runtime and WASI are 423 KiB. The compiled
rtc-bench is 997 KiB of classes (880 KiB zipped), and the wasm module is
2.1 MB (Chicory still parses it for its types, data and tables). A real
module without the benchmark's second peer would be about the same size.
