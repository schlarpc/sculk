package sculk.core.control;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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
import sculk.core.util.Bytes;

/**
 * A Model Context Protocol server over the control server's HTTP listener
 * ({@code POST /mcp}, the Streamable HTTP transport, answering with plain
 * JSON rather than a stream). It shares the control server's token, Host
 * and Origin checks, and gives an agent the same powers as the HTTP API:
 * query and wait for events, read frames, evaluate script code, reload.
 */
final class McpHandler {
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final Gson GSON = new Gson();
    private static final int MAX_WAIT_MILLIS = 60_000;

    private static final String FILTER_HELP = """
            Filter syntax (Wireshark-style; blank matches everything): \
            `kind == packet && dir == inbound && owner != minecraft`, \
            `packet.type matches "recipe|chat" and len > 1000`, `mod.data contains "../"`, \
            `mod.data.pos.x >= 100`, `mod && action != pass`. \
            Fields are event keys; dots walk into nested objects and lists (a list matches if any element does). \
            Operators: == != < <= > >= contains matches (~ is matches, a regex found anywhere). \
            A field alone means present and not false, so `mod` alone means "is a custom payload". \
            Combine with && || ! or and/or/not and parentheses. \
            Event kinds: start, connection, disconnect, payload_type, packet, script, script_error, script_reload, eval. \
            Packet events are layered: id, kind, t, injected, action (pass/drop/replace); \
            conn {id, side (client/server), remote}; frame {seq, dir (inbound/outbound), len, hex, spans: \
            [{field, off, len}] saying which bytes hold packet.id, packet.body, mod.channel, mod.data}; \
            packet {id, state, flow, type, javaClass, owner}; and for custom payloads only \
            mod {channel, owner (mod id), registeredVia, javaClass, roundTrip, data (decoded tree)}. \
            connection and disconnect events have conn; payload_type events have mod. \
            Shorthands: dir, len, side, type, channel, and owner (mod.owner, else packet.owner).""";


    private final Sculk engine;
    private final EventStore store;

    McpHandler(Sculk engine, EventStore store) {
        this.engine = engine;
        this.store = store;
    }

