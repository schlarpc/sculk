package sculk.core.remote;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebRTC data channels for remote access (docs/REMOTE.md), on str0m running
 * as wasm under Chicory ({@link WebRtcModule}). It owns one UDP socket per
 * local address and one thread, which feeds each datagram to the connection
 * it belongs to, runs their timers, and calls each connection's
 * {@link Peer} with what happens.
 *
 * <p>One socket per address, not one on the wildcard, because str0m must
 * know which local address a datagram came in on to match it to the
 * candidates it offered. Each socket also asks public STUN servers for its
 * public address, offered to browsers as a server-reflexive candidate.
 *
 * <p>Peer callbacks run on the WebRtc thread, and {@link Connection} may be
 * used only there: from elsewhere, go through {@link #execute}.
 */
public final class WebRtc implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("sculk");
    /** A connection that has not connected by now is dropped. */
    static final long CONNECT_TIMEOUT_MS = 30_000;
    private static final long STUN_RETRY_MS = 2_000;
    private static final long STUN_REFRESH_MS = 5 * 60_000;
    /** How long an answer waits for a first STUN reply, so it can include the public address. */
    private static final long STUN_WAIT_MS = 1_500;

    /** One browser's connection, as the game sees it. Called on the WebRtc thread. */
    public interface Peer {
        default void connected(Connection c) {}

        default void opened(Connection c, int channel, String label) {}

        default void message(Connection c, int channel, byte[] data, boolean binary) {}

        /** A channel's queue drained below its low mark ({@link Connection#setLowMark}). */
        default void writable(Connection c, int channel) {}

        default void closed(Connection c) {}
    }

    private final WebRtcModule module = new WebRtcModule();
    private final boolean hostAes;
    private final Selector selector;
    private final List<Sock> socks = new ArrayList<>();
    private final Map<Integer, Connection> conns = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private final ByteBuffer buf = ByteBuffer.allocate(65536);
    private volatile boolean closed;

    /** One socket: a local address, and what STUN says it looks like from outside. */
    private static final class Sock {
        final DatagramChannel channel;
        final InetSocketAddress local;
        final byte[] localBytes;
        InetSocketAddress mapped;
        byte[] stunTxn;
        long stunNext;
        int stunServer;

        Sock(DatagramChannel channel, InetSocketAddress local) {
            this.channel = channel;
            this.local = local;
            this.localBytes = WebRtcModule.addr(local);
        }
    }

    /** One browser's connection. Use only on the WebRtc thread. */
    public final class Connection {
        final int handle;
        final Peer peer;
        final long started = System.currentTimeMillis();
        long deadlineNanos;
        boolean connected;
        boolean ended;
        final Map<Integer, String> channels = new HashMap<>();

        Connection(int handle, Peer peer) {
            this.handle = handle;
            this.peer = peer;
        }

        /** Sends on a channel; false if its queue is full (wait for {@link Peer#writable}). */
        public boolean send(int channel, byte[] data, boolean binary) {
            checkThread();
            if (ended) return false;
            try {
                boolean ok = module.write(handle, channel, data, binary);
                drain(this);
                return ok;
            } catch (WebRtcModule.ModuleError e) {
                LOG.debug("webrtc: send: {}", e.getMessage());
                return false;
            }
        }

        /** Bytes queued on a channel and not yet sent. */
        public int queued(int channel) {
            checkThread();
            try {
                return ended ? 0 : module.buffered(handle, channel);
            } catch (WebRtcModule.ModuleError e) {
                return 0;
            }
        }

        /** Below this many queued bytes, {@link Peer#writable} is called. */
        public void setLowMark(int channel, int bytes) {
            checkThread();
            try {
                if (!ended) module.setBufferedLow(handle, channel, bytes);
            } catch (WebRtcModule.ModuleError e) {
                LOG.debug("webrtc: low mark: {}", e.getMessage());
            }
        }

        public boolean isConnected() {
            return connected && !ended;
        }

        public void close() {
            checkThread();
            end(this);
        }
    }

    /** Binds to every usable local address, on {@code port} (0 for any). */
    public WebRtc(int port) throws IOException {
        this(localAddresses(), port, true);
    }

    WebRtc(List<InetAddress> addresses, int port, boolean hostAes) throws IOException {
        this.hostAes = hostAes;
        this.selector = Selector.open();
        for (InetAddress a : addresses) {
            DatagramChannel ch = DatagramChannel.open(a instanceof Inet4Address
                    ? StandardProtocolFamily.INET : StandardProtocolFamily.INET6);
            try {
                ch.bind(new InetSocketAddress(a, port));
            } catch (IOException e) {
                ch.close();
                LOG.debug("webrtc: cannot bind {}: {}", a, e.toString());
                continue;
            }
            ch.configureBlocking(false);
            var sock = new Sock(ch, (InetSocketAddress) ch.getLocalAddress());
            ch.register(selector, SelectionKey.OP_READ, sock);
            socks.add(sock);
        }
        if (socks.isEmpty()) throw new IOException("no local address to bind for WebRTC");
        thread = new Thread(this::loop, "sculk-webrtc");
        thread.setDaemon(true);
        thread.start();
    }

    /** Non-loopback, non-link-local IPv4 addresses of interfaces that are up. */
    static List<InetAddress> localAddresses() throws SocketException {
        List<InetAddress> out = new ArrayList<>();
        for (NetworkInterface ni : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet4Address && !a.isLinkLocalAddress() && !a.isLoopbackAddress()) out.add(a);
            }
        }
        return out;
    }

    /** The sockets' addresses: local, and public as STUN reports them (null until it answers). */
    public CompletableFuture<List<InetSocketAddress[]>> addresses() {
        return call(() -> socks.stream().map(s -> new InetSocketAddress[] {s.local, s.mapped}).toList());
    }

    /**
     * Answers a browser's offer. The connection is created at once, and
     * {@code peer} hears about it from then on; the future gives the answer
     * to send back, with every candidate in it (no trickle).
     */
    public CompletableFuture<String> answer(String offer, Peer peer) {
        var result = new CompletableFuture<String>();
        // Give STUN a moment, so the answer can carry the public address.
        long waitUntil = System.currentTimeMillis() + STUN_WAIT_MS;
        Runnable attempt = new Runnable() {
            @Override
            public void run() {
                boolean someMapped = socks.stream().anyMatch(s -> s.mapped != null);
                if (!someMapped && System.currentTimeMillis() < waitUntil) {
                    schedule(this, 100);
                    return;
                }
                Connection c = newConnection(peer);
                try {
                    String answer = module.acceptOffer(c.handle, offer);
                    drain(c);
                    result.complete(answer);
                } catch (WebRtcModule.ModuleError e) {
                    end(c);
                    result.completeExceptionally(new IOException("bad offer: " + e.getMessage()));
                }
            }
        };
        execute(attempt);
        return result;
    }

    /** Makes an offer with one data channel: the browser's side, for tests. */
    CompletableFuture<Map.Entry<Connection, String>> offer(String label, Peer peer) {
        return call(() -> {
            Connection c = newConnection(peer);
            return Map.entry(c, module.offer(c.handle, label));
        });
    }

    /** Takes the answer to an {@link #offer}: for tests. */
    CompletableFuture<Void> acceptAnswer(Connection c, String answer) {
        return call(() -> {
            module.acceptAnswer(c.handle, answer);
            drain(c);
            return null;
        });
    }

    private Connection newConnection(Peer peer) {
        Connection c = new Connection(module.create(hostAes), peer);
        conns.put(c.handle, c);
        for (Sock s : socks) {
            try {
                module.addHost(c.handle, s.local);
                if (s.mapped != null && !s.mapped.equals(s.local)) module.addSrflx(c.handle, s.mapped, s.local);
            } catch (WebRtcModule.ModuleError e) {
                LOG.debug("webrtc: candidate {}: {}", s.local, e.getMessage());
            }
        }
        return c;
    }

    /** Runs {@code task} on the WebRtc thread. */
    public void execute(Runnable task) {
        tasks.add(task);
        selector.wakeup();
    }

    private interface Call<T> {
        T run() throws Exception;
    }

    private <T> CompletableFuture<T> call(Call<T> task) {
        var f = new CompletableFuture<T>();
        execute(() -> {
            try {
                f.complete(task.run());
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f;
    }

    private final List<Map.Entry<Long, Runnable>> timers = new ArrayList<>();

    private void schedule(Runnable task, long ms) {
        timers.add(Map.entry(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms), task));
    }

    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("use WebRtc.execute");
    }

    private void loop() {
        try {
            while (!closed) {
                long now = System.nanoTime();
                long next = now + TimeUnit.SECONDS.toNanos(1);
                for (Connection c : conns.values()) next = Math.min(next, c.deadlineNanos);
                for (Sock s : socks) next = Math.min(next, s.stunNext);
                for (var t : timers) next = Math.min(next, t.getKey());
                long waitMs = Math.max(0, TimeUnit.NANOSECONDS.toMillis(next - now));
                // Always select, even with tasks waiting: that is what reads
                // the sockets, and a task may be waiting on what they bring.
                if (!tasks.isEmpty() || waitMs == 0) selector.selectNow();
                else selector.select(waitMs);
                for (Runnable t; (t = tasks.poll()) != null; ) safely(t::run);
                for (SelectionKey key : selector.selectedKeys()) readAll((Sock) key.attachment());
                selector.selectedKeys().clear();
                now = System.nanoTime();
                for (Sock s : socks) if (s.stunNext <= now) stun(s, now);
                for (Connection c : new ArrayList<>(conns.values())) {
                    if (c.deadlineNanos <= now) {
                        safely(() -> {
                            module.timeout(c.handle);
                            drain(c);
                        });
                    }
                    if (!c.connected && !c.ended && System.currentTimeMillis() - c.started > CONNECT_TIMEOUT_MS) {
                        LOG.info("webrtc: connection did not connect in {} s", CONNECT_TIMEOUT_MS / 1000);
                        end(c);
                    }
                }
                List<Runnable> due = new ArrayList<>();
                timers.removeIf(t -> {
                    if (t.getKey() <= System.nanoTime()) {
                        due.add(t.getValue());
                        return true;
                    }
                    return false;
                });
                due.forEach(t -> safely(t::run));
            }
        } catch (IOException | RuntimeException e) {
            if (!closed) LOG.error("webrtc: stopped: {}", e.toString());
        } finally {
            for (Connection c : new ArrayList<>(conns.values())) end(c);
            for (Sock s : socks) {
                try {
                    s.channel.close();
                } catch (IOException ignored) {
                    // closing anyway
                }
            }
        }
    }

    private interface Step {
        void run() throws Exception;
    }

    private void safely(Step s) {
        try {
            s.run();
        } catch (Exception e) {
            LOG.warn("webrtc: {}", e.toString());
        }
    }

    private void readAll(Sock s) throws IOException {
        while (true) {
            buf.clear();
            var from = (InetSocketAddress) s.channel.receive(buf);
            if (from == null) return;
            buf.flip();
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            if (Stun.isStun(data) && s.stunTxn != null && Arrays.equals(Stun.transactionId(data), s.stunTxn)) {
                InetSocketAddress mapped = Stun.mappedAddress(data);
                if (mapped != null) {
                    if (!mapped.equals(s.mapped)) LOG.debug("webrtc: {} is {} from outside", s.local, mapped);
                    s.mapped = mapped;
                    s.stunTxn = null;
                    s.stunNext = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STUN_REFRESH_MS);
                }
                continue;
            }
            byte[] fromBytes = WebRtcModule.addr(from);
            for (Connection c : conns.values()) {
                if (module.accepts(c.handle, fromBytes, s.localBytes, data)) {
                    safely(() -> {
                        module.receive(c.handle, fromBytes, s.localBytes, data);
                        drain(c);
                    });
                    break;
                }
            }
        }
    }

    private void stun(Sock s, long now) {
        String server = Stun.SERVERS.get(s.stunServer++ % Stun.SERVERS.size());
        s.stunNext = now + TimeUnit.MILLISECONDS.toNanos(STUN_RETRY_MS);
        if (s.local.getAddress().isLoopbackAddress()) {
            s.stunNext = Long.MAX_VALUE; // nobody outside to ask
            return;
        }
        try {
            int colon = server.lastIndexOf(':');
            var to = new InetSocketAddress(InetAddress.getByName(server.substring(0, colon)),
                    Integer.parseInt(server.substring(colon + 1)));
            if ((to.getAddress() instanceof Inet4Address) != (s.local.getAddress() instanceof Inet4Address)) return;
            s.stunTxn = Stun.transactionId();
            s.channel.send(ByteBuffer.wrap(Stun.bindingRequest(s.stunTxn)), to);
        } catch (IOException | RuntimeException e) {
            LOG.debug("webrtc: STUN {} from {}: {}", server, s.local, e.toString());
        }
    }

    /** Takes every output the connection has, until it waits or ends. */
    private void drain(Connection c) throws WebRtcModule.ModuleError {
        while (!c.ended) {
            int code = module.poll(c.handle);
            byte[] out = module.output();
            switch (code) {
                case WebRtcModule.WAIT -> {
                    long micros = ByteBuffer.wrap(out).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
                    c.deadlineNanos = System.nanoTime() + Math.min(micros, 60_000_000L) * 1000;
                    return;
                }
                case WebRtcModule.TRANSMIT -> transmit(out);
                case WebRtcModule.CHANNEL_OPEN -> {
                    int ch = u16(out);
                    String label = new String(out, 2, out.length - 2, StandardCharsets.UTF_8);
                    c.channels.put(ch, label);
                    c.peer.opened(c, ch, label);
                }
                case WebRtcModule.CHANNEL_DATA ->
                        c.peer.message(c, u16(out), Arrays.copyOfRange(out, 3, out.length), out[2] != 0);
                case WebRtcModule.CHANNEL_CLOSE -> c.channels.remove(u16(out));
                case WebRtcModule.BUFFERED_LOW -> c.peer.writable(c, u16(out));
                case WebRtcModule.CONNECTED -> {
                    c.connected = true;
                    c.peer.connected(c);
                }
                case WebRtcModule.ICE_STATE -> {
                    if (out[0] == 4 && c.connected) LOG.debug("webrtc: ICE disconnected");
                }
                case WebRtcModule.CLOSED -> end(c);
                default -> {}
            }
        }
    }

    private void transmit(byte[] out) {
        InetSocketAddress from = WebRtcModule.addr(out, 0);
        InetSocketAddress to = WebRtcModule.addr(out, WebRtcModule.ADDR);
        ByteBuffer data = ByteBuffer.wrap(out, 2 * WebRtcModule.ADDR, out.length - 2 * WebRtcModule.ADDR);
        for (Sock s : socks) {
            if (s.local.equals(from)) {
                try {
                    s.channel.send(data, to);
                } catch (IOException e) {
                    LOG.debug("webrtc: send to {}: {}", to, e.toString());
                }
                return;
            }
        }
        LOG.debug("webrtc: no socket for {}", from);
    }

    private static int u16(byte[] b) {
        return (b[0] & 0xff) | ((b[1] & 0xff) << 8);
    }

    private void end(Connection c) {
        if (c.ended) return;
        c.ended = true;
        conns.remove(c.handle);
        module.free(c.handle);
        safely(() -> c.peer.closed(c));
    }

    @Override
    public void close() {
        closed = true;
        selector.wakeup();
        try {
            thread.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            selector.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }
}
