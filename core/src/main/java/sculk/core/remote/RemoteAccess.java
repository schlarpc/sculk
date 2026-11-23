package sculk.core.remote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.XECPrivateKey;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sculk.core.platform.ChatLine;

/**
 * Remote access (docs/REMOTE.md): the game's persistent key and its link,
 * and the pieces that reach browsers: {@link RelayList}, {@link
 * GameSignaling}, {@link Approvals} and {@link WebRtc}.
 *
 * <p>Its files, in {@code sculk/remote/} in the game directory (not under {@code config/}, which modpack exports take along), readable by the owner only:
 * {@code server.key}, the X25519 private key the link names, and
 * {@code browsers.json}, the browsers that come back without asking.
 */
public final class RemoteAccess implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("sculk");
    public static final String SHELL = "https://sculk-ui.github.io/";
    private static final long RELAY_REFRESH_HOURS = 24;

    private final KeyPair key;
    private final Approvals approvals;
    private final RelayList relays;
    private final WebRtc webrtc;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sculk-remote");
        t.setDaemon(true);
        return t;
    });
    private volatile GameSignaling signaling;
    private final java.util.function.Supplier<Tunnel.Upstream> upstream;
    /** Threads that read the control server's answers for tunnels. */
    private final java.util.concurrent.ExecutorService readers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sculk-tunnel");
        t.setDaemon(true);
        return t;
    });

    /**
     * Starts remote access: loads or makes the key, and starts listening.
     *
     * @param dir where its files are ({@code sculk/remote})
     * @param cacheDir where the relay list is cached ({@code config/sculk/cache})
     * @param port the UDP port for WebRTC; 0 for any
     * @param chat shows a line to the player, or the server console
     * @param upstream the control server that approved browsers reach, or
     *     null while it is not running
     */
    public RemoteAccess(Path dir, Path cacheDir, int port, Consumer<ChatLine> chat,
            java.util.function.Supplier<Tunnel.Upstream> upstream) throws IOException {
        this.upstream = upstream;
        Files.createDirectories(dir);
        this.key = loadOrCreateKey(dir.resolve("server.key"));
        this.approvals = new Approvals(dir.resolve("browsers.json"), chat, timers);
        this.relays = new RelayList(cacheDir.resolve("relays.json"));
        this.webrtc = new WebRtc(port);
        // The published relay list first (briefly), then start either way.
        timers.execute(() -> {
            refreshRelays();
            startSignaling();
        });
        timers.scheduleAtFixedRate(() -> {
            List<String> before = relays.current();
            refreshRelays();
            if (!before.equals(relays.current())) startSignaling();
        }, RELAY_REFRESH_HOURS, RELAY_REFRESH_HOURS, TimeUnit.HOURS);
    }

    /** The link a browser opens: the shell, with this game's key in the fragment. */
    public String link() {
        return SHELL + "#" + Base64.getUrlEncoder().withoutPadding().encodeToString(X25519.raw(key.getPublic()));
    }

    public Approvals approvals() {
        return approvals;
    }

    /** Forgets a browser and drops its connections, whether or not the file can then be written. */
    public void revoke(int number) throws IOException {
        List<WebRtc.Connection> conns = approvals.revoke(number);
        webrtc.execute(() -> conns.forEach(WebRtc.Connection::close));
        approvals.save();
    }

    private void refreshRelays() {
        try {
            relays.refresh();
        } catch (IOException e) {
            LOG.info("remote access: cannot fetch {} ({}); using the saved relay list", RelayList.SOURCE, e.getMessage());
        }
    }

    private synchronized void startSignaling() {
        GameSignaling old = signaling;
        signaling = new GameSignaling(key, relays.current(), approvals::approve, this::answer);
        if (old != null) old.close();
    }

    /** GameSignaling asks for the answer to an approved browser's offer. */
    private CompletableFuture<String> answer(byte[] browserKey, String offer) {
        // Approved just now. Connecting takes a while yet, and a revoke in
        // that time finds no connection to close: so each step asks again.
        int epoch = approvals.epoch(browserKey);
        return webrtc.answer(offer, new WebRtc.Peer() {
            ChannelLink link;
            Tunnel tunnel;

            private boolean revoked(WebRtc.Connection c) {
                if (approvals.allowed(browserKey, epoch)) return false;
                webrtc.execute(c::close);
                return true;
            }

            @Override
            public void connected(WebRtc.Connection c) {
                approvals.connected(browserKey, c);
                if (revoked(c)) return;
                LOG.info("remote access: browser #{} connected", approvals.browser(browserKey).number);
            }

            @Override
            public void opened(WebRtc.Connection c, int channel, String label) {
                if (!label.equals("sculk") || tunnel != null || revoked(c)) return;
                link = new ChannelLink(webrtc, c, channel);
                tunnel = new Tunnel(link, upstream, readers);
            }

            @Override
            public void message(WebRtc.Connection c, int channel, byte[] data, boolean binary) {
                if (tunnel != null && binary) tunnel.receive(data);
            }

            @Override
            public void writable(WebRtc.Connection c, int channel) {
                if (link != null) link.flush();
            }

            @Override
            public void closed(WebRtc.Connection c) {
                approvals.disconnected(browserKey, c);
                if (tunnel != null) tunnel.close();
                if (link != null) link.close();
                LOG.info("remote access: browser #{} disconnected", approvals.browser(browserKey).number);
            }
        });
    }

    /**
     * Moves the files of a game that kept them in {@code old}
     * ({@code config/sculk/remote}, at first) to {@code dir}, unless
     * {@code dir} has a key already: its link and its remembered browsers
     * stay good.
     */
    public static void moveFrom(Path old, Path dir) throws IOException {
        if (!Files.isRegularFile(old.resolve("server.key")) || Files.exists(dir.resolve("server.key"))) return;
        Files.createDirectories(dir);
        for (String name : List.of("browsers.json", "server.key")) { // the key last: it is what says this is done
            Path from = old.resolve(name);
            if (Files.isRegularFile(from)) Files.move(from, dir.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.deleteIfExists(old);
        } catch (IOException e) {
            // Something else is in it: leave it.
        }
        LOG.info("remote access: moved the server key and remembered browsers from {} to {}", old, dir.toAbsolutePath());
    }

    /** The server key in {@code file} (base64 of the raw private key), made on first use. */
    static KeyPair loadOrCreateKey(Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            return X25519.fromPrivate(Base64.getDecoder().decode(Files.readString(file).trim()));
        }
        KeyPair k = X25519.generate();
        byte[] raw = ((XECPrivateKey) k.getPrivate()).getScalar()
                .orElseThrow(() -> new IOException("this JVM does not expose X25519 private keys"));
        Approvals.writeOwnerOnly(file, Base64.getEncoder().encodeToString(raw) + "\n");
        LOG.info("remote access: made a new server key in {}", file.toAbsolutePath());
        return k;
    }

    @Override
    public void close() {
        timers.shutdownNow();
        if (signaling != null) signaling.close();
        webrtc.close();
        readers.shutdownNow();
    }
}
