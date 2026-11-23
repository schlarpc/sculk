package sculk.core.remote;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP requests from a remote browser, over its data channel, replayed against
 * the game's own control server on loopback (docs/REMOTE.md, "Tunnel"). The
 * browser was approved, so its requests carry the control token, which the
 * game adds; the browser never sees it.
 *
 * <p>Every message is {@code stream (u32) || type (u8) || data}. The browser
 * opens streams; each is one request and its response, and several run at
 * once (the event stream stays open while other requests come and go).
 *
 * <p>Responses are streamed in chunks of at most {@link #CHUNK} bytes, so a
 * server-sent event stream arrives as it happens. {@link Link#send} may block
 * while the channel's queue is full, which pauses reading from the server.
 */
public final class Tunnel {
    private static final Logger LOG = LoggerFactory.getLogger("sculk");

    // Browser to game.
    static final int REQUEST = 1, REQUEST_BODY = 2, REQUEST_END = 3, CANCEL = 7;
    // Game to browser.
    static final int RESPONSE = 4, RESPONSE_BODY = 5, RESPONSE_END = 6, ERROR = 8;

    static final int CHUNK = 16 * 1024;
    /** A request body larger than this is refused. */
    static final int MAX_REQUEST = 8 << 20;
    /** Streams open at once; more are refused. */
    static final int MAX_STREAMS = 64;
    /** Request headers passed on; the rest (cookies, origin, authorization) are not. */
    private static final List<String> REQUEST_HEADERS = List.of("accept", "accept-encoding", "content-type", "last-event-id");
    /**
     * Response headers passed back. A gzipped body crosses the channel as it
     * is, and the shell decodes it, so DTLS and SCTP carry a fraction of the bytes.
     */
    private static final List<String> RESPONSE_HEADERS = List.of("content-type", "content-encoding", "cache-control");
    /** A {@code %} must begin an escape, or {@link URI#resolve(String)} would throw. */
    private static final Pattern PATH = Pattern.compile("/(?:[A-Za-z0-9._~!$&'()*+,;=:@/?-]|%[0-9A-Fa-f]{2})*");

    /** Where messages to the browser go. */
    public interface Link {
        /** Sends one message; may block while the channel is full. */
        void send(byte[] message) throws InterruptedException;
    }

    /** The control server to replay requests against, or null while it is not running. */
    public interface Upstream {
        URI base();

        String token();
    }

    private final Link link;
    private final java.util.function.Supplier<Upstream> upstream;
    private final ExecutorService readers;
    /**
     * No proxy and no redirects: the request carries the control token, which
     * goes to the control server and nowhere else, whatever the JVM's proxy
     * properties say.
     */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .proxy(HttpClient.Builder.NO_PROXY)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final Map<Integer, Stream> streams = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private final class Stream {
        final int id;
        String method;
        String path;
        final Map<String, String> headers = new java.util.HashMap<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        volatile boolean cancelled;
        volatile CompletableFuture<?> pending;
        volatile InputStream reading;

        Stream(int id) {
            this.id = id;
        }
    }

    /**
     * @param upstream the control server, asked for each request (it may
     *     start, stop or move while the browser is connected)
     * @param readers runs the threads that read responses
     */
    public Tunnel(Link link, java.util.function.Supplier<Upstream> upstream, ExecutorService readers) {
        this.link = link;
        this.upstream = upstream;
        this.readers = readers;
    }

    static byte[] message(int stream, int type, byte[] data, int off, int len) {
        ByteBuffer b = ByteBuffer.allocate(5 + len);
        b.putInt(stream).put((byte) type).put(data, off, len);
        return b.array();
    }

    static byte[] message(int stream, int type, byte[] data) {
        return message(stream, type, data, 0, data.length);
    }

    /** A message from the browser. Never blocks. */
    public void receive(byte[] m) {
        if (closed || m.length < 5) return;
        ByteBuffer b = ByteBuffer.wrap(m);
        int id = b.getInt();
        int type = b.get() & 0xff;
        byte[] data = Arrays.copyOfRange(m, 5, m.length);
        switch (type) {
            case REQUEST -> {
                if (streams.containsKey(id)) return;
                if (streams.size() >= MAX_STREAMS) {
                    fail(id, "too many requests at once");
                    return;
                }
                Stream s = new Stream(id);
                try {
                    JsonObject req = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
                    s.method = req.get("method").getAsString();
                    s.path = req.get("path").getAsString();
                    if (req.has("headers")) {
                        for (var e : req.getAsJsonObject("headers").entrySet()) {
                            String k = e.getKey().toLowerCase(java.util.Locale.ROOT);
                            if (REQUEST_HEADERS.contains(k)) s.headers.put(k, e.getValue().getAsString());
                        }
                    }
                } catch (RuntimeException e) {
                    fail(id, "bad request");
                    return;
                }
                if (!List.of("GET", "POST", "PUT", "DELETE").contains(s.method) || !PATH.matcher(s.path).matches()
                        || s.path.startsWith("//")) {
                    fail(id, "bad request");
                    return;
                }
                streams.put(id, s);
            }
            case REQUEST_BODY -> {
                Stream s = streams.get(id);
                if (s == null) return;
                if (s.body.size() + data.length > MAX_REQUEST) {
                    streams.remove(id, s);
                    fail(id, "request too large");
                    return;
                }
                s.body.writeBytes(data);
            }
            case REQUEST_END -> {
                Stream s = streams.get(id);
                if (s != null) start(s);
            }
            case CANCEL -> {
                Stream s = streams.remove(id);
                if (s != null) cancel(s);
            }
            default -> {
                // Unknown: ignored, so a newer browser can try things an older game lacks.
            }
        }
    }

    private void start(Stream s) {
        Upstream up = upstream.get();
        if (up == null) {
            forget(s);
            fail(s.id, "the control server is off (its control setting), so there is nothing to show");
            return;
        }
        HttpRequest request;
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(up.base().resolve(s.path))
                    .header("Authorization", "Bearer " + up.token());
            s.headers.forEach((k, v) -> req.header(k, v));
            byte[] body = s.body.toByteArray();
            req.method(s.method, body.length == 0 && !s.method.equals("POST")
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            request = req.build();
        } catch (RuntimeException e) {
            // What the client will not send (a header value with a line break in
            // it, say). Thrown from here it would leave the stream open for good.
            forget(s);
            fail(s.id, "bad request");
            return;
        }
        s.pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        forget(s);
                        if (!stopped(s)) fail(s.id, "the control server did not answer: " + error.getMessage());
                        return;
                    }
                    if (stopped(s)) {
                        forget(s);
                        close(response.body());
                        return;
                    }
                    readers.execute(() -> relay(s, response));
                });
    }

    /** Sends the response, then its body chunk by chunk as the server writes it. */
    private void relay(Stream s, HttpResponse<InputStream> response) {
        InputStream in = response.body();
        s.reading = in;
        try {
            JsonObject head = new JsonObject();
            head.addProperty("status", response.statusCode());
            JsonObject headers = new JsonObject();
            for (String h : RESPONSE_HEADERS) response.headers().firstValue(h).ifPresent(v -> headers.addProperty(h, v));
            head.add("headers", headers);
            link.send(message(s.id, RESPONSE, head.toString().getBytes(StandardCharsets.UTF_8)));
            byte[] buf = new byte[CHUNK];
            int n;
            while (!stopped(s) && (n = in.read(buf)) >= 0) {
                if (n > 0) link.send(message(s.id, RESPONSE_BODY, buf, 0, n));
            }
            if (!stopped(s)) link.send(message(s.id, RESPONSE_END, new byte[0]));
        } catch (IOException e) {
            if (!stopped(s)) fail(s.id, "the control server stopped answering");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            forget(s);
            close(in);
        }
    }

    /**
     * Removes this stream and no other: once it is cancelled the browser may
     * open another under the same id, which a late removal by id would take
     * out of the count, and out of reach of CANCEL and {@link #close}.
     */
    private void forget(Stream s) {
        streams.remove(s.id, s);
    }

    /** Cancelled, or the tunnel closed (which may miss a stream that was added as it closed). */
    private boolean stopped(Stream s) {
        return s.cancelled || closed;
    }

    private void cancel(Stream s) {
        s.cancelled = true;
        CompletableFuture<?> f = s.pending;
        if (f != null) f.cancel(true);
        close(s.reading);
    }

    private static void close(InputStream in) {
        if (in == null) return;
        try {
            in.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    private void fail(int id, String why) {
        try {
            link.send(message(id, ERROR, why.getBytes(StandardCharsets.UTF_8)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.debug("remote tunnel: stream {}: {}", id, why);
    }

    /** The browser went away: stop every request. */
    public void close() {
        closed = true;
        for (Stream s : streams.values()) cancel(s);
        streams.clear();
    }
}
