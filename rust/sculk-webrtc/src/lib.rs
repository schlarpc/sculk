//! str0m behind a small sans-IO interface, for the mod to run as wasm under
//! Chicory (core's `sculk.core.remote.WebRtc` drives it).
//!
//! The JVM owns the UDP socket and the clock's timers; this module only turns
//! inputs (datagrams, timeouts, the browser's offer, data to send) into
//! outputs (datagrams to send, the next timeout, data channel events). Each
//! `Rtc` is one browser's connection, named by a handle.
//!
//! Calls pass bytes through two buffers in this module's memory: the host
//! asks for an input buffer (`input`), fills it, and calls; results go to the
//! output buffer, read with `output_ptr` and `output_len` after the call.
//!
//! Addresses are 18 bytes: the IPv6 address (IPv4 as `::ffff:a.b.c.d`), then
//! the port, big-endian. Return values: 0 or more is success; -1 is an error
//! whose message is in the output buffer.
//!
//! `poll` returns what happened, with the output buffer holding:
//!
//! | code | event | output |
//! |---:|---|---|
//! | 0 | wait | u64 LE: microseconds until `timeout` should be called |
//! | 1 | transmit | source address, destination address, datagram |
//! | 2 | channel open | u16 LE channel, label (UTF-8) |
//! | 3 | channel data | u16 LE channel, u8 binary, data |
//! | 4 | channel closed | u16 LE channel |
//! | 5 | channel buffered amount low | u16 LE channel |
//! | 6 | connected | |
//! | 7 | ICE state | u8: 0 new, 1 checking, 2 connected, 3 completed, 4 disconnected |
//! | 8 | closed: this connection is over | |

use std::cell::RefCell;
use std::net::{IpAddr, Ipv6Addr, SocketAddr};
use std::sync::Arc;
use std::time::Instant;

use str0m::change::{SdpAnswer, SdpOffer, SdpPendingOffer};
use str0m::channel::ChannelId;
use str0m::net::{Protocol, Receive};
use str0m::{Candidate, Event, IceConnectionState, Input, Output, Rtc};

const ADDR: usize = 18;

struct Conn {
    rtc: Rtc,
    /// Channels by the index the host knows them by.
    channels: Vec<ChannelId>,
    /// An offer this side made (rtc_offer), awaiting the answer.
    pending: Option<SdpPendingOffer>,
}

thread_local! {
    static CONNS: RefCell<Vec<Option<Conn>>> = const { RefCell::new(Vec::new()) };
    static INPUT: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
    static OUTPUT: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

fn set_output(bytes: &[u8]) {
    OUTPUT.with(|o| {
        let mut o = o.borrow_mut();
        o.clear();
        o.extend_from_slice(bytes);
    });
}

fn error(msg: impl std::fmt::Display) -> i32 {
    set_output(msg.to_string().as_bytes());
    -1
}

/// The input buffer, at least `len` bytes; valid until the next call.
#[unsafe(no_mangle)]
pub extern "C" fn input(len: u32) -> *mut u8 {
    INPUT.with(|i| {
        let mut i = i.borrow_mut();
        i.resize(len as usize, 0);
        i.as_mut_ptr()
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn output_ptr() -> *const u8 {
    OUTPUT.with(|o| o.borrow().as_ptr())
}

#[unsafe(no_mangle)]
pub extern "C" fn output_len() -> u32 {
    OUTPUT.with(|o| o.borrow().len() as u32)
}

fn input_bytes<R>(f: impl FnOnce(&[u8]) -> R) -> R {
    INPUT.with(|i| f(&i.borrow()))
}

fn addr(b: &[u8]) -> Option<SocketAddr> {
    if b.len() != ADDR {
        return None;
    }
    let ip = Ipv6Addr::from(<[u8; 16]>::try_from(&b[..16]).ok()?);
    let ip = match ip.to_ipv4_mapped() {
        Some(v4) => IpAddr::V4(v4),
        None => IpAddr::V6(ip),
    };
    Some(SocketAddr::new(ip, u16::from_be_bytes([b[16], b[17]])))
}

fn put_addr(out: &mut Vec<u8>, a: SocketAddr) {
    let ip = match a.ip() {
        IpAddr::V4(v4) => v4.to_ipv6_mapped(),
        IpAddr::V6(v6) => v6,
    };
    out.extend_from_slice(&ip.octets());
    out.extend_from_slice(&a.port().to_be_bytes());
}

fn with_conn(h: u32, f: impl FnOnce(&mut Conn) -> i32) -> i32 {
    CONNS.with(|c| match c.borrow_mut().get_mut(h as usize).and_then(Option::as_mut) {
        Some(conn) => f(conn),
        None => error(format!("no connection {h}")),
    })
}

/// A new connection; `host_aes` nonzero seals DTLS records with the host's AES-GCM.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_new(host_aes: u32) -> u32 {
    let aes = if host_aes != 0 { sculk_webrtc_crypto::Aes::Host } else { sculk_webrtc_crypto::Aes::Rust };
    let rtc = Rtc::builder()
        .set_crypto_provider(Arc::new(sculk_webrtc_crypto::provider(aes)))
        .build(Instant::now());
    let conn = Conn { rtc, channels: Vec::new(), pending: None };
    CONNS.with(|c| {
        let mut c = c.borrow_mut();
        match c.iter().position(Option::is_none) {
            Some(i) => {
                c[i] = Some(conn);
                i as u32
            }
            None => {
                c.push(Some(conn));
                (c.len() - 1) as u32
            }
        }
    })
}

/// Ends a connection and frees it.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_free(h: u32) {
    CONNS.with(|c| {
        if let Some(slot) = c.borrow_mut().get_mut(h as usize) {
            if let Some(conn) = slot.as_mut() {
                conn.rtc.disconnect();
            }
            *slot = None;
        }
    });
}

/// Adds a host candidate: input is one address.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_add_host(h: u32) -> i32 {
    let Some(a) = input_bytes(addr) else { return error("bad address") };
    with_conn(h, |c| match Candidate::host(a, "udp") {
        Ok(cand) => {
            c.rtc.add_local_candidate(cand);
            0
        }
        Err(e) => error(e),
    })
}

/// Adds a server-reflexive candidate: input is the public address, then the local one.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_add_srflx(h: u32) -> i32 {
    let Some((a, base)) = input_bytes(|b| {
        if b.len() != 2 * ADDR {
            return None;
        }
        Some((addr(&b[..ADDR])?, addr(&b[ADDR..])?))
    }) else {
        return error("bad addresses");
    };
    with_conn(h, |c| match Candidate::server_reflexive(a, base, "udp") {
        Ok(cand) => {
            c.rtc.add_local_candidate(cand);
            0
        }
        Err(e) => error(e),
    })
}

