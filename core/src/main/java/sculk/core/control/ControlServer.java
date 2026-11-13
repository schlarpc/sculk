package sculk.core.control;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import sculk.core.Sculk;
import sculk.core.log.EventLog;
import sculk.core.net.SculkConnection;
import sculk.core.util.Bytes;

/**
 * The administrative HTTP server: query and tail the event log, evaluate
 * script code in the live script scope, reload scripts.
 *
 * <p>Whoever can call this server can run code in the game process and inject
 * packets, so it is off unless {@code -Dsculk.control=<port>} is set, and:
 * <ul>
 *   <li>it listens on the loopback address only;</li>
 *   <li>every request needs {@code Authorization: Bearer <token>}. The token
 *       is random per launch and written, with the port, to a file only the
 *       user can read ({@code sculk-control.json} in the working directory,
 *       or {@code -Dsculk.controlFile});</li>
 *   <li>the {@code Host} header must name the loopback address and this port,
 *       which stops DNS rebinding, and a request with an {@code Origin}
 *       header is refused unless the origin is this server, which stops
 *       cross-site requests from a browser;</li>
 *   <li>anything that changes state is a POST.</li>
 * </ul>
 * It must never share a listener with anything a test target can reach.
 *
 * <pre>
 *   GET  /status
 *   GET  /events?filter=&amp;after=&amp;limit=      JSON Lines; see {@link EventFilter}
 *   GET  /events/stream?filter=&amp;after=      the same as server-sent events, live
 *   GET  /frame?id=                         the whole frame of a packet event
 *   POST /eval      body: JavaScript        {"ok":true,"result":...}
 *   POST /reload                            drops all script state, loads the startup scripts again
 *   POST /mcp       body: JSON-RPC          Model Context Protocol (Streamable HTTP); see {@link McpHandler}
 *   GET  /ui/...                            the web UI (static files from sculk/web/ on the classpath)
 * </pre>
 *
 * The web UI's static files are the only thing served without the token:
 * they hold no secrets, and a page can only load them when its Host header
 * passes. The page asks for the token (or reads it from {@code #token=}) and
 * sends it with every API call.
 */
