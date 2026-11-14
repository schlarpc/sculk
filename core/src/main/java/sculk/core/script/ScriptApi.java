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
 * {@code drop()}, {@code replaceFrame(bytes)}, {@code replacePacket(obj)}.
 * Connections ({@code e.conn}) expose {@code id, side, remote} and
 * {@code inject(dir, bytes)}, {@code injectPacket(dir, obj)}, {@code replay(event)}.
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

    /** Listeners this script has registered and not removed. */
    int listenerCount() {
        return packetListeners.size() + connectionListeners.size();
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
        for (ScheduledFuture<?> f : pending) f.cancel(false);
        pending.clear();
    }

    // ---- script-visible API ----

    public void on(String event, Function fn) {
        if (closed) throw new IllegalStateException("script " + name + " was stopped");
        switch (event) {
            case "packet" -> {
                Consumer<PacketEvent> l = e -> invoke("packet", fn, e);
                packetListeners.add(l);
                engine.addPacketListener(l);
            }
            case "connection" -> {
                Consumer<SculkConnection> l = c -> invoke("connection", fn, c);
                connectionListeners.add(l);
                engine.addConnectionListener(l);
            }
            default -> throw new IllegalArgumentException("unknown event '" + event + "' (expected packet, connection)");
        }
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

    /** Writes {@code data} (a JS object) to the event log as {@code {"kind":"script", "script":..., "data":...}}. */
    public void emit(Object data) {
        Map<String, Object> e = Sculk.entry("script", Map.of());
        e.put("script", name);
        e.put("data", plain(ScriptHost.toJava(data)));
        engine.log(e);
    }

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
        return ObjectModel.toTree(ScriptHost.toJava(value), 6);
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
            Sculk.LOG.error("script {}: {} callback failed", name, what, e);
            Map<String, Object> entry = Sculk.entry("script_error", Map.of());
            entry.put("script", name);
            entry.put("callback", what);
            entry.put("error", e instanceof RhinoException re ? describe(re) : e.toString());
            if (args.length > 0 && args[0] instanceof PacketEvent pe) entry.put("packet", pe.toString());
            engine.log(entry);
        }
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
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) return d.longValue();
        return value;
    }
}
