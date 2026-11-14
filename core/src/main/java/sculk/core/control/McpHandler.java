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
import sculk.core.script.ScriptManager;
import sculk.core.util.Bytes;

/**
 * A Model Context Protocol server over the control server's HTTP listener
 * ({@code POST /mcp}, the Streamable HTTP transport, answering with plain
 * JSON rather than a stream). It shares the control server's token, Host
 * and Origin checks, and gives an agent the same powers as the HTTP API:
 * query and wait for events, read frames, evaluate script code, and read,
 * write, edit, run and manage installed scripts.
 */
final class McpHandler {
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private static final Gson GSON = new Gson();
    private static final int MAX_WAIT_MILLIS = 60_000;

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
            emit(obj) (a `script` event), log(msg) (a `script_log` event), schedule(ms, fn), tree(obj), \
            with(record, field, value), hex(bytes), unhex(str), copy(bytes), scope(name) (another running script's \
            globals), name, dist. Connections offer inject(dir, bytes), injectPacket(dir, obj), replay(event). \
            Packet events have the same fields and shorthands as logged events (e.mod.channel, e.frame.dir, e.owner), \
            plus e.frame.bytes, e.packet.object, e.mod.data as live objects and e.conn as the live connection, and \
            offer drop(), replaceFrame(bytes), replacePacket(obj).""";

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
                Map<String, Object> r = message(e);
                if (r != null) out.add(r);
            }
            return out.isEmpty() ? null : out;
        }
        return message(parsed);
    }

    /** Null if the MCP-Protocol-Version header is absent or one we speak, else why not. */
    static String checkVersion(String header) {
        if (header == null || PROTOCOL_VERSIONS.contains(header.trim())) return null;
        return "unsupported MCP-Protocol-Version " + header + " (supported: " + String.join(", ", PROTOCOL_VERSIONS) + ")";
    }

    /** Handles one JSON-RPC message; returns the response object, or null for a notification or a response. */
    private Map<String, Object> message(JsonElement parsed) {
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
                + "scripting API in the live game. Installed scripts (sculk_scripts, sculk_script_read, "
                + "sculk_script_write, sculk_script_edit, sculk_script_control) persist on disk and run at launch; "
                + "use them for anything that should outlive the session. "
                + "Only use it against games you own or are authorised to test.");
        return out;
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

    private static List<Map<String, Object>> tools() {
        Map<String, Object> filter = prop("string", "Event filter; blank or absent matches everything.");
        Map<String, Object> after = prop("integer", "Only events with an id greater than this.");
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(tool("sculk_status",
                "Platform, open connections, running scripts and the id of the latest event.",
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
                "Create or replace a script with the whole source. A new script is enabled; it starts now only if "
                        + "`run` is true. A script that is running restarts with the new source. Syntax errors are reported "
                        + "and the file is still written. Prefer sculk_script_edit for small changes.\n\n" + SCRIPT_HELP,
                Map.of("name", prop("string", "File name ending in .js; letters, digits, spaces and _ . ( ) + , -."),
                        "source", prop("string", "The whole script."),
                        "base", prop("string", "Optional: the hash this was edited from (from sculk_script_read); the write "
                                + "fails if the script changed since. Pass an empty string to require that the script is new."),
                        "run", prop("boolean", "Also (re)start it now, even if it is a manual script.")),
                List.of("name", "source")));
        out.add(tool("sculk_script_edit",
                "Edit a script by replacing exact text: `old` must occur exactly once in the source (include enough "
                        + "context to make it unique), unless `all` is true. A running script restarts with the result.",
                Map.of("name", scriptName,
                        "old", prop("string", "Exact text to replace, whitespace included."),
                        "new", prop("string", "Replacement text."),
                        "all", prop("boolean", "Replace every occurrence.")),
                List.of("name", "old", "new")));
        Map<String, Object> action = new LinkedHashMap<>(prop("string",
                "run: (re)start it from its file now, whatever its run mode, and return the value of its last expression "
                        + "or its error. stop: remove its listeners and timers. enable / disable: whether it starts at launch "
                        + "and on reload (disabling also stops it; enabling starts a startup script). rename: needs `to`. "
                        + "delete: stop it and delete the file."));
        action.put("enum", List.of("run", "stop", "enable", "disable", "rename", "delete"));
        out.add(tool("sculk_script_control",
                "Run, stop, enable, disable, rename or delete an installed script.",
                Map.of("name", scriptName, "action", action, "to", prop("string", "New name, for rename.")),
                List.of("name", "action")));
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
                case "sculk_scripts" -> text(EventLog.toJson(ControlServer.scriptList(engine)), false);
                case "sculk_script_read" -> scriptRead(required(args, "name"));
                case "sculk_script_write" -> scriptWrite(args);
                case "sculk_script_edit" -> scriptEdit(args);
                case "sculk_script_control" -> scriptControl(args);
                default -> throw new IllegalArgumentException("unknown tool " + name);
            };
        } catch (ScriptManager.ScriptError e) {
            return text("error: " + e.getMessage(), true);
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("unknown tool")) throw e;
            // Bad filters and arguments are tool errors the model can read and fix.
            return text("error: " + e.getMessage(), true);
        }
    }

    // ---- scripts ----

    private Map<String, Object> scriptRead(String name) {
        Map<String, Object> s = engine.scripts().source(name);
        String source = (String) s.get("source");
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("  hash ").append(s.get("hash")).append("  ").append(Boolean.TRUE.equals(s.get("enabled")) ? "enabled" : "disabled")
                .append("  run ").append(s.get("run")).append("  state ").append(s.get("state")).append('\n');
        String[] lines = source.split("\n", -1);
        int width = String.valueOf(lines.length).length();
        for (int i = 0; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) break;
            sb.append(String.format("%" + width + "d\t", i + 1)).append(lines[i]).append('\n');
        }
        return text(sb.toString(), false);
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