public final class ControlServer {
    private static final int MAX_BODY = 4 << 20;
    private static final String WEB_ROOT = "/sculk/web/";
    private static final java.util.regex.Pattern ASSET = java.util.regex.Pattern.compile("[A-Za-z0-9_-][A-Za-z0-9._-]*");
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "woff2", "font/woff2",
            "map", "application/json; charset=utf-8",
            "txt", "text/plain; charset=utf-8");
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final Sculk engine;
    private final EventStore store;
    private final HttpServer server;
    private final String token;
    private final Set<String> hosts;
    private final Path file;
    private final McpHandler mcp;

    /**
     * @param token the bearer token to require, or null for a random one. A
     *              fixed token lets MCP clients keep a static configuration.
     */
    public ControlServer(Sculk engine, EventStore store, int port, Path file, String token) throws IOException {
        this.engine = engine;
        this.store = store;
        this.file = file.toAbsolutePath();
        this.mcp = new McpHandler(engine, store);
        if (token != null && token.length() < 16) throw new IllegalArgumentException("control token must be at least 16 characters");
        if (token == null) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            token = Bytes.hex(random);
        }
        this.token = token;

        // 127.0.0.1 explicitly: getLoopbackAddress() is ::1 under
        // -Djava.net.preferIPv6Addresses=system, which Forge and NeoForge dev runs set.
        InetAddress loopback = InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1});
        server = HttpServer.create(new InetSocketAddress(loopback, port), 16);
        AtomicInteger threads = new AtomicInteger();
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sculk-control-" + threads.incrementAndGet());
            t.setDaemon(true);
            return t;
        }));
        int bound = server.getAddress().getPort();
        hosts = Set.of("127.0.0.1:" + bound, "localhost:" + bound);
        server.createContext("/", this::handle);
        server.start();
        writeFile(bound);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Files.deleteIfExists(this.file);
            } catch (IOException ignored) {
                // Best effort.
            }
        }, "sculk-control-cleanup"));
        Sculk.LOG.info("control server on http://127.0.0.1:{} (token in {})", bound, this.file);
    }

    public int port() { return server.getAddress().getPort(); }
    public String token() { return token; }
    public Path file() { return file; }

    public void stop() {
        server.stop(0);
    }

    /** Writes port and token where only the user can read them. */
    private void writeFile(int port) throws IOException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("url", "http://127.0.0.1:" + port);
        info.put("mcp", "http://127.0.0.1:" + port + "/mcp");
        info.put("port", port);
        info.put("token", token);
        info.put("pid", ProcessHandle.current().pid());
        info.put("platform", engine.platform().describe());
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system: restrict to the owner as far as Java can.
            Files.createFile(file);
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
            file.toFile().setWritable(false, false);
            file.toFile().setWritable(true, true);
        }
        Files.writeString(file, EventLog.toJson(info) + "\n");
    }

    // ---- request handling ----

    private void handle(HttpExchange ex) {
        try (ex) {
            String path = ex.getRequestURI().getPath();
            String refusal = refuseCaller(ex);
            if (refusal == null && ex.getRequestMethod().equals("GET") && (path.equals("/") || path.startsWith("/ui/"))) {
                serveStatic(ex, path);
                return;
            }
            if (refusal == null) refusal = refuseToken(ex);
            if (refusal != null) {
                int status = refusal.startsWith("401") ? 401 : 403;
                if (status == 401) ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                json(ex, status, Map.of("error", refusal.substring(4)));
                return;
            }
            String method = ex.getRequestMethod();
            Map<String, String> query = query(ex.getRequestURI().getRawQuery());
            try {
                switch (method + " " + path) {
                    case "GET /status" -> json(ex, 200, status(engine, store));
                    case "GET /events" -> events(ex, query);
                    case "GET /events/stream" -> stream(ex, query);
                    case "GET /frame" -> frame(ex, query);
                    case "POST /eval" -> json(ex, 200, engine.scripts().evalForRepl(body(ex)));
                    case "POST /reload" -> json(ex, 200, engine.reloadScripts());
                    case "POST /mcp" -> {
                        Map<String, Object> response = mcp.handle(body(ex));
                        if (response == null) ex.sendResponseHeaders(202, -1);
                        else json(ex, 200, response);
                    }
                    default -> {
                        boolean known = Set.of("/status", "/events", "/events/stream", "/frame", "/eval", "/reload", "/mcp").contains(path);
                        json(ex, known ? 405 : 404, Map.of("error", known ? "wrong method for " + path : "no such endpoint " + path));
                    }
                }
            } catch (IllegalArgumentException e) {
                json(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
            }
        } catch (IOException e) {
            // The caller went away.
        } catch (RuntimeException e) {
            Sculk.LOG.warn("control request failed", e);
        }
    }

    /** Returns "403 reason" for a caller that must not be served at all, or null. */
    private String refuseCaller(HttpExchange ex) {
        if (!ex.getRemoteAddress().getAddress().isLoopbackAddress()) return "403 not a loopback caller";
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || !hosts.contains(host.toLowerCase(java.util.Locale.ROOT))) return "403 bad Host header";
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !(origin.startsWith("http://") && hosts.contains(origin.substring(7)))) {
            return "403 cross-origin request";
        }
        return null;
    }

    /** Returns "401 reason" for a request without the right token, or null. */
    private String refuseToken(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        byte[] given = (auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : "").getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, token.getBytes(StandardCharsets.UTF_8))) return "401 missing or wrong bearer token";
        return null;
    }

    static Map<String, Object> status(Sculk engine, EventStore store) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("platform", engine.platform().describe());
        out.put("pid", ProcessHandle.current().pid());
        out.put("events", store.size());
        out.put("lastEventId", engine.lastEventId());
        out.put("scripts", engine.scriptPaths());
        List<Map<String, Object>> conns = new java.util.ArrayList<>();
        for (SculkConnection c : engine.connections()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("side", c.getSide());
            m.put("remote", String.valueOf(c.getRemoteAddress()));
            m.put("open", c.isOpen());
            conns.add(m);
        }
        out.put("connections", conns);
        return out;
    }

    private void events(HttpExchange ex, Map<String, String> query) throws IOException {
        Predicate<Map<String, Object>> filter = EventFilter.compile(query.get("filter"));
        Long after = query.containsKey("after") ? Long.valueOf(query.get("after")) : null;
        int limit = Integer.parseInt(query.getOrDefault("limit", "200"));
        StringBuilder sb = new StringBuilder();
        for (StoredEvent e : store.query(filter, after, limit)) sb.append(e.json()).append('\n');
        send(ex, 200, "application/x-ndjson", sb.toString());
    }

    private void frame(HttpExchange ex, Map<String, String> query) throws IOException {
        if (!query.containsKey("id")) throw new IllegalArgumentException("id is required");
        StoredEvent e = store.get(Long.parseLong(query.get("id")));
        if (e == null || e.frame() == null) {
            json(ex, 404, Map.of("error", e == null ? "no such event (it may have been dropped)" : "event has no frame"));
            return;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.id());
        out.put("len", e.frame().length);
        out.put("hex", Bytes.hex(e.frame()));
        json(ex, 200, out);
    }

    /** Server-sent events: matching events as they happen, after any backlog asked for with {@code after}. */
    private void stream(HttpExchange ex, Map<String, String> query) throws IOException {
        Predicate<Map<String, Object>> filter = EventFilter.compile(query.get("filter"));
        Long after = query.containsKey("after") ? Long.valueOf(query.get("after")) : null;
        BlockingQueue<StoredEvent> queue = new LinkedBlockingQueue<>(10_000);
        Consumer<StoredEvent> subscriber = e -> {
            if (filter.test(e.fields())) queue.offer(e);
        };
        store.subscribe(subscriber);
        try {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            long last = -1;
            if (after != null) {
                for (StoredEvent e : store.query(filter, after, Integer.MAX_VALUE)) {
                    out.write(("id: " + e.id() + "\ndata: " + e.json() + "\n\n").getBytes(StandardCharsets.UTF_8));
                    last = e.id();
                }
            }
            out.flush();
            while (true) {
                StoredEvent e = queue.poll(10, TimeUnit.SECONDS);
                if (e == null) out.write(": keepalive\n\n".getBytes(StandardCharsets.UTF_8));
                else if (e.id() > last) out.write(("id: " + e.id() + "\ndata: " + e.json() + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            store.unsubscribe(subscriber);
        }
    }

    /** Serves the web UI from the classpath; GET / redirects to it. */
    private void serveStatic(HttpExchange ex, String path) throws IOException {
        if (path.equals("/") || path.equals("/ui")) {
            ex.getResponseHeaders().set("Location", "/ui/");
            ex.sendResponseHeaders(302, -1);
            return;
        }
        String name = path.substring("/ui/".length());
        if (name.isEmpty()) name = "index.html";
        byte[] bytes = null;
        if (ASSET.matcher(name).matches()) {
            try (InputStream in = ControlServer.class.getResourceAsStream(WEB_ROOT + name)) {
                if (in != null) bytes = in.readAllBytes();
            }
        }
        if (bytes == null) {
            boolean built = ControlServer.class.getResource(WEB_ROOT + "index.html") != null;
            send(ex, 404, "text/plain", built ? "not found\n" : "this build of sculk has no web UI (see web/ in the sculk repository)\n");
            return;
        }
        String ext = name.substring(name.lastIndexOf('.') + 1);
        var headers = ex.getResponseHeaders();
        headers.set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
        headers.set("Cache-Control", "no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Content-Security-Policy", CSP);
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cross-Origin-Opener-Policy", "same-origin");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        ex.sendResponseHeaders(200, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
    }

    // ---- plumbing ----

    private static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) throw new IllegalArgumentException("request body too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    private static void json(HttpExchange ex, int status, Object value) throws IOException {
        send(ex, status, "application/json", EventLog.toJson(value) + "\n");
    }

    private static void send(HttpExchange ex, int status, String type, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) ex.getResponseBody().write(bytes);
    }
}
