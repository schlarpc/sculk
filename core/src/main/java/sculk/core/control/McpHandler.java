package sculk.core.control;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import sculk.core.Sculk;
import sculk.core.log.EventLog;
import sculk.core.script.ScriptManager;
import sculk.core.util.Bytes;

/**
 * A Model Context Protocol server over the control server's HTTP listener
 * ({@code POST /mcp}, the Streamable HTTP transport, answering with plain
 * JSON rather than a stream). It shares the control server's Host and
 * Origin checks, and gives an agent the same powers as the HTTP API: query
 * and wait for events, read frames, evaluate script code, and read, write,
 * edit, run and manage installed scripts.
 *
 * <p>A client gets in either with the control token, or by pairing: it
 * connects without one, and its session waits until the player approves it
 * in game ({@code /sculk mcp accept}). A waiting session can initialize and
 * list tools, but each tool call answers with what to type instead of
 * running. Approvals last until the game exits; the client's configuration
 * then needs only a fixed port, and never changes.
 */
final class McpHandler {
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final Gson GSON = new Gson();
    private static final int MAX_WAIT_MILLIS = 300_000;
    /** How often a streamed call reports progress (or, without a progress token, sends a keep-alive). */
    static final long TICK_MILLIS = 2_000;
    private static final int MAX_SESSIONS = 256;
    /** Waiting sessions are kept apart, and few, so callers without a token cannot push out approved ones. */
    private static final int MAX_PENDING = 8;

    static final String SCRIPT_HELP = """
            Scripts are JavaScript (Rhino, ES6: arrow functions, let/const, template strings, classes; no modules, \
            no async/await) files in the game's script directory. Each running script has its own top-level scope \
            and owns the listeners and timers it registers, so stopping or restarting one leaves the others alone. \
            A script can begin with a metadata block:
            // ==Sculk==
            // @name        Short title
            // @description What it does
            // @run         startup   (startup: runs at launch and on reload, when enabled; manual: only when run)
            // @dist        client    (optional; client or server: only in a game client, or only in a dedicated server)
            // ==/Sculk==
            The `sculk` global offers: on('packet'|'connection', fn), connections(), connection(id), payloadTypes(), \
            emit(obj) (a `script` event), log(msg) (a `script_log` event), chat(msg) (a chat line only the player sees; \
            a `chat` event), schedule(ms, fn), tree(obj), \
            with(record, field, value), hex(bytes), unhex(str), copy(bytes), scope(name) (another running script's \
            globals), name, dist. Connections offer inject(dir, bytes), injectPacket(dir, obj), \
            injectPayload(dir, channel, payload) (a mod payload object, e.g. an edited e.mod.data), replay(event). \
            Packet events have the same fields and shorthands as logged events (e.mod.channel, e.frame.dir, e.owner), \
            plus e.frame.bytes, e.packet.object, e.mod.data as live objects and e.conn as the live connection, and \
            offer drop(), replaceFrame(bytes), replacePacket(obj), replacePayload(payload) (same channel). \
            e.mod.data trees show NBT as JSON, item stacks as {item, count, components or tag}, identifiers, \
            registry entries and holders as "ns:path", positions as {x, y, z}, text components as JSON text; \
            the live objects are the game's own (e.g. e.mod.data.tag().copy()).""";

    private static final String FILTER_HELP = """
            Filter syntax (Wireshark-style; blank matches everything): \
            `kind == packet && dir == inbound && owner != minecraft`, \
            `packet.type matches "recipe|chat" and len > 1000`, `mod.data contains "../"`, \
            `mod.data.pos.x >= 100`, `mod && action != pass`. \
            Fields are event keys; dots walk into nested objects and lists (a list matches if any element does). \
            Operators: == != < <= > >= contains matches (~ is matches, a regex found anywhere). \
            A field alone means present and not false, so `mod` alone means "is a custom payload". \
            Combine with && || ! or and/or/not and parentheses. \
            Event kinds: start, connection, disconnect, payload_type, packet, eval, script (sculk.emit), \
            script_log (sculk.log), script_error, script_run, script_stop, script_file (saved, renamed, ...), script_reload; \
            script events carry `script` (the script's name, or "console"). \
            Packet events are layered: id, kind, t, injected, action (pass/drop/replace); \
            conn {id, side (client/server), remote}; frame {seq, dir (inbound/outbound), len, hex, spans: \
            [{field, off, len}] saying which bytes hold packet.id, packet.body, mod.channel, mod.data}; \
            packet {id, state, flow, type, javaClass, owner}; and for custom payloads only \
            mod {channel, owner (mod id), registeredVia, javaClass, roundTrip, data (decoded tree)}. \
            connection and disconnect events have conn; payload_type events have mod. \
            Shorthands: dir, len, side, type, channel, and owner (mod.owner, else packet.owner).""";


    private final Sculk engine;
    private final EventStore store;

    private final McpStateless stateless = new McpStateless(this);

    McpHandler(Sculk engine, EventStore store) {
        this.engine = engine;
        this.store = store;
    }

    /** The first version whose Streamable HTTP transport drops JSON-RPC batches. */
    private static final String NO_BATCHES = "2025-06-18";
    /** What a client that sends no MCP-Protocol-Version header speaks, per the spec. */
    private static final String DEFAULT_VERSION = "2025-03-26";