    /** Handles one JSON-RPC message; returns the response object, or null for a notification. */
    Map<String, Object> handle(String body) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (JsonParseException e) {
            return error(null, -32700, "parse error: " + e.getMessage());
        }
        if (!parsed.isJsonObject()) return error(null, -32600, "expected one JSON-RPC request object (batches are not supported)");
        JsonObject msg = parsed.getAsJsonObject();
        Object id = msg.has("id") && !msg.get("id").isJsonNull() ? GSON.fromJson(msg.get("id"), Object.class) : null;
        if (id instanceof Double d && d == Math.rint(d)) id = d.longValue();
        String method = msg.has("method") ? msg.get("method").getAsString() : null;
        if (method == null) return id == null ? null : error(id, -32600, "missing method");
        if (!msg.has("id")) return null; // A notification (initialized, cancelled, ...): nothing to answer.
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();
        try {
            Object result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> Map.of();
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> call(params);
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

    private Map<String, Object> initialize(JsonObject params) {
        String asked = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocolVersion", PROTOCOL_VERSIONS.contains(asked) ? asked : PROTOCOL_VERSIONS.get(0));
        out.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        out.put("serverInfo", Map.of("name", "sculk", "version", "0.1.0"));
        out.put("instructions", "sculk is a packet interception and injection tool running inside a Minecraft "
                + engine.platform().describe() + " process. Use sculk_events to look at captured traffic, "
                + "sculk_wait_events to wait for new traffic, and sculk_eval to run JavaScript with the `sculk` "
                + "scripting API in the live game. Only use it against games you own or are authorised to test.");
        return out;
    }

    // ---- tools ----

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", schema);
        return t;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static List<Map<String, Object>> tools() {
        Map<String, Object> filter = prop("string", "Event filter; blank or absent matches everything.");
        Map<String, Object> after = prop("integer", "Only events with an id greater than this.");
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(tool("sculk_status",
                "Platform, open connections, startup scripts and the id of the latest event.",
                Map.of(), List.of()));
        out.add(tool("sculk_events",
                "Query recent events, oldest first, as JSON Lines. Without `after`, returns the last `limit` matches; "
                        + "with it, the first `limit` matches after that id (page forward with the last id returned). "
                        + "Frame hex in events may be cut short; use sculk_frame for the whole frame.\n\n" + FILTER_HELP,
                Map.of("filter", filter, "after", after, "limit", prop("integer", "Maximum events to return (default 50).")),
                List.of()));
        out.add(tool("sculk_wait_events",
                "Wait until at least one event matching the filter with an id greater than `after` exists, then return "
                        + "the matches (up to `limit`) as JSON Lines. Returns nothing if `timeoutMs` passes first. "
                        + "Use sculk_status or a previous result for the starting id. Same filter syntax as sculk_events.",
                Map.of("filter", filter, "after", prop("integer", "Wait for events after this id (required)."),
                        "limit", prop("integer", "Maximum events to return (default 50)."),
                        "timeoutMs", prop("integer", "How long to wait, at most 60000 (default 30000).")),
                List.of("after")));
        out.add(tool("sculk_frame",
                "The whole frame (packet id and body, uncompressed and unencrypted) of a packet event, as hex.",
                Map.of("id", prop("integer", "Event id.")), List.of("id")));
        out.add(tool("sculk_eval",
                "Evaluate JavaScript (Rhino, ES6) in the live script scope of the game process, where startup scripts "
                        + "ran. The `sculk` global offers: on('packet'|'connection', fn), connections(), payloadTypes(), "
                        + "emit(obj), log(msg), schedule(ms, fn), tree(obj), with(record, field, value), hex(bytes), "
                        + "unhex(str), copy(bytes). Connections offer inject(dir, bytes), injectPacket(dir, obj), "
                        + "replay(event). Packet events have the same fields and shorthands as logged events (e.mod.channel, "
                        + "e.frame.dir, e.owner), plus e.frame.bytes, e.packet.object, e.mod.data as live objects and "
                        + "e.conn as the live connection, and offer drop(), replaceFrame(bytes), replacePacket(obj). "
                        + "Globals persist between calls until sculk_reload. Returns the value of the last expression.",
                Map.of("code", prop("string", "JavaScript source.")), List.of("code")));
        out.add(tool("sculk_reload",
                "Drop all script state (globals, listeners, timers) and load the startup scripts again from disk.",
                Map.of(), List.of()));
        return out;
    }

    private Map<String, Object> call(JsonObject params) {
        if (!params.has("name")) throw new IllegalArgumentException("missing tool name");
        String name = params.get("name").getAsString();
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        try {
            return switch (name) {
                case "sculk_status" -> text(EventLog.toJson(ControlServer.status(engine, store)), false);
                case "sculk_events" -> {
                    Predicate<Map<String, Object>> f = EventFilter.compile(string(args, "filter"));
                    yield lines(store.query(f, longArg(args, "after"), intArg(args, "limit", 50)));
                }
                case "sculk_wait_events" -> waitEvents(args);
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
                default -> throw new IllegalArgumentException("unknown tool " + name);
            };
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("unknown tool")) throw e;
            // Bad filters and arguments are tool errors the model can read and fix.
            return text("error: " + e.getMessage(), true);
        }
    }

    private Map<String, Object> waitEvents(JsonObject args) {
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
            List<StoredEvent> found = store.query(filter, after, limit);
            if (found.isEmpty() && arrived.poll(timeout, TimeUnit.MILLISECONDS) != null) {
                Thread.sleep(50); // Let a burst of related events land together.
                found = store.query(filter, after, limit);
            }
            return lines(found);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return text("interrupted", true);
        } finally {
            store.unsubscribe(subscriber);
        }
    }

    // ---- plumbing ----

    private static Map<String, Object> lines(List<StoredEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (StoredEvent e : events) sb.append(e.json()).append('\n');
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

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", Map.of("code", code, "message", message));
        return out;
    }
}
