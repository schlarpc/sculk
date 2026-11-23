package sculk.core.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connections to the Nostr relays of one topic (docs/REMOTE.md): it keeps
 * {@code want} of them open, taken in the topic's rendezvous order, publishes
 * to all of them, subscribes on all of them, and hands each event on once
 * however many relays deliver it.
 *
 * <p>The relays are public and not trusted: what one sends is bounded
 * ({@link #MESSAGE_MAX}, and no more is read from it until that is dealt
 * with), an event counts only under the id its own fields hash to, and
 * anything else it says is at most logged, cut short. Signatures are not
 * checked: the Noise handshake in the content is what authenticates.
 *
 * <p>A relay that refuses for good ({@code blocked:}, {@code restricted:},
 * {@code auth-required:}, {@code pow:}) is dropped and the next in order
 * taken; one that disconnects is retried with backoff. Publishing is
 * best effort: a relay that is down misses the event, and the signaling
 * session above publishes again until it hears back.
 *
 * <p>All state belongs to one thread; listeners are called on it.
 */
public final class RelayPool implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("sculk");
    private static final Pattern TERMINAL = Pattern.compile("^(blocked|restricted|auth-required|pow):.*", Pattern.DOTALL);
    private static final int SEEN_MAX = 4096;
    private static final long BACKOFF_MIN_MS = 1000;
    private static final long BACKOFF_MAX_MS = 60_000;
    /**
     * The most one message from a relay may hold, in chars. An event's
     * content is a Noise message of at most 65535 bytes in base64, so a real
     * one is under 100 KiB; a relay that sends more is up to no good.
     */
    static final int MESSAGE_MAX = 256 * 1024;
    private static final int LOG_MAX = 200;
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/=]*");
    private static final Pattern INTEGER = Pattern.compile("0|[1-9][0-9]{0,17}");

    /** One connection; the real one is a WebSocket, tests use a fake relay. */
    interface Link {
        void send(String text);

        void close();
    }

    /** What a link reports, on any thread. */
    interface LinkEvents {
        void opened();

        /** A whole message; the link reads no more until the stage returned completes. */
        CompletionStage<?> text(String text);

        void closed(String why);
    }

    /** Opens links: {@link #webSockets} for real, a fake relay in tests. */
    interface Connector {
        Link open(String url, LinkEvents events);
    }

    private final List<String> order;
    private final int want;
    private final Connector connector;
    private final LongSupplier nowSeconds;
    private final Consumer<JsonObject> onEvent;
    private final ScheduledExecutorService thread;

    private final Map<String, Relay> active = new LinkedHashMap<>();
    private final Set<String> retired = new HashSet<>();
    private final Set<String> topics = new LinkedHashSet<>();
    private final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private boolean closed;

    private final class Relay implements LinkEvents {
        final String url;
        Link link;
        boolean open;
        long backoff = BACKOFF_MIN_MS;

        Relay(String url) {
            this.url = url;
        }

        void connect() {
            link = connector.open(url, this);
        }

        @Override
        public void opened() {
            run(() -> {
                if (active.get(url) != this) return;
                open = true;
                backoff = BACKOFF_MIN_MS;
                for (String topic : topics) sendReq(this, topic);
            });
        }

        @Override
        public CompletionStage<?> text(String text) {
            var done = new CompletableFuture<Void>();
            run(() -> {
                try {
                    if (active.get(url) == this) receive(this, text);
                } catch (RuntimeException e) {
                    // Whatever a relay can make this throw is its nonsense, not ours.
                    LOG.debug("nostr {}: ignored a message ({})", url, clean(e.toString()));
                } finally {
                    done.complete(null);
                }
            });
            return done;
        }

        @Override
        public void closed(String why) {
            run(() -> {
                if (active.get(url) != this) return;
                open = false;
                link = null;
                if (closed) return;
                LOG.debug("nostr {}: closed ({}); retrying in {} ms", url, clean(why), backoff);
                long delay = backoff;
                backoff = Math.min(backoff * 2, BACKOFF_MAX_MS);
                thread.schedule(() -> {
                    if (!closed && active.get(url) == this && link == null) connect();
                }, delay, TimeUnit.MILLISECONDS);
            });
        }
    }

    /**
     * @param ranked relays in the topic's rendezvous order ({@link Nostr#relaysFor})
     * @param want how many to keep open
     */
    public RelayPool(List<String> ranked, int want, Consumer<JsonObject> onEvent) {
        this(ranked, want, webSockets(), () -> System.currentTimeMillis() / 1000, onEvent);
    }

    RelayPool(List<String> ranked, int want, Connector connector, LongSupplier nowSeconds, Consumer<JsonObject> onEvent) {
        this.order = List.copyOf(ranked);
        this.want = want;
        this.connector = connector;
        this.nowSeconds = nowSeconds;
        this.onEvent = onEvent;
        this.thread = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sculk-nostr");
            t.setDaemon(true);
            return t;
        });
        run(this::fill);
    }

    /** Receive events on {@code topic} from now on, on every relay, including ones opened later. */
    public void subscribe(String topic) {
        run(() -> {
            if (!topics.add(topic)) return;
            for (Relay r : active.values()) if (r.open) sendReq(r, topic);
        });
    }

    /** Send a signed event ({@link Nostr.Signer#event}) to every open relay. */
    public void publish(String event) {
        run(() -> {
            JsonObject e = JsonParser.parseString(event).getAsJsonObject();
            remember(e.get("id").getAsString()); // our own, if a relay echoes it
            String msg = "[\"EVENT\"," + event + "]";
            for (Relay r : active.values()) if (r.open) r.link.send(msg);
        });
    }

    /** The relays open now: for status and tests. */
    List<String> openRelays() {
        return call(() -> active.values().stream().filter(r -> r.open).map(r -> r.url).toList());
    }

    /** Runs {@code task} on the pool's thread. */
    void run(Runnable task) {
        if (!thread.isShutdown()) {
            thread.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    LOG.warn("nostr: {}", e.toString());
                }
            });
        }
    }

    private <T> T call(java.util.concurrent.Callable<T> task) {
        try {
            return thread.submit(task).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        call(() -> {
            closed = true;
            for (Relay r : active.values()) {
                if (r.link != null) r.link.close();
            }
            active.clear();
            return null;
        });
        thread.shutdown();
    }

    /** Opens relays in order until {@code want} are active or the list runs out. */
    private void fill() {
        for (String url : order) {
            if (active.size() >= want) return;
            if (active.containsKey(url) || retired.contains(url)) continue;
            Relay r = new Relay(url);
            active.put(url, r);
            r.connect();
        }
        if (active.size() < want) LOG.warn("nostr: only {} usable relays of {} wanted", active.size(), want);
    }

    private void retire(Relay r, String why) {
        LOG.info("nostr {}: dropped ({})", r.url, clean(why));
        active.remove(r.url);
        retired.add(r.url);
        if (r.link != null) r.link.close();
        fill();
    }

    private static String subId(String topic) {
        return "sculk-" + topic.substring(0, 16);
    }

    private void sendReq(Relay r, String topic) {
        r.link.send("[\"REQ\",\"" + subId(topic) + "\"," + Nostr.filter(topic, nowSeconds.getAsLong()) + "]");
    }

    private void remember(String id) {
        seen.add(id);
        if (seen.size() > SEEN_MAX) {
            var it = seen.iterator();
            it.next();
            it.remove();
        }
    }

    /** What a relay said, fit for the log: one line, printable ASCII, cut short. */
    static String clean(String s) {
        if (s == null) return "";
        var out = new StringBuilder(Math.min(s.length(), LOG_MAX + 1));
        for (int i = 0; i < s.length() && i < LOG_MAX; i++) {
            char c = s.charAt(i);
            out.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        if (s.length() > LOG_MAX) out.append('~');
        return out.toString();
    }

    /** {@code e} if it is a JSON string, else null. */
    private static String string(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private void receive(Relay r, String text) {
        JsonArray msg;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonArray() || parsed.getAsJsonArray().isEmpty()) return;
            msg = parsed.getAsJsonArray();
        } catch (RuntimeException e) {
            return; // a relay talking nonsense is ignored, not fatal
        }
        String type = string(msg.get(0));
        if (type == null) return;
        switch (type) {
            case "EVENT" -> {
                if (msg.size() < 3 || !msg.get(2).isJsonObject()) return;
                JsonObject event = msg.get(2).getAsJsonObject();
                String topic = topicOf(event);
                if (topic == null || !topics.contains(topic) || !subId(topic).equals(string(msg.get(1)))) return;
                // The id is the event's own hash, not the relay's word for it:
                // else one relay could send junk under a real event's id, and
                // the real one, from the others, would be dropped as seen.
                String id = idOf(event, topic);
                if (id == null || !id.equals(string(event.get("id")))) return;
                if (seen.contains(id)) return;
                remember(id);
                onEvent.accept(event);
            }
            case "OK" -> {
                // ["OK", id, accepted, message]
                if (msg.size() < 4 || !msg.get(2).isJsonPrimitive() || !msg.get(2).getAsJsonPrimitive().isBoolean()) return;
                String why = string(msg.get(3));
                if (why == null || msg.get(2).getAsBoolean()) return;
                if (TERMINAL.matcher(why).matches()) {
                    retire(r, why);
                } else {
                    LOG.debug("nostr {}: refused an event ({})", r.url, clean(why));
                }
            }
            case "CLOSED" -> {
                // ["CLOSED", subId, message]: a refused subscription
                String why = msg.size() >= 3 ? string(msg.get(2)) : null;
                if (why == null) return;
                if (TERMINAL.matcher(why).matches()) retire(r, why);
                else LOG.debug("nostr {}: subscription closed ({})", r.url, clean(why));
            }
            default -> {
                // EOSE, NOTICE, AUTH: nothing to do
            }
        }
    }

    /** The topic of an event tagged as ours are, {@code [["x", topic]]} and no more; else null. */
    private static String topicOf(JsonObject event) {
        if (!event.has("tags") || !event.get("tags").isJsonArray()) return null;
        JsonArray tags = event.getAsJsonArray("tags");
        if (tags.size() != 1 || !tags.get(0).isJsonArray()) return null;
        JsonArray tag = tags.get(0).getAsJsonArray();
        if (tag.size() != 2 || !"x".equals(string(tag.get(0)))) return null;
        String topic = string(tag.get(1));
        return topic != null && HEX64.matcher(topic).matches() ? topic : null;
    }

    /**
     * The id NIP-01 gives an event with these fields, as {@link Nostr.Signer}
     * and the shell compute it; null for an event neither would have made.
     * Theirs hold only ASCII that JSON does not escape, so the serialization
     * is plain concatenation, and taking no other kind leaves a relay no
     * second spelling of one event.
     */
    private static String idOf(JsonObject event, String topic) {
        String pubkey = string(event.get("pubkey"));
        String content = string(event.get("content"));
        String createdAt = integer(event.get("created_at"));
        String kind = integer(event.get("kind"));
        if (pubkey == null || content == null || createdAt == null || kind == null) return null;
        if (!HEX64.matcher(pubkey).matches() || !BASE64.matcher(content).matches()) return null;
        String serial = "[0,\"" + pubkey + "\"," + createdAt + "," + kind + ",[[\"x\",\"" + topic + "\"]],\"" + content + "\"]";
        return HexFormat.of().formatHex(Nostr.sha256(serial.getBytes(StandardCharsets.US_ASCII)));
    }

    /** {@code e} as written, if it is a JSON number written as a plain whole one, else null. */
    private static String integer(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        String s = e.getAsString();
        return INTEGER.matcher(s).matches() ? s : null;
    }

    /** Links over {@code java.net.http} WebSockets. */
    static Connector webSockets() {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return (url, events) -> {
            var link = new Link() {
                volatile WebSocket ws;
                volatile boolean closing;
                // A WebSocket takes one send at a time: chain them, without
                // blocking the pool's thread on a slow relay.
                private java.util.concurrent.CompletableFuture<?> sending =
                        java.util.concurrent.CompletableFuture.completedFuture(null);

                @Override
                public synchronized void send(String text) {
                    WebSocket w = ws;
                    if (w == null) return;
                    sending = sending.handle((x, e) -> null)
                            .thenCompose(x -> w.sendText(text, true))
                            .exceptionally(e -> {
                                w.abort();
                                return null;
                            });
                }

                @Override
                public void close() {
                    closing = true;
                    WebSocket w = ws;
                    if (w != null) w.abort();
                }
            };
            http.newWebSocketBuilder()
                    .buildAsync(URI.create(url), new WebSocket.Listener() {
                        private final StringBuilder partial = new StringBuilder();
                        private boolean over;

                        @Override
                        public void onOpen(WebSocket ws) {
                            link.ws = ws;
                            if (link.closing) {
                                ws.abort();
                                return;
                            }
                            events.opened();
                            ws.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            if (over) return null;
                            if (partial.length() + data.length() > MESSAGE_MAX) {
                                // Not kept, and not asked for more: a relay
                                // must not be able to fill the game's heap.
                                over = true;
                                partial.setLength(0);
                                partial.trimToSize();
                                ws.abort();
                                events.closed("a message over " + MESSAGE_MAX + " chars");
                                return null;
                            }
                            partial.append(data);
                            if (!last) {
                                ws.request(1);
                                return null;
                            }
                            String text = partial.toString();
                            partial.setLength(0);
                            // The next frame is asked for once this message is
                            // dealt with, so a relay that sends faster than the
                            // pool's thread works waits in its own socket, not
                            // in that thread's queue.
                            events.text(text).whenComplete((x, e) -> ws.request(1));
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                            if (!over) events.closed(status + " " + reason);
                            return null;
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            if (!over) events.closed(error.toString()); // told once already
                        }
                    })
                    .whenComplete((ws, error) -> {
                        if (error != null) events.closed(error.toString());
                    });
            return link;
        };
    }
}
