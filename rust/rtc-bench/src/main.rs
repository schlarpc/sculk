//! How fast can str0m push a data channel, and what does each side cost?
//!
//! Two `Rtc`s in one process, joined by an in-memory link with no loss and
//! no delay, negotiate over SDP (as a browser would), finish ICE, DTLS and
//! SCTP, then the left one sends `--mb` megabytes on a reliable, ordered
//! data channel in `--msg`-byte messages. Time on the wire is virtual; the
//! CPU time spent inside each peer is measured with the real clock and
//! reported separately, since the mod is one side of the connection and the
//! browser the other.
//!
//! Built natively and for `wasm32-wasip1`; the JVM benchmark
//! (bench/rtc) runs the wasm build under Chicory. Output is `key=value`
//! lines, so every runner can compare them.

use std::collections::VecDeque;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::{Duration, Instant};

use str0m::channel::ChannelId;
use str0m::net::{Protocol, Receive};
use str0m::{Candidate, Event, Input, Output, Rtc, RtcError};

struct Args {
    mb: usize,
    msg: usize,
}

fn args() -> Args {
    let mut a = Args { mb: 16, msg: 16 * 1024 };
    let mut it = std::env::args().skip(1);
    while let Some(k) = it.next() {
        let v = it.next().unwrap_or_else(|| panic!("{k} needs a value"));
        let n: usize = v.parse().unwrap_or_else(|_| panic!("{k}: not a number: {v}"));
        match k.as_str() {
            "--mb" => a.mb = n,
            "--msg" => a.msg = n,
            _ => panic!("unknown argument {k} (takes --mb, --msg)"),
        }
    }
    a
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
    /// Real time spent inside this peer's str0m calls.
    cpu: Duration,
    /// Datagrams addressed to this peer, not yet delivered.
    inbox: VecDeque<Packet>,
    opened: Option<ChannelId>,
    received: usize,
}

impl Peer {
    fn new(rtc: Rtc, addr: SocketAddr, now: Instant) -> Self {
        Peer { rtc, addr, next: now, cpu: Duration::ZERO, inbox: VecDeque::new(), opened: None, received: 0 }
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
        let start = Instant::now();
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
        self.cpu += start.elapsed();
        Ok(busy)
    }
}

fn rtc(now: Instant) -> Rtc {
    Rtc::builder()
        .set_crypto_provider(Arc::new(rtc_crypto::provider()))
        .build(now)
}

fn main() -> Result<(), RtcError> {
    let args = args();
    let total = args.mb * 1024 * 1024;
    let payload = vec![0x5a_u8; args.msg];

    let wall = Instant::now();
    let mut now = Instant::now();

    let t = Instant::now();
    let l_addr: SocketAddr = (Ipv4Addr::new(10, 0, 0, 1), 1000).into();
    let r_addr: SocketAddr = (Ipv4Addr::new(10, 0, 0, 2), 2000).into();
    let mut l = Peer::new(rtc(now), l_addr, now);
    let mut r = Peer::new(rtc(now), r_addr, now);
    l.rtc.add_local_candidate(Candidate::host(l_addr, "udp")?);
    r.rtc.add_local_candidate(Candidate::host(r_addr, "udp")?);
    r.rtc.add_remote_candidate(Candidate::host(l_addr, "udp")?);
    l.rtc.add_remote_candidate(Candidate::host(r_addr, "udp")?);

    let mut change = l.rtc.sdp_api();
    let cid = change.add_channel("bench".into());
    let (offer, pending) = change.apply().expect("an offer");
    let answer = r.rtc.sdp_api().accept_offer(offer)?;
    l.rtc.sdp_api().accept_answer(pending, answer)?;
    let setup = t.elapsed();
    println!("setup_ms={:.1}", ms(setup));

    // Zero delay: a round that moved packets advances the clock a little,
    // an idle one jumps to the next timer.
    let pump = |l: &mut Peer, r: &mut Peer, now: &mut Instant| -> Result<(), RtcError> {
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
    };

    let t = Instant::now();
    let (l0, r0) = (l.cpu, r.cpu);
    while r.opened.is_none() || l.rtc.channel(cid).is_none() {
        pump(&mut l, &mut r, &mut now)?;
    }
    println!("handshake_ms={:.1}", ms(t.elapsed()));
    println!("handshake_left_cpu_ms={:.1}", ms(l.cpu - l0));
    println!("handshake_right_cpu_ms={:.1}", ms(r.cpu - r0));

    let t = Instant::now();
    let virt = now;
    let (l0, r0) = (l.cpu, r.cpu);
    let mut sent = 0;
    while r.received < total {
        let start = Instant::now();
        let mut ch = l.rtc.channel(cid).expect("the channel");
        while sent < total && ch.buffered_amount() < 1024 * 1024 {
            let n = payload.len().min(total - sent);
            if !ch.write(true, &payload[..n])? {
                break;
            }
            sent += n;
        }
        l.cpu += start.elapsed();
        pump(&mut l, &mut r, &mut now)?;
    }
    let elapsed = t.elapsed();
    let (lc, rc) = (l.cpu - l0, r.cpu - r0);
    let mb = total as f64 / (1024.0 * 1024.0);
    println!("transfer_mb={mb}");
    println!("message_bytes={}", args.msg);
    println!("transfer_ms={:.1}", ms(elapsed));
    println!("transfer_virtual_ms={:.1}", ms(now - virt));
    println!("sender_cpu_ms={:.1}", ms(lc));
    println!("receiver_cpu_ms={:.1}", ms(rc));
    println!("sender_mb_per_s={:.2}", mb / lc.as_secs_f64());
    println!("receiver_mb_per_s={:.2}", mb / rc.as_secs_f64());
    println!("total_ms={:.1}", ms(wall.elapsed()));
    Ok(())
}

fn ms(d: Duration) -> f64 {
    d.as_secs_f64() * 1000.0
}