/// Accepts the browser's offer (input, UTF-8 SDP); the answer goes to the output.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_accept_offer(h: u32) -> i32 {
    let offer = match input_bytes(|b| String::from_utf8(b.to_vec())) {
        Ok(s) => s,
        Err(_) => return error("offer is not UTF-8"),
    };
    let offer = match SdpOffer::from_sdp_string(&offer) {
        Ok(o) => o,
        Err(e) => return error(e),
    };
    with_conn(h, |c| match c.rtc.sdp_api().accept_offer(offer) {
        Ok(answer) => {
            set_output(answer.to_sdp_string().as_bytes());
            0
        }
        Err(e) => error(e),
    })
}

/// Makes an offer with one data channel, labeled by the input (UTF-8); the
/// offer goes to the output. The browser's side, for tests and native
/// clients; the game answers offers instead.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_offer(h: u32) -> i32 {
    let label = input_bytes(|b| String::from_utf8_lossy(b).into_owned());
    with_conn(h, |c| {
        let mut change = c.rtc.sdp_api();
        change.add_channel(label);
        match change.apply() {
            Some((offer, pending)) => {
                c.pending = Some(pending);
                set_output(offer.to_sdp_string().as_bytes());
                0
            }
            None => error("nothing to offer"),
        }
    })
}

/// Accepts the answer (input, UTF-8 SDP) to this side's offer.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_accept_answer(h: u32) -> i32 {
    let answer = match input_bytes(|b| String::from_utf8(b.to_vec())) {
        Ok(s) => s,
        Err(_) => return error("answer is not UTF-8"),
    };
    let answer = match SdpAnswer::from_sdp_string(&answer) {
        Ok(a) => a,
        Err(e) => return error(e),
    };
    with_conn(h, |c| {
        let Some(pending) = c.pending.take() else { return error("no offer made") };
        match c.rtc.sdp_api().accept_answer(pending, answer) {
            Ok(()) => 0,
            Err(e) => error(e),
        }
    })
}

/// Whether a datagram (input as for rtc_receive) is for this connection: one
/// socket serves several.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_accepts(h: u32) -> i32 {
    with_conn(h, |c| {
        input_bytes(|b| {
            if b.len() < 2 * ADDR {
                return 0;
            }
            let (Some(source), Some(destination)) = (addr(&b[..ADDR]), addr(&b[ADDR..2 * ADDR])) else {
                return 0;
            };
            let Ok(contents) = b[2 * ADDR..].try_into() else { return 0 };
            let input = Input::Receive(Instant::now(), Receive { proto: Protocol::Udp, source, destination, contents });
            c.rtc.accepts(&input) as i32
        })
    })
}

