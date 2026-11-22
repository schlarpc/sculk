//! What does a str0m data channel cost each side in CPU?
//!
//! Two `Rtc`s in one process, joined by an in-memory link with no loss and
//! no delay, negotiate over SDP (as a browser would), finish ICE, DTLS and
//! SCTP, then the left one sends `--mb` megabytes on a reliable, ordered
//! data channel in `--msg`-byte messages. Time on the link is virtual.
//!
//! Each peer's cost is the thread CPU time spent in its str0m calls, so a
//! busy or low-priority machine slows the run without changing the result.
//! The mod is one side of the connection and the browser the other, so the
//! two are reported separately, as milliseconds of CPU per MB sent and as
//! the share of one core a 1 MB/s stream would take.
//!
//! `--aes host` seals DTLS records with the host's AES-GCM (wasm only; see
//! rust/rtc-crypto); `--aes mixed` uses it on the sending side only, against
//! RustCrypto on the other, which checks the two agree. Built natively and for `wasm32-wasip1`; bench/rtc runs
//! the wasm under Chicory, and provides the `sculk` host module this needs.
//! Output is `key=value` lines.

use std::collections::VecDeque;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use rtc_crypto::Aes;
use str0m::channel::ChannelId;
use str0m::net::{Protocol, Receive};
use str0m::{Candidate, Event, Input, Output, Rtc, RtcError};

struct Args {
    mb: usize,
    msg: usize,
    /// The left (sending) peer's, then the right's.
    aes: (Aes, Aes),
}

fn args() -> Args {
    let mut a = Args { mb: 16, msg: 16 * 1024, aes: (Aes::Rust, Aes::Rust) };
    let mut it = std::env::args().skip(1);
    while let Some(k) = it.next() {
        let v = it.next().unwrap_or_else(|| panic!("{k} needs a value"));
        let num = || v.parse::<usize>().unwrap_or_else(|_| panic!("{k}: not a number: {v}"));
        match k.as_str() {
            "--mb" => a.mb = num(),
            "--msg" => a.msg = num(),
            "--aes" => {
                // `mixed`: host on the left, RustCrypto on the right, to show they interoperate.
                a.aes = match v.as_str() {
                    "rust" => (Aes::Rust, Aes::Rust),
                    "host" => (Aes::Host, Aes::Host),
                    "mixed" => (Aes::Host, Aes::Rust),
                    _ => panic!("--aes rust|host|mixed"),
                }
            }
            _ => panic!("unknown argument {k} (takes --mb, --msg, --aes)"),
        }
    }
    a
}

/// This thread's CPU time, in nanoseconds.
#[cfg(target_arch = "wasm32")]
fn cpu_ns() -> u64 {
    // WASI has a thread CPU-time clock, but Chicory does not implement it.
    #[link(wasm_import_module = "sculk")]
    unsafe extern "C" {
        fn thread_cpu_ns() -> i64;
    }
    // SAFETY: no arguments, returns a number.
    unsafe { thread_cpu_ns() as u64 }
}

#[cfg(not(target_arch = "wasm32"))]
fn cpu_ns() -> u64 {
    let mut ts = libc::timespec { tv_sec: 0, tv_nsec: 0 };
    // SAFETY: ts is a valid timespec to write to.
    let r = unsafe { libc::clock_gettime(libc::CLOCK_THREAD_CPUTIME_ID, &mut ts) };
    assert_eq!(r, 0, "clock_gettime");
    ts.tv_sec as u64 * 1_000_000_000 + ts.tv_nsec as u64
}

struct Packet {
    from: SocketAddr,
    to: SocketAddr,
    data: Vec<u8>,
}

struct Peer {
    rtc: Rtc,
    addr: SocketAddr,
    /// When the peer next wants a timeout.
    next: Instant,
    /// Thread CPU time spent inside this peer's str0m calls, in ns.
    cpu: u64,
    /// Datagrams addressed to this peer, not yet delivered.
    inbox: VecDeque<Packet>,
    opened: Option<ChannelId>,
    received: usize,
}

impl Peer {
    fn new(rtc: Rtc, addr: SocketAddr, now: Instant) -> Self {
        Peer { rtc, addr, next: now, cpu: 0, inbox: VecDeque::new(), opened: None, received: 0 }
    }

    /// Polls until str0m asks for a timeout; returns what it sends.
    fn drain(&mut self, out: &mut Vec<Packet>) -> Result<(), RtcError> {
        loop {
            match self.rtc.poll_output()? {
                Output::Timeout(t) => {
                    self.next = t;
                    return Ok(());
                }
                Output::Transmit(t) => out.push(Packet { from: t.source, to: t.destination, data: t.contents.into() }),
                Output::Event(Event::ChannelOpen(id, _)) => self.opened = Some(id),
                Output::Event(Event::ChannelData(d)) => self.received += d.data.len(),
                Output::Event(_) => {}
            }
        }
    }

