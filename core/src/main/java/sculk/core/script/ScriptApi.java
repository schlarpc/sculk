package sculk.core.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import sculk.core.Sculk;
import sculk.core.control.EventFilter;
import sculk.core.log.EventLog;
import sculk.core.model.ObjectModel;
import sculk.core.net.SculkConnection;
import sculk.core.packet.PacketEvent;
import sculk.core.util.Bytes;
import sculk.shaded.org.mozilla.javascript.Function;
import sculk.shaded.org.mozilla.javascript.RhinoException;
import sculk.shaded.org.mozilla.javascript.Scriptable;

/**
 * The {@code sculk} global that one script sees. Every script (and the
 * console) has its own instance and scope, and owns the listeners and timers
 * it registers, so {@link #close()} stops exactly that script.
 *
 * <pre>{@code
 * sculk.on('packet', e => {
 *   if (e.mod && e.mod.channel === 'appleskin:exhaustion_sync' && e.frame.dir === 'inbound') e.drop();
 * });
 * sculk.on('connection', c => sculk.log('hello ' + c.side));
 * }</pre>
 *
 * Packet events have the same layered fields as logged events, and the
 * same shorthands as filters: see {@link PacketEvent}. They offer
 * {@code drop()}, {@code replaceFrame(bytes)}, {@code replacePacket(obj)},
 * {@code replacePayload(payload)}.
 * Connections ({@code e.conn}) expose {@code id, side, remote} and
 * {@code inject(dir, bytes)}, {@code injectPacket(dir, obj)},
 * {@code injectPayload(dir, channel, payload)}, {@code replay(event)}.
 */
public final class ScriptApi implements AutoCloseable {
    private final Sculk engine;
    private final ScriptManager manager;
    private final ScriptHost host;
    private final String name;
    private final Scriptable scope;
    private final ScheduledExecutorService timers;
    private final List<Consumer<PacketEvent>> packetListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SculkConnection>> connectionListeners = new CopyOnWriteArrayList<>();
    private final Set<ScheduledFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final List<Runnable> decoders = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    ScriptApi(Sculk engine, ScriptManager manager, ScriptHost host, ScheduledExecutorService timers, String name) {
        this.engine = engine;
        this.manager = manager;
        this.host = host;
        this.timers = timers;
        this.name = name;
        this.scope = host.newScope();
        host.bind(scope, "sculk", this);
    }

    /** Runs {@code source} in this script's scope. */
    Object load(String source) {
        return host.eval(scope, name, source);
    }

    Scriptable scope() {
        return scope;
    }

    /** Listeners (and decoders) this script has registered and not removed. */
    int listenerCount() {
        return packetListeners.size() + connectionListeners.size() + decoders.size();
    }

    /** Timers this script has scheduled that have not run yet. */
    int timerCount() {
        pending.removeIf(ScheduledFuture::isDone);
        return pending.size();
    }