/// A datagram arrived: input is source address, destination address, datagram.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_receive(h: u32) -> i32 {
    with_conn(h, |c| {
        input_bytes(|b| {
            if b.len() < 2 * ADDR {
                return error("short input");
            }
            let (Some(source), Some(destination)) = (addr(&b[..ADDR]), addr(&b[ADDR..2 * ADDR])) else {
                return error("bad addresses");
            };
            let contents = match b[2 * ADDR..].try_into() {
                Ok(c) => c,
                Err(e) => return error(e), // not a datagram str0m knows: ignore it upstream
            };
            let input = Input::Receive(Instant::now(), Receive { proto: Protocol::Udp, source, destination, contents });
            match c.rtc.handle_input(input) {
                Ok(()) => 0,
                Err(e) => error(e),
            }
        })
    })
}

/// The time asked for by the last wait has come.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_timeout(h: u32) -> i32 {
    with_conn(h, |c| match c.rtc.handle_input(Input::Timeout(Instant::now())) {
        Ok(()) => 0,
        Err(e) => error(e),
    })
}

/// The next output; call until it returns 0 (wait) or 8 (closed).
#[unsafe(no_mangle)]
pub extern "C" fn rtc_poll(h: u32) -> i32 {
    with_conn(h, |c| {
        let mut buf = Vec::new();
        let code = loop {
            if !c.rtc.is_alive() {
                break 8;
            }
            let out = match c.rtc.poll_output() {
                Ok(o) => o,
                Err(e) => return error(e),
            };
            break match out {
                Output::Timeout(t) => {
                    let micros = t.saturating_duration_since(Instant::now()).as_micros().min(u64::MAX as u128) as u64;
                    buf.extend_from_slice(&micros.to_le_bytes());
                    0
                }
                Output::Transmit(t) => {
                    put_addr(&mut buf, t.source);
                    put_addr(&mut buf, t.destination);
                    buf.extend_from_slice(&t.contents);
                    1
                }
                Output::Event(Event::ChannelOpen(id, label)) => {
                    buf.extend_from_slice(&channel_index(c, id).to_le_bytes());
                    buf.extend_from_slice(label.as_bytes());
                    2
                }
                Output::Event(Event::ChannelData(d)) => {
                    buf.extend_from_slice(&channel_index(c, d.id).to_le_bytes());
                    buf.push(d.binary as u8);
                    buf.extend_from_slice(&d.data);
                    3
                }
                Output::Event(Event::ChannelClose(id)) => {
                    buf.extend_from_slice(&channel_index(c, id).to_le_bytes());
                    4
                }
                Output::Event(Event::ChannelBufferedAmountLow(id)) => {
                    buf.extend_from_slice(&channel_index(c, id).to_le_bytes());
                    5
                }
                Output::Event(Event::Connected) => 6,
                Output::Event(Event::IceConnectionStateChange(s)) => {
                    buf.push(match s {
                        IceConnectionState::New => 0,
                        IceConnectionState::Checking => 1,
                        IceConnectionState::Connected => 2,
                        IceConnectionState::Completed => 3,
                        IceConnectionState::Disconnected => 4,
                    });
                    7
                }
                // Media and statistics: not used here.
                Output::Event(_) => continue,
            };
        };
        set_output(&buf);
        code
    })
}

fn channel_index(c: &mut Conn, id: ChannelId) -> u16 {
    match c.channels.iter().position(|x| *x == id) {
        Some(i) => i as u16,
        None => {
            c.channels.push(id);
            (c.channels.len() - 1) as u16
        }
    }
}

fn channel(c: &mut Conn, idx: u32) -> Option<ChannelId> {
    c.channels.get(idx as usize).copied()
}

/// Sends the input on a channel; 1 if accepted, 0 if its buffer is full.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_write(h: u32, idx: u32, binary: u32) -> i32 {
    with_conn(h, |c| {
        let Some(id) = channel(c, idx) else { return error("no such channel") };
        let Some(mut ch) = c.rtc.channel(id) else { return error("channel not open") };
        input_bytes(|b| match ch.write(binary != 0, b) {
            Ok(true) => 1,
            Ok(false) => 0,
            Err(e) => error(e),
        })
    })
}

/// Bytes queued on a channel and not yet sent.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_buffered(h: u32, idx: u32) -> i32 {
    with_conn(h, |c| {
        let Some(id) = channel(c, idx) else { return error("no such channel") };
        match c.rtc.channel(id) {
            Some(mut ch) => ch.buffered_amount().min(i32::MAX as usize) as i32,
            None => error("channel not open"),
        }
    })
}

/// Poll reports a buffered-amount-low event when a channel drains below this.
#[unsafe(no_mangle)]
pub extern "C" fn rtc_set_buffered_low(h: u32, idx: u32, threshold: u32) -> i32 {
    with_conn(h, |c| {
        let Some(id) = channel(c, idx) else { return error("no such channel") };
        match c.rtc.channel(id) {
            Some(mut ch) => {
                ch.set_buffered_amount_low_threshold(threshold as usize);
                0
            }
            None => error("channel not open"),
        }
    })
}
