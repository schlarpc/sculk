package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GameSignalingTest {
    private static final List<String> RELAYS = IntStream.range(0, 12).mapToObj(i -> "wss://r" + i).toList();

    /** The browser's side, as the shell does it, minus WebRTC. */
    static final class TestBrowser implements AutoCloseable {
        final KeyPair key = X25519.generate();
        final byte[] sid = new byte[Signaling.SID];
        final String sidHex;
        final NoiseXK noise;
        final RelayPool pool;
        final String serverTopic;
        final Nostr.Signer signer = new Nostr.Signer();
        final BlockingQueue<JsonObject> statuses = new LinkedBlockingQueue<>();
        NoiseXK.Transport transport;
        int nextIn;
        volatile String lastContent;
        final java.util.concurrent.ScheduledExecutorService resend =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

        TestBrowser(FakeRelays.Net net, byte[] serverKey) throws Exception {
            this(net, serverKey, Nostr.serverTopic(serverKey));
        }

        /** Holding {@code serverKey}, but publishing on {@code topic}. */
        TestBrowser(FakeRelays.Net net, byte[] serverKey, String topic) throws Exception {
            new SecureRandom().nextBytes(sid);
            sidHex = HexFormat.of().formatHex(sid);
            serverTopic = topic;
            noise = NoiseXK.initiator(key, serverKey, Signaling.PROLOGUE);
            pool = new RelayPool(Nostr.relaysFor(serverTopic, RELAYS), Nostr.BROWSER_RELAYS, net,
                    () -> System.currentTimeMillis() / 1000, e -> onEvent(e.get("content").getAsString()));
            pool.subscribe(sidHex);
            send(0, noise.writeMessage(new byte[0]));
            resend.scheduleAtFixedRate(this::publish, 200, 200, TimeUnit.MILLISECONDS);
        }

        void send(int n, byte[] body) {
            lastContent = new Signaling.Envelope(sid, n, body).encode();
            publish();
        }

        void publish() {
            pool.publish(signer.event(serverTopic, lastContent, System.currentTimeMillis() / 1000));
        }

        synchronized void onEvent(String content) {
            var env = Signaling.Envelope.decode(content);
            if (env.n() != nextIn) return;
            nextIn++;
            try {
                if (env.n() == 0) {
                    noise.readMessage(env.body());
                    send(1, noise.writeMessage("{\"sdp\":\"OFFER\"}".getBytes(StandardCharsets.UTF_8)));
                    transport = noise.split();
                } else {
                    resend.shutdownNow(); // the game has message 3
                    statuses.add(JsonParser.parseString(new String(
                            transport.receive().decrypt(new byte[0], env.body()), StandardCharsets.UTF_8)).getAsJsonObject());
                }
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }

        JsonObject next() throws InterruptedException {
            JsonObject s = statuses.poll(15, TimeUnit.SECONDS);
            if (s == null) throw new AssertionError("no status");
            return s;
        }

        @Override
        public void close() {
            resend.shutdownNow();
            pool.close();
        }
    }

    private static GameSignaling game(FakeRelays.Net net, KeyPair key, GameSignaling.Approver approver) {
        return new GameSignaling(key, RELAYS, approver,
                offer -> CompletableFuture.completedFuture("ANSWER to " + offer), net, System::currentTimeMillis);
    }

    @Test
    void aRememberedBrowserGetsTheAnswerAtOnce() throws Exception {
        var net = new FakeRelays.Net();
        KeyPair server = X25519.generate();
        var seen = new AtomicReference<byte[]>();
        var code = new AtomicReference<String>();
        try (var g = game(net, server, (key, c) -> {
                    seen.set(key);
                    code.set(c);
                    return CompletableFuture.completedFuture(true);
                });
             var b = new TestBrowser(net, X25519.raw(server.getPublic()))) {
            JsonObject s = b.next();
            assertEquals("approved", s.get("status").getAsString());
            assertEquals("ANSWER to OFFER", s.get("sdp").getAsString());
            assertEquals(HexFormat.of().formatHex(X25519.raw(b.key.getPublic())), HexFormat.of().formatHex(seen.get()));
            assertEquals(Signaling.approvalCode(b.noise.handshakeHash()), code.get());
        }
    }

    @Test
    void anUnknownBrowserWaitsThenIsApproved() throws Exception {
        var net = new FakeRelays.Net();
        KeyPair server = X25519.generate();
        var decision = new CompletableFuture<Boolean>();
        try (var g = game(net, server, (key, c) -> decision);
             var b = new TestBrowser(net, X25519.raw(server.getPublic()))) {
            assertEquals("pending", b.next().get("status").getAsString());
            decision.complete(true);
            assertEquals("approved", b.next().get("status").getAsString());
        }
    }

    @Test
    void aDeniedBrowserIsTold() throws Exception {
        var net = new FakeRelays.Net();
        KeyPair server = X25519.generate();
        try (var g = game(net, server, (key, c) -> CompletableFuture.completedFuture(false));
             var b = new TestBrowser(net, X25519.raw(server.getPublic()))) {
            assertEquals("denied", b.next().get("status").getAsString());
        }
    }

    @Test
    void theWrongServerKeyGetsNothing() throws Exception {
        var net = new FakeRelays.Net();
        KeyPair server = X25519.generate();
        KeyPair other = X25519.generate();
        var asked = new AtomicReference<String>();
        // The browser has the wrong key, but publishes on the real server's topic.
        try (var g = game(net, server, (key, c) -> {
                    asked.set(c);
                    return CompletableFuture.completedFuture(true);
                });
             var b = new TestBrowser(net, X25519.raw(other.getPublic()), g.topic())) {
            assertNull(b.statuses.poll(2, TimeUnit.SECONDS));
            assertNull(asked.get(), "approval was never asked");
        }
    }

    @Test
    void lostMessagesAreSentAgain() throws Exception {
        var net = new FakeRelays.Net();
        for (String r : RELAYS) net.relay(r).lossy = true;
        KeyPair server = X25519.generate();
        var decision = new CompletableFuture<Boolean>();
        try (var g = game(net, server, (key, c) -> decision);
             var b = new TestBrowser(net, X25519.raw(server.getPublic()))) {
            assertEquals("pending", b.next().get("status").getAsString());
            decision.complete(true);
            assertEquals("approved", b.next().get("status").getAsString());
            assertTrue(b.statuses.isEmpty());
        }
    }
}
