package sculk.core.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** One connection; the real one is a WebSocket, tests use a fake relay. */
    interface Link {
        void send(String text);

        void close();
    }

    /** What a link reports, on any thread. */
    interface LinkEvents {
        void opened();

        void text(String text);

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
        public void text(String text) {
            run(() -> {
                if (active.get(url) == this) receive(this, text);
            });
        }

        @Override
        public void closed(String why) {
            run(() -> {
                if (active.get(url) != this) return;
                open = false;
                link = null;
                if (closed) return;
                LOG.debug("nostr {}: closed ({}); retrying in {} ms", url, why, backoff);
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
        LOG.info("nostr {}: dropped ({})", r.url, why);
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

    private void receive(Relay r, String text) {
        JsonArray msg;
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonArray() || parsed.getAsJsonArray().isEmpty()) return;
            msg = parsed.getAsJsonArray();
        } catch (RuntimeException e) {
            return; // a relay talking nonsense is ignored, not fatal
        }
        String type = msg.get(0).isJsonPrimitive() ? msg.get(0).getAsString() : "";
        switch (type) {
            case "EVENT" -> {
                if (msg.size() < 3 || !msg.get(2).isJsonObject()) return;
                JsonObject event = msg.get(2).getAsJsonObject();
                String topic = topicOf(event);
                if (topic == null || !topics.contains(topic) || !msg.get(1).getAsString().equals(subId(topic))) return;
                if (!event.has("id") || !event.get("id").isJsonPrimitive()) return;
                String id = event.get("id").getAsString();
                if (seen.contains(id)) return;
                remember(id);
                onEvent.accept(event);
            }
            case "OK" -> {
                // ["OK", id, accepted, message]
                if (msg.size() >= 4 && !msg.get(2).getAsBoolean()) {
                    String why = msg.get(3).getAsString();
                    if (TERMINAL.matcher(why).matches()) {
                        retire(r, why);
                    } else {
                        LOG.debug("nostr {}: refused an event ({})", r.url, why);
                    }
                }
            }
            case "CLOSED" -> {
                // ["CLOSED", subId, message]: a refused subscription
                String why = msg.size() >= 3 ? msg.get(2).getAsString() : "";
                if (TERMINAL.matcher(why).matches()) retire(r, why);
                else LOG.debug("nostr {}: subscription closed ({})", r.url, why);
            }
            default -> {
                // EOSE, NOTICE, AUTH: nothing to do
            }
        }
    }

    private static String topicOf(JsonObject event) {
        if (!event.has("tags") || !event.get("tags").isJsonArray()) return null;
        for (JsonElement t : event.getAsJsonArray("tags")) {
            if (t.isJsonArray() && t.getAsJsonArray().size() >= 2
                    && "x".equals(t.getAsJsonArray().get(0).getAsString())) {
                return t.getAsJsonArray().get(1).getAsString();
            }
        }
        return null;
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
                            partial.append(data);
                            if (last) {
                                String text = partial.toString();
                                partial.setLength(0);
                                events.text(text);
                            }
                            ws.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
                            events.closed(status + " " + reason);
                            return null;
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            events.closed(error.toString());
                        }
                    })
                    .whenComplete((ws, error) -> {
                        if (error != null) events.closed(error.toString());
                    });
            return link;
        };
    }
}