    /**
     * Handles one POST body: a JSON-RPC message, or (for protocol versions
     * before 2025-06-18) a batch of them. {@code version} is the
     * MCP-Protocol-Version header, already checked by {@link #checkVersion}.
     * Returns the response (an object, a list for a batch), or null when
     * there is nothing to answer.
     */
    Object handle(String body, String version) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (JsonParseException e) {
            return error(null, -32700, "parse error: " + e.getMessage());
        }
        if (parsed.isJsonArray()) {
            String v = version == null ? DEFAULT_VERSION : version;
            if (v.compareTo(NO_BATCHES) >= 0) return error(null, -32600, "JSON-RPC batches are not part of MCP " + v);
            if (parsed.getAsJsonArray().isEmpty()) return error(null, -32600, "empty batch");
            List<Object> out = new ArrayList<>();
            for (JsonElement e : parsed.getAsJsonArray()) {
                Map<String, Object> r = message(e, Call.NONE);
                if (r != null) out.add(r);
            }
            return out.isEmpty() ? null : out;
        }
        return message(parsed, Call.NONE);
    }

    // ---- the HTTP side: sessions, streamed calls, cancellation ----

    /**
     * One client's session: its calls in flight, by request id, so
     * notifications/cancelled can find them. A paired session has a short
     * number for the player to type, and the name the client gave itself.
     */
    private static final class Session {
        final Map<Object, Call> calls = new java.util.concurrent.ConcurrentHashMap<>();
        final int number;
        final String client;
        final long since = System.currentTimeMillis();
        volatile boolean approved;

        Session(int number, String client, boolean approved) {
            this.number = number;
            this.client = client;
            this.approved = approved;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("number", number);
            m.put("client", client);
            m.put("since", since);
            m.put("approved", approved);
            return m;
        }
    }

    /** Clients with the token that never initialized (or skip sessions) share this one. */
    private final Session anonymous = new Session(0, "", true);
    private final java.util.concurrent.atomic.AtomicInteger numbers = new java.util.concurrent.atomic.AtomicInteger();
    /** Paired sessions waiting for the player, oldest first. */
    private final Map<String, Session> pending = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
                    return size() > MAX_PENDING;
                }
            });
    private final Map<String, Session> sessions = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Session> eldest) {
                    return size() > MAX_SESSIONS;
                }
            });

    /**
     * One tools/call in flight. It may answer as a server-sent event stream:
     * the stream opens on the first progress tick, carries
     * notifications/progress (when the client sent a progressToken) or
     * keep-alives, and ends with the response. A cancelled call, by
     * notifications/cancelled or by the client going away, stops at its next
     * tick and sends no response.
     */
    static final class Call {
        static final Call NONE = new Call(null, null);

        private final HttpExchange exchange;
        private final Object progressToken;
        private volatile boolean cancelled;
        private OutputStream stream;

        Call(HttpExchange exchange, Object progressToken) {
            this.exchange = exchange;
            this.progressToken = progressToken;
        }

        boolean cancelled() {
            return cancelled;
        }

        void cancel() {
            cancelled = true;
        }

        boolean streaming() {
            return stream != null;
        }

        /** Reports progress, opening the stream if needed. Without a progress token it only keeps the stream alive. */
        synchronized void progress(double progress, double total, String message) {
            if (exchange == null || cancelled) return;
            try {
                open();
                if (progressToken == null) {
                    write(": keep-alive\n\n");
                } else {
                    Map<String, Object> params = new LinkedHashMap<>();
                    params.put("progressToken", progressToken);
                    params.put("progress", progress);
                    params.put("total", total);
                    params.put("message", message);
                    send(Map.of("jsonrpc", "2.0", "method", "notifications/progress", "params", params));
                }
            } catch (IOException e) {
                cancelled = true; // The client went away; nobody will read the answer.
            }
        }

        synchronized void send(Object message) throws IOException {
            open();
            write("event: message\ndata: " + EventLog.toJson(message) + "\n\n");
        }

        private void open() throws IOException {
            if (stream != null) return;
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, 0);
            stream = exchange.getResponseBody();
        }

        private void write(String text) throws IOException {
            stream.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stream.flush();
        }
    }

    /**
     * Serves POST /mcp. Sessions are optional: initialize hands out an
     * Mcp-Session-Id, and a request naming an unknown one gets 404 (the
     * client must initialize again). tools/call may stream its answer when
     * the client accepts text/event-stream. {@code hasToken} says whether the
     * request carried the control token; without it, only a session (or
     * an initialize, which starts one waiting for the player) gets in.
     */
    void post(HttpExchange ex, String body, String version, boolean hasToken) throws IOException {
        JsonElement parsedBody = null;
        try {
            parsedBody = JsonParser.parseString(body);
        } catch (JsonParseException e) {
            // Answered below, in whichever era the headers say.
        }
        if (McpStateless.isModern(version, parsedBody != null && parsedBody.isJsonObject() ? parsedBody.getAsJsonObject() : null)) {
            if (parsedBody == null && hasToken) {
                ControlServer.json(ex, 400, error(null, -32700, "parse error"));
                return;
            }
            stateless.post(ex, parsedBody, hasToken);
            return;
        }
        String bad = checkVersion(version);
        if (bad != null) {
            ControlServer.json(ex, 400, error(null, -32600, bad));
            return;
        }
        String sessionId = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        Session session = hasToken ? anonymous : null;
        if (sessionId != null) {
            session = session(sessionId);
            if (session == null) {
                ControlServer.json(ex, 404, error(null, -32001, "unknown or expired session " + sessionId + "; initialize again"));
                return;
            }
        }
        JsonObject msg = null;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) msg = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            // handle() answers it.
        }
        String method = msg != null && msg.has("method") && msg.get("method").isJsonPrimitive() ? msg.get("method").getAsString() : null;
        Object id = msg != null && msg.has("id") && !msg.get("id").isJsonNull() ? requestId(msg.get("id")) : null;

        if ("notifications/cancelled".equals(method) && msg.has("params") && msg.get("params").isJsonObject()) {
            JsonObject p = msg.getAsJsonObject("params");
            if (p.has("requestId")) {
                Call c = session.calls.get(requestId(p.get("requestId")));
                if (c != null) c.cancel();
            }
            ex.sendResponseHeaders(202, -1);
            return;
        }
        if ("initialize".equals(method) && id != null) {
            String newId = newSessionId();
            Session created;
            if (hasToken) {
                created = new Session(0, clientName(msg), true);
                sessions.put(newId, created);
            } else {
                created = waitFor(newId, clientName(msg));
            }
            Object response = handle(body, version);
            if (!created.approved && response instanceof Map<?, ?> m && m.get("result") instanceof Map<?, ?> r) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) r;
                result.put("instructions", result.get("instructions") + " " + waiting(created));
            }
            ex.getResponseHeaders().set("Mcp-Session-Id", newId);
            ControlServer.json(ex, 200, response);
            return;
        }
        if (session == null) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            ControlServer.json(ex, 401, error(id, -32001,
                    "send the control token, or initialize to ask the player to approve this client"));
            return;
        }
        boolean waiting = !hasToken && !session.approved;
        if (waiting && msg == null) {
            // A batch could carry a tool call past the check below.
            ControlServer.json(ex, 200, error(null, -32600, "a client waiting for approval sends one message at a time"));
            return;
        }
        if ("tools/call".equals(method) && id != null) {
            String accept = String.valueOf(ex.getRequestHeaders().getFirst("Accept"));
            JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
            Object token = params.has("_meta") && params.get("_meta").isJsonObject() && params.getAsJsonObject("_meta").has("progressToken")
                    ? requestId(params.getAsJsonObject("_meta").get("progressToken")) : null;
            Call call = new Call(accept.contains("text/event-stream") ? ex : null, token);
            if (session.calls.putIfAbsent(id, call) != null) {
                ControlServer.json(ex, 200, error(id, -32600, "request id " + id + " is already in flight"));
                return;
            }
            try {
                Map<String, Object> response;
                try {
                    if (waiting && !awaitApproval(sessionId, session, call)) {
                        response = new LinkedHashMap<>();
                        response.put("jsonrpc", "2.0");
                        response.put("id", id);
                        response.put("result", text(session(sessionId) == null
                                ? "This MCP client's request to use sculk was turned away; initialize again to ask the player anew."
                                : waiting(session), true));
                    } else {
                        response = message(msg, call);
                    }
                } catch (java.util.concurrent.CancellationException e) {
                    response = null;
                }
                synchronized (call) {
                    if (call.streaming()) {
                        // Cancelled: end the stream without an answer, as the spec asks.
                        if (response != null && !call.cancelled()) call.send(response);
                    } else {
                        ControlServer.json(ex, 200, response != null ? response : error(id, -32800, "request cancelled"));
                    }
                }
            } finally {
                session.calls.remove(id, call);
            }
            return;
        }
        Object response = handle(body, version);
        if (response == null) ex.sendResponseHeaders(202, -1);
        else ControlServer.json(ex, 200, response);
    }

    /** Serves DELETE /mcp: ends a session and cancels its calls. */
    void delete(HttpExchange ex) throws IOException {
        String sessionId = ex.getRequestHeaders().getFirst("Mcp-Session-Id");
        if (sessionId == null) {
            ControlServer.json(ex, 400, Map.of("error", "Mcp-Session-Id is required"));
            return;
        }
        Session session = sessions.remove(sessionId);
        if (session == null) session = pending.remove(sessionId);
        if (session == null) {
            ControlServer.json(ex, 404, Map.of("error", "unknown or expired session"));
            return;
        }
        session.calls.values().forEach(Call::cancel);
        ex.sendResponseHeaders(204, -1);
    }

    private Session session(String id) {
        Session s = sessions.get(id);
        return s != null ? s : pending.get(id);
    }

    /** How long a waiting client's tool call is held for the player before it gives up; tests shorten it. */
    static volatile long approvalWaitMillis = MAX_WAIT_MILLIS;

    /**
     * Holds a waiting client's tool call until the player approves it, so
     * the call simply runs once they do and the model need not be told to
     * try again. Streams progress (the command to type) meanwhile. Returns
     * true once approved; false when the wait ran out or the session was
     * revoked or pushed out. Throws CancellationException if the client
     * cancelled the call.
     */
    private boolean awaitApproval(String sessionId, Session session, Call call) {
        long start = System.currentTimeMillis(), deadline = start + approvalWaitMillis, nextTick = start;
        String note = "waiting for the player to approve this client: /sculk mcp accept " + session.number;
        while (!session.approved) {
            if (session(sessionId) != session) return false; // revoked or pushed out (revoking also cancels: say why)
            if (call.cancelled()) throw new java.util.concurrent.CancellationException();
            long now = System.currentTimeMillis();
            if (now >= deadline) return false;
            if (now >= nextTick) {
                call.progress(now - start, deadline - start, note);
                nextTick = now + TICK_MILLIS;
            }
            try {
                Thread.sleep(Math.min(100, deadline - now));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** What a waiting client is told, for its model to pass on. */
    private static String waiting(Session s) {
        return "This MCP client is waiting for the player's approval, so sculk's tools do not run yet. "
                + "Ask the user to type `/sculk mcp accept " + s.number + "` in Minecraft (or at the server console). "
                + "A tool call made meanwhile waits for the approval (up to " + approvalWaitMillis / 1000
                + " s) and then runs, so there is no need to call again unless it gave up.";
    }

    /** The name and version the client gave in initialize, cut to something fit for chat. */
    private static String clientName(JsonObject msg) {
        String name = "unnamed client";
        try {
            JsonObject info = msg.getAsJsonObject("params").getAsJsonObject("clientInfo");
            name = info.get("name").getAsString();
            if (info.has("version")) name += " " + info.get("version").getAsString();
        } catch (RuntimeException e) {
            // No clientInfo: keep the placeholder.
        }
        name = name.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) name = "unnamed client";
        return name.length() > 64 ? name.substring(0, 64) + "…" : name;
    }

    private static Map<String, Object> clientEntry(String action, Session s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("client", s.toJson());
        return m;
    }

    // ---- pairing, for /sculk mcp ----

    /**
     * Starts a session that waits for the player. Clients reconnect in
     * bursts (Claude Code, finding its session gone after a game restart,
     * initializes two or three times within a second and keeps the last),
     * so a session joins a waiting one from a client of the same name and
     * takes its number: accepting that number then approves whichever
     * session the client kept. Only waiting numbers are shared; a session
     * never joins one already approved.
     */
    private Session waitFor(String sessionId, String client) {
        Session created;
        boolean joined;
        synchronized (pending) {
            Session same = pending.values().stream().filter(s -> s.client.equals(client)).findFirst().orElse(null);
            joined = same != null;
            created = new Session(joined ? same.number : numbers.incrementAndGet(), client, false);
            pending.put(sessionId, created);
        }
        if (!joined) {
            engine.log(Sculk.entry("mcp_client", clientEntry("requested", created)));
            String accept = "/sculk mcp accept " + created.number;
            engine.chat(sculk.core.platform.ChatLine.of("MCP client #" + created.number + " (" + created.client
                    + ") wants access: " + accept + " ").button("accept", accept, "Let this client use sculk until the game exits"), null);
        }
        return created;
    }

    /** Paired clients, waiting and approved, one entry per number, with how many sessions share it. */
    List<Map<String, Object>> clients() {
        Map<Integer, Map<String, Object>> byNumber = new java.util.TreeMap<>();
        for (Map<String, Session> map : List.of(pending, sessions)) {
            synchronized (map) {
                for (Session s : map.values()) {
                    if (s.number == 0) continue;
                    Map<String, Object> entry = byNumber.computeIfAbsent(s.number, n -> {
                        Map<String, Object> m = s.toJson();
                        m.put("sessions", 0);
                        return m;
                    });
                    entry.put("sessions", (Integer) entry.get("sessions") + 1);
                }
            }
        }
        return new ArrayList<>(byNumber.values());
    }

    /**
     * Approves a waiting client, every session under its number: the one
     * numbered {@code number}, or, when null, the only one waiting. Returns
     * its entry; throws IllegalArgumentException, with what to do, when that
     * is not one client.
     */
    Map<String, Object> accept(Integer number) {
        Map<String, Session> moved = new LinkedHashMap<>();
        synchronized (pending) {
            java.util.Set<Integer> waiting = new java.util.TreeSet<>();
            for (Session s : pending.values()) waiting.add(s.number);
            if (number == null) {
                if (waiting.isEmpty()) throw new IllegalArgumentException("no MCP client is waiting");
                if (waiting.size() > 1) {
                    throw new IllegalArgumentException(waiting.size() + " clients are waiting; name one: /sculk mcp accept <number>");
                }
                number = waiting.iterator().next();
            }
            if (!waiting.contains(number)) throw new IllegalArgumentException("no MCP client #" + number + " is waiting");
            for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue().number == number) {
                    moved.put(e.getKey(), e.getValue());
                    it.remove();
                }
            }
        }
        Session last = null;
        for (var e : moved.entrySet()) {
            e.getValue().approved = true;
            sessions.put(e.getKey(), e.getValue());
            last = e.getValue();
        }
        Sculk.LOG.info("MCP client #{} ({}) approved", last.number, last.client);
        engine.log(Sculk.entry("mcp_client", clientEntry("accepted", last)));
        return last.toJson();
    }

    /** Ends a paired client's sessions, waiting or approved; it must pair again. Returns its entry. */
    Map<String, Object> revoke(int number) {
        List<Session> found = new ArrayList<>();
        for (Map<String, Session> map : List.of(pending, sessions)) {
            synchronized (map) {
                map.values().removeIf(s -> s.number == number && found.add(s));
            }
        }
        if (found.isEmpty()) throw new IllegalArgumentException("no MCP client #" + number);
        for (Session s : found) s.calls.values().forEach(Call::cancel);
        Session first = found.get(0);
        Sculk.LOG.info("MCP client #{} ({}) revoked", first.number, first.client);
        engine.log(Sculk.entry("mcp_client", clientEntry("revoked", first)));
        return first.toJson();
    }

    private static String newSessionId() {
        byte[] random = new byte[16];
        new java.security.SecureRandom().nextBytes(random);
        return Bytes.hex(random);
    }

    /** A JSON-RPC id or progress token as a map key: strings stay strings, whole numbers become longs. */
    static Object requestId(JsonElement e) {
        Object v = GSON.fromJson(e, Object.class);
        return v instanceof Double d && d == Math.rint(d) ? (Object) d.longValue() : v;
    }

    /** Null if the MCP-Protocol-Version header is absent or one we speak, else why not. */
    static String checkVersion(String header) {
        if (header == null || PROTOCOL_VERSIONS.contains(header.trim())) return null;
        return "unsupported MCP-Protocol-Version " + header + " (supported: " + String.join(", ", PROTOCOL_VERSIONS) + ")";
    }

    /** Handles one JSON-RPC message; returns the response object, or null for a notification or a response. */
    private Map<String, Object> message(JsonElement parsed, Call call) {
        if (!parsed.isJsonObject()) return error(null, -32600, "expected a JSON-RPC message object");
        JsonObject msg = parsed.getAsJsonObject();
        Object id = msg.has("id") && !msg.get("id").isJsonNull() ? GSON.fromJson(msg.get("id"), Object.class) : null;
        if (id instanceof Double d && d == Math.rint(d)) id = d.longValue();
        String method = msg.has("method") && msg.get("method").isJsonPrimitive() ? msg.get("method").getAsString() : null;
        if (method == null) {
            // A response to a request of ours (we send none), or junk.
            return id == null || msg.has("result") || msg.has("error") ? null : error(id, -32600, "missing method");
        }
        if (!msg.has("id")) return null; // A notification (initialized, cancelled, ...): nothing to answer.
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
        try {
            Object result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> call(params, call);
                default -> null;
            };
            if (result == null) return error(id, -32601, "method not found: " + method);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("jsonrpc", "2.0");
            out.put("id", id);
            out.put("result", result);
            return out;
        } catch (IllegalArgumentException e) {
            return error(id, -32602, String.valueOf(e.getMessage()));
        }
    }

    static final Map<String, Object> SERVER_INFO = Map.of("name", "sculk", "version", "0.1.0");
    static final Map<String, Object> CAPABILITIES = Map.of("tools", Map.of("listChanged", false));

    private Map<String, Object> initialize(JsonObject params) {
        String asked = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSIONS.contains(asked) ? asked : PROTOCOL_VERSIONS.get(0));
        out.put("capabilities", CAPABILITIES);
        out.put("serverInfo", SERVER_INFO);
        out.put("instructions", instructions());
        return out;
    }

    /** What the server tells the model about itself, in either protocol era. */
    String instructions() {
        return "sculk is a packet interception and injection tool running inside a Minecraft "
                + engine.platform().describe() + " process. Use sculk_events to look at captured traffic, "
                + "sculk_wait_events to wait for new traffic, and sculk_eval to run JavaScript with the `sculk` "
                + "scripting API in the live game. Installed scripts (sculk_scripts, sculk_script_read, "
                + "sculk_script_write, sculk_script_edit, sculk_script_control) persist on disk and run at launch; "
                + "use them for anything that should outlive the session. "
                + "Only use it against games you own or are authorised to test.";
    }

    // ---- tools ----

    /**
     * Hints for clients that ask before running tools: readOnlyHint (changes
     * nothing), destructiveHint (may undo or overwrite something),
     * idempotentHint (repeating it changes nothing more), openWorldHint (reaches
     * beyond this process: eval and reload can send packets to the other side).
     */
    private static Map<String, Object> hints(String title, boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title);
        m.put("readOnlyHint", readOnly);
        if (!readOnly) {
            m.put("destructiveHint", destructive);
            m.put("idempotentHint", idempotent);
        }
        m.put("openWorldHint", openWorld);
        return m;
    }

    private static final Map<String, Map<String, Object>> HINTS = Map.ofEntries(
            Map.entry("sculk_status", hints("Status", true, false, true, false)),
            Map.entry("sculk_events", hints("Query events", true, false, true, false)),
            Map.entry("sculk_wait_events", hints("Wait for events", true, false, true, false)),
            Map.entry("sculk_frame", hints("Read a frame", true, false, true, false)),
            Map.entry("sculk_stats", hints("Summarise events", true, false, true, false)),
            Map.entry("sculk_eval", hints("Run JavaScript in the game", false, true, false, true)),
            Map.entry("sculk_reload", hints("Reload all scripts", false, true, false, true)),
            Map.entry("sculk_scripts", hints("List scripts", true, false, true, false)),
            Map.entry("sculk_script_read", hints("Read a script", true, false, true, false)),
            Map.entry("sculk_script_write", hints("Write a script", false, true, false, true)),
            Map.entry("sculk_script_edit", hints("Edit a script", false, true, false, true)),
            Map.entry("sculk_script_control", hints("Run, stop, enable, rename or delete a script", false, true, false, true)));

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", schema);
        Map<String, Object> hints = HINTS.get(name);
        if (hints != null) {
            t.put("title", hints.get("title"));
            t.put("annotations", hints);
        }
        return t;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    static List<Map<String, Object>> tools() {
        Map<String, Object> filter = prop("string", "Event filter; blank or absent matches everything.");
        Map<String, Object> after = prop("integer", "Only events with an id greater than this.");
        Map<String, Object> fields = prop("string", "Optional: comma-separated fields to return instead of whole events, "
                + "e.g. \"t,type,channel,mod.data\" (shorthands and dotted paths as in filters). Each event comes back as "
                + "{\"id\": ..., \"<field>\": value, ...}.");
        Map<String, Object> hex = prop("boolean", "Include frame.hex and frame.spans (default false).");
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(tool("sculk_status",
                "Platform, open connections, running scripts, the id of the latest event, and what the capture holds "
                        + "(first and last id, events, memory and disk use).",
                Map.of(), List.of()));
        out.add(tool("sculk_events",
                "Query the session's events, oldest first, as JSON Lines. The whole session is captured (up to a disk "
                        + "cap; sculk_status says what is held), not just recent events. Without `after`, returns the last "
                        + "`limit` matches (before `before`, if given: page backwards with the first id returned); with "
                        + "`after`, the first `limit` matches after it (page forward with the last id returned). "
                        + "Filters on id, t, kind, conn, dir, len, side, type, channel, owner, injected, action, script and "
                        + "packet.state run on an index and are fast over millions of events; other fields (mod.data..., "
                        + "frame.hex) read events back from disk, so narrow those with an id range or an indexed clause. "
                        + "Long scans report progress and can be cancelled. For counts and summaries, sculk_stats is "
                        + "cheaper than paging. " + SHAPE_HELP + "\n\n"
                        + FILTER_HELP,
                Map.of("filter", filter, "after", after, "before", prop("integer", "Only events with an id less than this."),
                        "limit", prop("integer", "Maximum events to return (default 50)."), "fields", fields, "hex", hex),
                List.of()));
        out.add(tool("sculk_stats",
                "Summarise the events matching a filter, over the whole session or an id range: how many, their frame "
                        + "bytes, and the first and last of them (id and time t, epoch ms). With `by` (a field, e.g. channel, "
                        + "owner, type, kind, dir, conn.id, script, or a deeper one like mod.data.slot), one row per distinct "
                        + "value, biggest first. Use it to see what a session holds before reading events: which mods talk "
                        + "most, when a channel first appeared, how many drops a script made. Same filter syntax as sculk_events.",
                Map.of("filter", filter, "by", prop("string", "Field to group by; absent for one row of everything."),
                        "after", after, "before", prop("integer", "Only events with an id less than this."),
                        "limit", prop("integer", "Maximum groups to return, biggest first (default 30).")),
                List.of()));
        out.add(tool("sculk_wait_events",
                "Wait until at least one event matching the filter with an id greater than `after` exists, then return "
                        + "the matches (up to `limit`) as JSON Lines. Returns nothing if `timeoutMs` passes first. "
                        + "Long waits report progress (send a progressToken) and can be cancelled. "
                        + "Use sculk_status or a previous result for the starting id. Same filter syntax, `fields` and "
                        + "`hex` as sculk_events.",
                Map.of("filter", filter, "after", prop("integer", "Wait for events after this id (required)."),
                        "limit", prop("integer", "Maximum events to return (default 50)."),
                        "timeoutMs", prop("integer", "How long to wait, at most 300000 (default 30000)."),
                        "fields", fields, "hex", hex),
                List.of("after")));
        out.add(tool("sculk_frame",
                "The whole frame (packet id and body, uncompressed and unencrypted) of a packet event, as hex.",
                Map.of("id", prop("integer", "Event id.")), List.of("id")));
        out.add(tool("sculk_eval",
                "Evaluate JavaScript in the game process, in the console's own scope (not a script's: use "
                        + "sculk.scope('name.js') to reach a running script's globals). Globals and listeners made here persist "
                        + "between calls until sculk_reload. For anything to keep, write a script instead (sculk_script_write). "
                        + "Returns the value of the last expression.\n\n" + SCRIPT_HELP,
                Map.of("code", prop("string", "JavaScript source.")), List.of("code")));
        out.add(tool("sculk_reload",
                "Stop every script and drop all script state (globals, listeners, timers, the console's too), then start "
                        + "the enabled startup scripts again from disk.",
                Map.of(), List.of()));
        Map<String, Object> scriptName = prop("string", "Script name as sculk_scripts lists it, e.g. drop-sync.js.");
        out.add(tool("sculk_scripts",
                "List installed scripts: name, enabled, run mode (startup/manual), title and description from the "
                        + "metadata block, state (active: has listeners or timers; idle: ran, nothing registered; stopped), "
                        + "the last run's outcome, and the source hash. Also the script directory and whether this process "
                        + "is a game client or a dedicated server.",
                Map.of(), List.of()));
        out.add(tool("sculk_script_read",
                "Read a script's source, with line numbers, and its hash (pass it to sculk_script_write as `base`).",
                Map.of("name", scriptName), List.of("name")));
        out.add(tool("sculk_script_write",
                "Create or replace a script with the whole source. A new script is enabled, and starts now if it is a "
                        + "startup script (the default). A script that is running restarts with the new source; if the new "
                        + "source has a syntax error, the file is still written but the old version keeps running. A script "
                        + "that failed to start is retried. Prefer sculk_script_edit for small changes.\n\n" + SCRIPT_HELP,
                Map.of("name", prop("string", "File name ending in .js; letters, digits, spaces and _ . ( ) + , -."),
                        "source", prop("string", "The whole script."),
                        "base", prop("string", "Optional: the hash this was edited from (from sculk_script_read); the write "
                                + "fails if the script changed since. Pass an empty string to require that the script is new."),
                        "run", prop("boolean", "Also (re)start it now, even if it is a manual script.")),
                List.of("name", "source")));
        out.add(tool("sculk_script_edit",
                "Edit a script by replacing exact text: `old` must occur exactly once in the source (include enough "
                        + "context to make it unique), unless `all` is true. A running script restarts with the result "
                        + "(or keeps its old version if the result does not compile).",
                Map.of("name", scriptName,
                        "old", prop("string", "Exact text to replace, whitespace included."),
                        "new", prop("string", "Replacement text."),
                        "all", prop("boolean", "Replace every occurrence.")),
                List.of("name", "old", "new")));
        Map<String, Object> action = new LinkedHashMap<>(prop("string",
                "run: (re)start it from its file now, whatever its run mode, and return the value of its last expression "
                        + "or its error. stop: remove its listeners and timers; it no longer follows its file. enable / disable: whether it starts at launch "
                        + "and on reload (disabling also stops it; enabling starts a startup script). rename: needs `to`. "
                        + "delete: stop it and delete the file."));
        action.put("enum", List.of("run", "stop", "enable", "disable", "rename", "delete"));
        out.add(tool("sculk_script_control",
                "Run, stop, enable, disable, rename or delete an installed script.",
                Map.of("name", scriptName, "action", action, "to", prop("string", "New name, for rename.")),
                List.of("name", "action")));
        return out;
    }

    Map<String, Object> call(JsonObject params, Call ctx) {
        if (!params.has("name")) throw new IllegalArgumentException("missing tool name");
        String name = params.get("name").getAsString();
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        try {
            return switch (name) {
                case "sculk_status" -> text(EventLog.toJson(ControlServer.status(engine, store)), false);
                case "sculk_events" -> lines(store.query(string(args, "filter"), longArg(args, "after"), longArg(args, "before"),
                        intArg(args, "limit", 50), scanProgress(ctx)), shape(args));
                case "sculk_stats" -> text(EventLog.toJson(store.stats(string(args, "filter"), string(args, "by"),
                        longArg(args, "after"), longArg(args, "before"), intArg(args, "limit", 30), scanProgress(ctx))), false);
                case "sculk_wait_events" -> waitEvents(args, ctx);
                case "sculk_frame" -> {
                    Long id = longArg(args, "id");
                    if (id == null) throw new IllegalArgumentException("id is required");
                    StoredEvent e = store.get(id);
                    if (e == null) yield text("no such event " + id + " (it may have been dropped)", true);
                    if (e.frame() == null) yield text("event " + id + " has no frame", true);
                    yield text(Bytes.hex(e.frame()), false);
                }
                case "sculk_eval" -> {
                    String code = string(args, "code");
                    if (code == null) throw new IllegalArgumentException("code is required");
                    Map<String, Object> r = engine.scripts().evalForRepl(code);
                    boolean ok = Boolean.TRUE.equals(r.get("ok"));
                    yield text(ok ? EventLog.toJson(r.get("result")) : String.valueOf(r.get("error")), !ok);
                }
                case "sculk_reload" -> {
                    Map<String, Object> r = engine.reloadScripts();
                    yield text(EventLog.toJson(r), !Boolean.TRUE.equals(r.get("ok")));
                }
                case "sculk_scripts" -> text(EventLog.toJson(ControlServer.scriptList(engine)), false);
                case "sculk_script_read" -> scriptRead(required(args, "name"));
                case "sculk_script_write" -> scriptWrite(args);
                case "sculk_script_edit" -> scriptEdit(args);
                case "sculk_script_control" -> scriptControl(args);
                default -> throw new IllegalArgumentException("unknown tool " + name);
            };
        } catch (ScriptManager.ScriptError e) {
            return text("error: " + e.getMessage(), true);
        } catch (EventStore.Cancelled e) {
            throw new java.util.concurrent.CancellationException();
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("unknown tool")) throw e;
            // Bad filters and arguments are tool errors the model can read and fix.
            return text("error: " + e.getMessage(), true);
        }
    }

    /** Scan progress for a call: notifications at most every {@link #TICK_MILLIS}, and its cancellation. */
    private static EventStore.Progress scanProgress(Call ctx) {
        long start = System.currentTimeMillis();
        long[] next = {start + TICK_MILLIS};
        return new EventStore.Progress() {
            @Override
            public void tick(long done, long total) {
                long now = System.currentTimeMillis();
                if (now < next[0]) return;
                next[0] = now + TICK_MILLIS;
                ctx.progress(done, total, "scanned " + done + " of " + total + " events");
            }

            @Override
            public boolean cancelled() {
                return ctx.cancelled();
            }
        };
    }

    // ---- scripts ----

    private Map<String, Object> scriptRead(String name) {
        Map<String, Object> s = engine.scripts().source(name);
        String source = (String) s.get("source");
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("  hash ").append(s.get("hash")).append("  ").append(Boolean.TRUE.equals(s.get("enabled")) ? "enabled" : "disabled")
                .append("  run ").append(s.get("run")).append("  ").append(statusLine(s.get("status"))).append('\n');
        String[] lines = source.split("\n", -1);
        int width = String.valueOf(lines.length).length();
        for (int i = 0; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) break;
            sb.append(String.format("%" + width + "d\t", i + 1)).append(lines[i]).append('\n');
        }
        return text(sb.toString(), false);
    }

    /** A script's status in one line: "running", "running (not the file: ...)", "failed: ...", "stopped (user)". */
    private static String statusLine(Object status) {
        if (!(status instanceof Map<?, ?> m)) return "";
        Object state = m.get("state");
        if ("running".equals(state)) {
            return m.get("rejected") instanceof Map<?, ?> r ? "running an older version; syntax error: " + r.get("error") : "running";
        }
        if ("failed".equals(state)) return "failed: " + m.get("error");
        return "stopped (" + m.get("why") + ")";
    }

    private Map<String, Object> scriptWrite(JsonObject args) {
        String name = required(args, "name");
        String source = args.has("source") && !args.get("source").isJsonNull() ? args.get("source").getAsString() : null;
        if (source == null) throw new IllegalArgumentException("source is required");
        Map<String, Object> r = engine.scripts().save(name, source, string(args, "base"));
        boolean run = args.has("run") && args.get("run").getAsBoolean();
        if (run && !r.containsKey("restart")) r = engine.scripts().run(name);
        return afterChange(r, source);
    }

    private Map<String, Object> scriptEdit(JsonObject args) {
        String name = required(args, "name");
        String old = string(args, "old"), replacement = string(args, "new");
        if (old == null || old.isEmpty()) throw new IllegalArgumentException("old is required and must not be empty");
        if (replacement == null) throw new IllegalArgumentException("new is required");
        boolean all = args.has("all") && args.get("all").getAsBoolean();
        Map<String, Object> current = engine.scripts().source(name);
        String source = (String) current.get("source");
        int first = source.indexOf(old);
        if (first < 0) return text("error: `old` does not occur in " + name + "; read it again with sculk_script_read", true);
        int count = 0;
        for (int i = first; i >= 0; i = source.indexOf(old, i + old.length())) count++;
        if (count > 1 && !all) {
            return text("error: `old` occurs " + count + " times in " + name + "; add context to make it unique, or pass all=true", true);
        }
        String updated = all ? source.replace(old, replacement)
                : source.substring(0, first) + replacement + source.substring(first + old.length());
        Map<String, Object> r = engine.scripts().save(name, updated, (String) current.get("hash"));
        r.put("replaced", all ? count : 1);
        return afterChange(r, updated);
    }

    /** The save result, with syntax problems and the restart outcome; an error if either failed. */
    private static Map<String, Object> afterChange(Map<String, Object> r, String source) {
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.remove("meta");
        Map<String, Object> check = ControlServer.check(source);
        if (!((List<?>) check.get("problems")).isEmpty()) out.put("problems", check.get("problems"));
        Object restart = out.get("restart");
        boolean failed = !Boolean.TRUE.equals(check.get("ok"))
                || (restart instanceof Map<?, ?> m && !Boolean.TRUE.equals(m.get("ok")))
                || (out.containsKey("ok") && !Boolean.TRUE.equals(out.get("ok")));
        return text(EventLog.toJson(out), failed);
    }

    private Map<String, Object> scriptControl(JsonObject args) {
        String name = required(args, "name");
        String action = required(args, "action");
        var scripts = engine.scripts();
        Map<String, Object> r = switch (action) {
            case "run" -> scripts.run(name);
            case "stop" -> scripts.stop(name);
            case "enable" -> scripts.setEnabled(name, true);
            case "disable" -> scripts.setEnabled(name, false);
            case "rename" -> scripts.rename(name, required(args, "to"));
            case "delete" -> scripts.delete(name);
            default -> throw new IllegalArgumentException("unknown action " + action + " (run, stop, enable, disable, rename, delete)");
        };
        Map<String, Object> out = new LinkedHashMap<>(r);
        out.remove("meta");
        Object restart = out.get("restart");
        boolean failed = (out.containsKey("ok") && !Boolean.TRUE.equals(out.get("ok")))
                || (restart instanceof Map<?, ?> m && !Boolean.TRUE.equals(m.get("ok")));
        return text(EventLog.toJson(out), failed);
    }

    private static String required(JsonObject args, String key) {
        String v = string(args, key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(key + " is required");
        return v;
    }

    private Map<String, Object> waitEvents(JsonObject args, Call ctx) {
        Long after = longArg(args, "after");
        if (after == null) throw new IllegalArgumentException("after is required");
        Predicate<Map<String, Object>> filter = EventFilter.compile(string(args, "filter"));
        int limit = intArg(args, "limit", 50);
        long timeout = Math.min(Math.max(intArg(args, "timeoutMs", 30_000), 0), MAX_WAIT_MILLIS);
        BlockingQueue<StoredEvent> arrived = new LinkedBlockingQueue<>();
        Consumer<StoredEvent> subscriber = e -> {
            if (e.id() > after && filter.test(e.fields())) arrived.offer(e);
        };
        store.subscribe(subscriber);
        try {
            String source = string(args, "filter");
            List<StoredEvent> found = store.query(source, after, null, limit, scanProgress(ctx));
            long start = System.currentTimeMillis(), deadline = start + timeout, nextTick = start + TICK_MILLIS;
            while (found.isEmpty()) {
                if (ctx.cancelled()) throw new java.util.concurrent.CancellationException();
                long now = System.currentTimeMillis();
                if (now >= deadline) break;
                if (arrived.poll(Math.min(deadline - now, 100), TimeUnit.MILLISECONDS) != null) {
                    Thread.sleep(50); // Let a burst of related events land together.
                    found = store.query(source, after, null, limit, scanProgress(ctx));
                    break;
                }
                if (System.currentTimeMillis() >= nextTick) {
                    long elapsed = System.currentTimeMillis() - start;
                    ctx.progress(elapsed, timeout, "waiting for events: " + elapsed / 1000 + " s of " + timeout / 1000 + " s");
                    nextTick += TICK_MILLIS;
                }
            }
            return lines(found, shape(args));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return text("interrupted", true);
        } finally {
            store.unsubscribe(subscriber);
        }
    }

    // ---- plumbing ----

    static final String SHAPE_HELP = "Events leave out frame.hex and frame.spans unless `hex` is true, and `fields` "
            + "picks just the fields wanted; both only change what comes back, not what filters see. Frame hex in events "
            + "may be cut short; use sculk_frame for the whole frame.";

    /** How events are returned: the fields to pick (null for whole events) and whether to keep the frame hex. */
    record Shape(List<String> fields, boolean hex) {
        static final Shape WHOLE = new Shape(null, true);
    }

    private static Shape shape(JsonObject args) {
        String f = string(args, "fields");
        List<String> fields = null;
        if (f != null && !f.isBlank()) {
            fields = new ArrayList<>();
            for (String part : f.split(",")) {
                String path = part.trim();
                if (path.isEmpty() || path.equals("id")) continue;
                EventFilter.analyze(path); // a syntax check, as for stats' `by`
                fields.add(path);
            }
        }
        boolean hex = args.has("hex") && !args.get("hex").isJsonNull() && args.get("hex").getAsBoolean();
        return new Shape(fields, hex);
    }

    /** One event as returned under {@code shape}, as a JSON line. */
    static String shaped(StoredEvent e, Shape shape) {
        if (shape.fields() != null) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", e.id());
            for (String path : shape.fields()) out.put(path, EventFilter.value(e.fields(), path));
            return EventLog.toJson(out);
        }
        if (shape.hex() || !(e.fields().get("frame") instanceof Map<?, ?> frame)
                || !(frame.containsKey("hex") || frame.containsKey("spans"))) {
            return e.json();
        }
        Map<String, Object> out = new LinkedHashMap<>(e.fields());
        Map<Object, Object> slim = new LinkedHashMap<>(frame);
        slim.remove("hex");
        slim.remove("spans");
        out.put("frame", slim);
        return EventLog.toJson(out);
    }

    private static Map<String, Object> lines(List<StoredEvent> events, Shape shape) {
        StringBuilder sb = new StringBuilder();
        for (StoredEvent e : events) sb.append(shaped(e, shape)).append('\n');
        return text(events.isEmpty() ? "(no matching events)" : sb.toString(), false);
    }

    private static Map<String, Object> text(String text, boolean isError) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", List.of(Map.of("type", "text", "text", text)));
        out.put("isError", isError);
        return out;
    }

    private static String string(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : null;
    }

    private static Long longArg(JsonObject args, String key) {
        try {
            return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsLong() : null;
        } catch (NumberFormatException | UnsupportedOperationException e) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
    }

    private static int intArg(JsonObject args, String key, int otherwise) {
        Long v = longArg(args, key);
        return v == null ? otherwise : (int) Math.max(0, Math.min(v, Integer.MAX_VALUE));
    }

    static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        out.put("error", error);
        return out;
    }
}
