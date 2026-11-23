package sculk.core.remote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game's side of signaling (docs/REMOTE.md): it listens on its topic's
 * relays, answers browsers' Noise handshakes, asks for approval, and once a
 * browser is approved sends it the WebRTC answer to its offer.
 *
 * <p>Nothing about a session reaches the game until its handshake is
 * complete, so a browser without the server's key cannot even ask. All state
 * belongs to one thread.
 */
public final class GameSignaling implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("sculk");
    /** Sessions at once, pending or not; more first messages are ignored. */
    static final int MAX_SESSIONS = 8;
    /** Out-of-order messages held per session. */
    private static final int MAX_HELD = 4;

    /** Decides whether a browser may connect. */
    public interface Approver {
        /**
         * Called once a browser's handshake completes, with its static key and
         * the code its page shows. Complete with true to approve; a remembered
         * key can complete at once. Must not block.
         */
        CompletableFuture<Boolean> approve(byte[] browserKey, String code);
    }

    private final KeyPair serverKey;
    private final String topic;
    private final Approver approver;
    private final BiFunction<byte[], String, CompletableFuture<String>> answerer;
    private final LongSupplier nowMs;
    private final ScheduledExecutorService thread;
    private final RelayPool pool;
    private final Nostr.Signer signer = new Nostr.Signer();
    private final Map<String, Session> sessions = new HashMap<>();
    /** Sessions that ended, so late copies of their first message start nothing. */
    private final java.util.LinkedHashSet<String> ended = new java.util.LinkedHashSet<>();

    private enum State { HANDSHAKE, APPROVAL, FINAL }

    private final class Session {
        final byte[] sid;
        final String sidHex;
        final NoiseXK noise;
        NoiseXK.Transport transport;
        State state = State.HANDSHAKE;
        int nextIn;
        int nextOut;
        final TreeMap<Integer, byte[]> held = new TreeMap<>();
        String lastContent;
        ScheduledFuture<?> resend;
        ScheduledFuture<?> deadline;

        Session(byte[] sid, String sidHex) {
            this.sid = sid;
            this.sidHex = sidHex;
            this.noise = NoiseXK.responder(serverKey, Signaling.prologue(sid));
        }
    }

    /**
     * @param relays the relay list ({@link RelayList#current()})
     * @param answerer turns an approved browser's WebRTC offer into the
     *     game's answer, given the browser's static key and its offer
     */
    public GameSignaling(KeyPair serverKey, List<String> relays, Approver approver,
            BiFunction<byte[], String, CompletableFuture<String>> answerer) {
        this(serverKey, relays, approver, answerer, null, System::currentTimeMillis);
    }

    GameSignaling(KeyPair serverKey, List<String> relays, Approver approver,
            BiFunction<byte[], String, CompletableFuture<String>> answerer, RelayPool.Connector connector, LongSupplier nowMs) {
        this.serverKey = serverKey;
        this.topic = Nostr.serverTopic(X25519.raw(serverKey.getPublic()));
        this.approver = approver;
        this.answerer = answerer;
        this.nowMs = nowMs;
        this.thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sculk-signaling");
            t.setDaemon(true);
            return t;
        });
        List<String> ranked = Nostr.relaysFor(topic, relays);
        this.pool = connector == null
                ? new RelayPool(ranked, Nostr.GAME_RELAYS, e -> run(() -> onEvent(e.get("content").getAsString())))
                : new RelayPool(ranked, Nostr.GAME_RELAYS, connector, () -> nowMs.getAsLong() / 1000,
                        e -> run(() -> onEvent(e.get("content").getAsString())));
        pool.subscribe(topic);
    }

    /** The topic this game listens on. */
    public String topic() {
        return topic;
    }

    /** The relays open now: for status. */
    public List<String> openRelays() {
        return pool.openRelays();
    }

    /** The WebRTC connection for {@code sid} is up: stop publishing the answer. */
    public void connected(String sidHex) {
        run(() -> end(sessions.get(sidHex), "connected"));
    }

    private void run(Runnable task) {
        if (thread.isShutdown()) return;
        thread.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.warn("remote signaling: {}", e.toString());
            }
        });
    }

    private void onEvent(String content) {
        Signaling.Envelope env;
        try {
            env = Signaling.Envelope.decode(content);
        } catch (IllegalArgumentException e) {
            return;
        }
        Session s = sessions.get(env.sidHex());
        if (s == null) {
            if (env.n() != 0 || sessions.size() >= MAX_SESSIONS || ended.contains(env.sidHex())) return;
            s = new Session(env.sid(), env.sidHex());
            sessions.put(s.sidHex, s);
            Session started = s;
            s.deadline = thread.schedule(() -> {
                if (started.state == State.HANDSHAKE) end(started, "handshake timed out");
            }, Signaling.HANDSHAKE_MS, TimeUnit.MILLISECONDS);
        }
        if (env.n() < s.nextIn) return; // a copy from another relay, or sent again
        if (env.n() > s.nextIn) {
            if (s.held.size() < MAX_HELD) s.held.putIfAbsent(env.n(), env.body());
            return;
        }
        byte[] body = env.body();
        while (body != null && sessions.get(s.sidHex) == s && handle(s, s.nextIn, body)) {
            s.nextIn++;
            body = s.held.remove(s.nextIn);
        }
    }

    /**
     * Handles the browser's message {@code n}; false if it was not the
     * browser's, and the real one may still come. The sid and n are in the
     * clear, so anyone watching can send a false message for a session: it
     * must not cost the browser its session.
     */
    private boolean handle(Session s, int n, byte[] body) {
        byte[] payload = null;
        try {
            if (n <= 1) payload = s.noise.readMessage(body);
        } catch (GeneralSecurityException | RuntimeException e) {
            LOG.debug("remote signaling {}: message {} dropped ({})", s.sidHex.substring(0, 8), n, e.toString());
            if (n == 0) {
                // Nothing came of this sid. Not ended(): the real first message may follow.
                cancel(s.deadline);
                sessions.remove(s.sidHex);
            }
            return false;
        }
        try {
            switch (n) {
                case 0 -> send(s, s.noise.writeMessage(new byte[0])); // Noise message 1; answer with message 2
                case 1 -> { // Noise message 3, carrying the offer
                    s.transport = s.noise.split();
                    stopResend(s);
                    JsonObject p = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
                    String offer = p.get("sdp").getAsString();
                    askApproval(s, offer);
                }
                default -> {
                    // Nothing more is defined from the browser.
                }
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            // From the browser, and not well formed.
            LOG.debug("remote signaling {}: dropped ({})", s.sidHex.substring(0, 8), e.toString());
            end(s, null);
        }
        return true;
    }

    private void askApproval(Session s, String offer) {
        s.state = State.APPROVAL;
        cancel(s.deadline);
        String code = Signaling.approvalCode(s.transport.secret());
        CompletableFuture<Boolean> decision = approver.approve(s.noise.remoteStatic(), code);
        if (!decision.isDone()) sendStatus(s, "{\"status\":\"pending\"}");
        s.deadline = thread.schedule(() -> {
            if (s.state == State.APPROVAL) finish(s, "{\"status\":\"expired\"}");
        }, Signaling.APPROVAL_MS, TimeUnit.MILLISECONDS);
        decision.whenComplete((ok, error) -> run(() -> {
            if (sessions.get(s.sidHex) != s || s.state != State.APPROVAL) return;
            if (error != null || !Boolean.TRUE.equals(ok)) {
                finish(s, "{\"status\":\"denied\"}");
                return;
            }
            answerer.apply(s.noise.remoteStatic(), offer).whenComplete((answer, err) -> run(() -> {
                if (sessions.get(s.sidHex) != s || s.state != State.APPROVAL) return;
                if (err != null) {
                    LOG.warn("remote signaling: no WebRTC answer: {}", err.toString());
                    finish(s, "{\"status\":\"denied\"}");
                    return;
                }
                var json = new JsonObject();
                json.addProperty("status", "approved");
                json.addProperty("sdp", answer);
                finish(s, json.toString());
            }));
        }));
    }

    /** The last message of a session: published until FINAL_MS passes or {@link #connected}. */
    private void finish(Session s, String status) {
        s.state = State.FINAL;
        cancel(s.deadline);
        sendStatus(s, status);
        s.deadline = thread.schedule(() -> end(s, "done"), Signaling.FINAL_MS, TimeUnit.MILLISECONDS);
    }

    private void sendStatus(Session s, String json) {
        try {
            send(s, s.transport.send().encrypt(new byte[0], json.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            end(s, e.toString());
        }
    }

    /** Publishes the session's next message, and again every RESEND_MS until superseded. */
    private void send(Session s, byte[] body) {
        stopResend(s);
        s.lastContent = new Signaling.Envelope(s.sid, s.nextOut++, body).encode();
        publish(s);
        s.resend = thread.scheduleAtFixedRate(() -> publish(s), Signaling.RESEND_MS, Signaling.RESEND_MS, TimeUnit.MILLISECONDS);
    }

    private void publish(Session s) {
        // A new event each time: relays drop an event id they have seen.
        pool.publish(signer.event(s.sidHex, s.lastContent, nowMs.getAsLong() / 1000));
    }

    private static void cancel(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }

    private void stopResend(Session s) {
        cancel(s.resend);
        s.resend = null;
    }

    private void end(Session s, String why) {
        if (s == null || sessions.get(s.sidHex) != s) return;
        if (why != null) LOG.debug("remote signaling {}: {}", s.sidHex.substring(0, 8), why);
        stopResend(s);
        cancel(s.deadline);
        sessions.remove(s.sidHex);
        ended.add(s.sidHex);
        if (ended.size() > 256) {
            var it = ended.iterator();
            it.next();
            it.remove();
        }
    }

    @Override
    public void close() {
        pool.close();
        thread.shutdownNow();
    }
}