    /// Delivers the inbox and runs timers due at `now`, timing the str0m calls.
    fn step(&mut self, now: Instant, out: &mut Vec<Packet>) -> Result<bool, RtcError> {
        let start = cpu_ns();
        let busy = !self.inbox.is_empty();
        while let Some(p) = self.inbox.pop_front() {
            let contents = p.data.as_slice().try_into()?;
            self.rtc.handle_input(Input::Receive(
                now,
                Receive { proto: Protocol::Udp, source: p.from, destination: p.to, contents },
            ))?;
            self.drain(out)?;
        }
        if self.next <= now {
            self.rtc.handle_input(Input::Timeout(now))?;
            self.drain(out)?;
        }
        self.cpu += cpu_ns() - start;
        Ok(busy)
    }
}

/// Zero delay: a round that moved packets advances the clock a little, an
/// idle one jumps to the next timer.
fn pump(l: &mut Peer, r: &mut Peer, now: &mut Instant) -> Result<(), RtcError> {
    let mut to_r = Vec::new();
    let mut to_l = Vec::new();
    let busy_l = l.step(*now, &mut to_r)?;
    let busy_r = r.step(*now, &mut to_l)?;
    let moved = !to_r.is_empty() || !to_l.is_empty();
    for p in to_r {
        debug_assert_eq!(p.to, r.addr);
        r.inbox.push_back(p);
    }
    for p in to_l {
        debug_assert_eq!(p.to, l.addr);
        l.inbox.push_back(p);
    }
    *now = if moved || busy_l || busy_r {
        *now + Duration::from_micros(50)
    } else {
        l.next.min(r.next).max(*now + Duration::from_micros(50))
    };
    Ok(())
}

fn main() -> Result<(), RtcError> {
    let args = args();
    let total = args.mb * 1024 * 1024;
    let payload = vec![0x5a_u8; args.msg];
    let mut now = Instant::now();

    let rtc = |now, aes| {
        Rtc::builder()
            .set_crypto_provider(Arc::new(rtc_crypto::provider(aes)))
            .build(now)
    };
    let t = cpu_ns();
    let l_addr: SocketAddr = (Ipv4Addr::new(10, 0, 0, 1), 1000).into();
    let r_addr: SocketAddr = (Ipv4Addr::new(10, 0, 0, 2), 2000).into();
    let mut l = Peer::new(rtc(now, args.aes.0), l_addr, now);
    let mut r = Peer::new(rtc(now, args.aes.1), r_addr, now);
    l.rtc.add_local_candidate(Candidate::host(l_addr, "udp")?);
    r.rtc.add_local_candidate(Candidate::host(r_addr, "udp")?);
    r.rtc.add_remote_candidate(Candidate::host(l_addr, "udp")?);
    l.rtc.add_remote_candidate(Candidate::host(r_addr, "udp")?);

    let mut change = l.rtc.sdp_api();
    let cid = change.add_channel("bench".into());
    let (offer, pending) = change.apply().expect("an offer");
    let answer = r.rtc.sdp_api().accept_offer(offer)?;
    l.rtc.sdp_api().accept_answer(pending, answer)?;
    println!("aes={:?}", args.aes);
    println!("setup_cpu_ms={:.1}", ms(cpu_ns() - t));

    let (l0, r0) = (l.cpu, r.cpu);
    while r.opened.is_none() || l.rtc.channel(cid).is_none() {
        pump(&mut l, &mut r, &mut now)?;
    }
    println!("handshake_left_cpu_ms={:.1}", ms(l.cpu - l0));
    println!("handshake_right_cpu_ms={:.1}", ms(r.cpu - r0));

    let wall = Instant::now();
    let virt = now;
    let (l0, r0) = (l.cpu, r.cpu);
    let mut sent = 0;
    while r.received < total {
        let start = cpu_ns();
        let mut ch = l.rtc.channel(cid).expect("the channel");
        while sent < total && ch.buffered_amount() < 1024 * 1024 {
            let n = payload.len().min(total - sent);
            if !ch.write(true, &payload[..n])? {
                break;
            }
            sent += n;
        }
        l.cpu += cpu_ns() - start;
        pump(&mut l, &mut r, &mut now)?;
    }
    let mb = total as f64 / (1024.0 * 1024.0);
    let (lc, rc) = (ms(l.cpu - l0) / mb, ms(r.cpu - r0) / mb);
    println!("transfer_mb={mb}");
    println!("message_bytes={}", args.msg);
    println!("transfer_wall_ms={:.1}", wall.elapsed().as_secs_f64() * 1000.0);
    println!("transfer_virtual_ms={:.1}", (now - virt).as_secs_f64() * 1000.0);
    // ms of CPU per MB; at 1 MB/s, ms per MB / 10 is the percentage of a core.
    println!("sender_cpu_ms_per_mb={lc:.2}");
    println!("receiver_cpu_ms_per_mb={rc:.2}");
    println!("sender_core_pct_at_1mbps={:.2}", lc / 10.0);
    println!("receiver_core_pct_at_1mbps={:.2}", rc / 10.0);
    Ok(())
}

fn ms(ns: u64) -> f64 {
    ns as f64 / 1e6
}
