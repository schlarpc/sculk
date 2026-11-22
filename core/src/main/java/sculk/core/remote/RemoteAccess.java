package sculk.core.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
 * <p>Its files, in {@code config/sculk/remote/}, readable by the owner only:
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

    /**
     * Starts remote access: loads or makes the key, and starts listening.
     *
     * @param dir where its files are ({@code config/sculk/remote})
     * @param cacheDir where the relay list is cached ({@code config/sculk/cache})
     * @param port the UDP port for WebRTC; 0 for any
     * @param chat shows a line to the player, or the server console
     */
    public RemoteAccess(Path dir, Path cacheDir, int port, Consumer<ChatLine> chat) throws IOException {
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

    /** Forgets a remembered browser and drops its connections. */
    public void revoke(int number) throws IOException {
        List<WebRtc.Connection> conns = approvals.revoke(number);
        webrtc.execute(() -> conns.forEach(WebRtc.Connection::close));
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
        return webrtc.answer(offer, new WebRtc.Peer() {
            @Override
            public void connected(WebRtc.Connection c) {
                approvals.connected(browserKey, c);
                LOG.info("remote access: browser #{} connected", approvals.browser(browserKey).number);
            }

            @Override
            public void message(WebRtc.Connection c, int channel, byte[] data, boolean binary) {
                // Until the control server is carried over the channel: echo.
                c.send(channel, ("sculk: " + new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8), false);
            }

            @Override
            public void closed(WebRtc.Connection c) {
                approvals.disconnected(browserKey, c);
            }
        });
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
        LOG.info("remote access: made a new server key in {}", file);
        return k;
    }

    @Override
    public void close() {
        timers.shutdownNow();
        if (signaling != null) signaling.close();
        webrtc.close();
    }
}