    /**
     * Evaluates {@code source} for an interactive caller and never throws:
     * {@code {"ok":true,"result":<JSON-safe value>}} or
     * {@code {"ok":false,"error":"..."}}. Java objects in the result are
     * shown as {@link ObjectModel} trees.
     */
    Map<String, Object> evalForRepl(String source) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Object value = load(source);
            out.put("ok", true);
            out.put("result", jsonSafe(value, 6));
        } catch (RhinoException e) {
            out.put("ok", false);
            out.put("error", describe(e));
        } catch (RuntimeException | LinkageError e) {
            out.put("ok", false);
            out.put("error", e.toString());
        }
        return out;
    }

    static String describe(RhinoException e) {
        return e.details() + " (" + e.sourceName() + ":" + e.lineNumber() + ")";
    }

    /** Stops this script: its listeners are removed and its pending timers cancelled. */
    @Override
    public void close() {
        closed = true;
        for (Consumer<PacketEvent> l : packetListeners) engine.removePacketListener(l);
        for (Consumer<SculkConnection> l : connectionListeners) engine.removeConnectionListener(l);
        packetListeners.clear();
        connectionListeners.clear();
        for (Runnable remove : decoders) remove.run();
        decoders.clear();
        for (ScheduledFuture<?> f : pending) f.cancel(false);
        pending.clear();
    }

    // ---- script-visible API ----

    public void on(String event, Function fn) {
        on(event, null, fn);
    }

    /**
     * Calls {@code fn} for each event of a kind ({@code packet} or
     * {@code connection}), or, with a {@code filter} in the language of
     * sculk_events and the web UI, only for those it matches: the same
     * fields and shorthands as logged events. A filter is checked before
     * the script runs, and only builds the costly fields it reads
     * ({@code packet.data}, {@code frame.hex}, {@code mod.roundTrip}).
     * There is no {@code id} yet when listeners run.
     */
    public void on(String event, String filter, Function fn) {
        if (closed) throw new IllegalStateException("script " + name + " was stopped");
        EventFilter.Compiled f = EventFilter.analyze(filter);
        boolean all = f.matchesAll();
        switch (event) {
            case "packet" -> {
                boolean data = reads(f, "packet.data"), hex = reads(f, "frame.hex"), roundTrip = reads(f, "mod.roundTrip");
                Consumer<PacketEvent> l = e -> {
                    if (all || f.predicate().test(e.filterView(data, hex, roundTrip, FILTER_TREE_DEPTH))) invoke("packet", fn, e);
                };
                packetListeners.add(l);
                engine.addPacketListener(l);
            }
            case "connection" -> {
                Consumer<SculkConnection> l = c -> {
                    if (all || f.predicate().test(Map.of("kind", "connection", "conn", c.toJson()))) invoke("connection", fn, c);
                };
                connectionListeners.add(l);
                engine.addConnectionListener(l);
            }
            default -> throw new IllegalArgumentException("unknown event '" + event + "' (expected packet, connection)");
        }
    }

    /** As deep as the event log's trees, so a filter matches here what it matches there. */
    private static final int FILTER_TREE_DEPTH = 6;

    /** Whether a filter reads {@code path}: the path itself, a field under it, or a whole layer above it. */
    private static boolean reads(EventFilter.Compiled f, String path) {
        for (EventFilter.Ref r : f.refs()) {
            String p = r.path();
            if (p.equals(path) || p.startsWith(path + ".")) return true;
            if (!r.presence() && path.startsWith(p + ".")) return true; // `packet contains x` reads all of packet
        }
        return false;
    }

    /**
     * Shows objects of class {@code className} (a Java class name, also
     * matching subclasses) the way {@code fn(obj, tree)} says, wherever
     * sculk makes a tree: packet.data in the event log and in the web UI,
     * sculk.tree. {@code obj} is the live object; {@code tree} is what sculk
     * would show, as a plain object to change in place or replace. Return
     * the tree to show (or nothing, to show {@code tree} as changed). Values
     * in it may be live game objects (from sculk.reader, say): they show as
     * trees too. A decoder that throws is reported as a script_error and the
     * plain tree is shown. Runs on the network thread: keep it quick.
     */
    public void decoder(String className, Function fn) {
        if (closed) throw new IllegalStateException("script " + name + " was stopped");
        if (className == null || className.isBlank()) throw new IllegalArgumentException("decoder needs a class name");
        decoders.add(ObjectModel.addDecoder(className, (value, tree) -> {
            if (closed) return null;
            try {
                Object result = host.callDecoder(fn, value, tree);
                return result == null ? null : ObjectModel.toTree(plain(result), DECODED_DEPTH);
            } catch (RuntimeException e) {
                reportError("decoder " + className, e, null);
                return null;
            }
        }));
    }

    /** How deep a decoder's own tree is shown (its values are often live objects to tree in turn). */
    private static final int DECODED_DEPTH = 8;

    /** A reader over bytes (a byte array, a buffer or a hex string), for decoders: see {@link Reader}. */
    public Reader reader(Object bytes) {
        return Reader.of(ScriptHost.toJava(bytes));
    }

    /** A writer that builds bytes as the game writes them, for replaceFrame and inject: see {@link Writer}. */
    public Writer writer() {
        return new Writer();
    }

    public void schedule(double delayMillis, Function fn) {
        if (closed) throw new IllegalStateException("script " + name + " was stopped");
        pending.removeIf(ScheduledFuture::isDone);
        pending.add(timers.schedule(() -> invoke("timer", fn), (long) delayMillis, TimeUnit.MILLISECONDS));
    }

    /** Writes {@code message} to the game log and the event log as {@code {"kind":"script_log", "script":..., "message":...}}. */
    public void log(Object message) {
        Object value = ScriptHost.toJava(message);
        String text = value instanceof String s ? s : EventLog.toJson(jsonSafe(value, 4));
        Sculk.LOG.info("[{}] {}", name, text);
        Map<String, Object> e = Sculk.entry("script_log", Map.of());
        e.put("script", name);
        e.put("message", text);
        engine.log(e);
    }

    /**
     * Shows {@code message} to the player only: a local chat line in a game
     * client (never sent to the server), the console in a dedicated server.
     * Recorded as {@code {"kind":"chat", "script":..., "text":...}}.
     */
    public void chat(Object message) {
        Object value = ScriptHost.toJava(message);
        engine.chat(value instanceof String s ? s : EventLog.toJson(jsonSafe(value, 4)), name);
    }

    /** Writes {@code data} (a JS object) to the event log as {@code {"kind":"script", "script":..., "data":...}}. */
    public void emit(Object data) {
        Map<String, Object> e = Sculk.entry("script", Map.of());
        e.put("script", name);
        e.put("data", plain(ScriptHost.toJava(data)));
        engine.log(e);
    }

    /** What the packets have said about the game: entities, menus, who we are. See {@link Game}. */
    public Game getGame() {
        Game g = game;
        if (g == null) game = g = new Game(engine, scope);
        return g;
    }

    private volatile Game game;

    /** This script's name, as the script list shows it. */
    public String getName() {
        return name;
    }

    /** "client" in a game client (and its integrated server), "server" in a dedicated server. */
    public String getDist() {
        return engine.platform().dedicatedServer() ? "server" : "client";
    }

    /** Another script's top-level scope, to read or call its globals; null if it is not loaded. */
    public Object scope(String script) {
        return manager.scopeOf(script);
    }

    public List<SculkConnection> connections() {
        return engine.connections();
    }

    /** The open connection with this id (as in {@code conn.id}), or null. */
    public SculkConnection connection(int id) {
        for (SculkConnection c : engine.connections()) {
            if (c.getId() == id) return c;
        }
        return null;
    }

    public List<Map<String, Object>> payloadTypes() {
        return engine.payloadTypes();
    }

    public String hex(byte[] bytes) {
        return Bytes.hex(bytes);
    }

    public byte[] copy(byte[] bytes) {
        return bytes.clone();
    }

    public byte[] unhex(String hex) {
        return Bytes.unhex(hex);
    }

    /** A plain tree view of any object (records, mod payloads, packets). */
    public Object tree(Object value) {
        return ScriptHost.jsTree(ObjectModel.toTree(ScriptHost.toJava(value), 6), scope);
    }

    /**
     * {@link #tree(Object)} with options: {@code depth} (default 6) and
     * {@code nodes}, how many values the tree may hold (default
     * {@link ObjectModel#TREE_NODES}, as in the event log), for a full view
     * of something big. The live object is never cut; only its tree is.
     */
    public Object tree(Object value, Object options) {
        int depth = 6, nodes = ObjectModel.TREE_NODES;
        if (ScriptHost.toJava(options) instanceof Map<?, ?> o) {
            if (o.get("depth") instanceof Number n) depth = n.intValue();
            if (o.get("nodes") instanceof Number n) nodes = n.intValue();
        }
        return ScriptHost.jsTree(ObjectModel.toTree(ScriptHost.toJava(value), depth, nodes), scope);
    }

    /** A copy of a record with one component replaced, for editing decoded payloads. */
    public Object with(Object record, String component, Object value) throws ReflectiveOperationException {
        return ObjectModel.withComponent(ScriptHost.toJava(record), component, ScriptHost.toJava(value));
    }

    // ---- plumbing ----

    private void invoke(String what, Function fn, Object... args) {
        if (closed) return;
        try {
            host.call(fn, args);
        } catch (RuntimeException e) {
            reportError(what, e, args.length > 0 && args[0] instanceof PacketEvent pe ? pe : null);
        }
    }

    private void reportError(String what, RuntimeException e, PacketEvent packet) {
        Sculk.LOG.error("script {}: {} callback failed", name, what, e);
        Map<String, Object> entry = Sculk.entry("script_error", Map.of());
        entry.put("script", name);
        entry.put("callback", what);
        entry.put("error", e instanceof RhinoException re ? describe(re) : e.toString());
        if (packet != null) entry.put("packet", packet.toString());
        engine.log(entry);
    }

    /** Converts an eval result to something {@link EventLog#toJson} can always serialise. */
    static Object jsonSafe(Object value, int depth) {
        value = ScriptHost.toJava(value);
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return String.valueOf(d);
            return value instanceof Double && d == Math.rint(d) && Math.abs(d) < 1e15 ? (Object) (long) d : value;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (depth <= 0) return "{...}";
            for (Map.Entry<?, ?> en : m.entrySet()) out.put(String.valueOf(en.getKey()), jsonSafe(en.getValue(), depth - 1));
            return out;
        }
        if (value instanceof List<?> l) {
            if (depth <= 0) return "[...]";
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(jsonSafe(o, depth - 1));
            return out;
        }
        if (value instanceof Scriptable) return value instanceof Function ? "[function]" : "[" + ((Scriptable) value).getClassName() + "]";
        // Opaque Java objects (connections, channels) reach far; keep them shallow. sculk.tree(x) goes deeper.
        return jsonSafe(ObjectModel.toTree(value, Math.min(depth, 2)), depth);
    }

    /** Deep-copies Rhino's Map/List views into ordinary collections for serialisation. */
    @SuppressWarnings("unchecked")
    static Object plain(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> en : ((Map<Object, Object>) m).entrySet()) {
                out.put(String.valueOf(en.getKey()), plain(ScriptHost.toJava(en.getValue())));
            }
            return out;
        }
        if (value instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) out.add(plain(ScriptHost.toJava(o)));
            return out;
        }
        // JS numbers are doubles; show whole ones as integers, but only where a long holds them exactly.
        if (value instanceof Double d && d == Math.rint(d) && Math.abs(d) < 1e15) return d.longValue();
        return value;
    }
}
