package sculk.core.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
 * The {@code sculk} global that scripts see.
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
    private final List<Consumer<PacketEvent>> packetListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Consumer<SculkConnection>> connectionListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private final ScriptHost host = new ScriptHost();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sculk-script-timer");
        t.setDaemon(true);
        return t;
    });

    public ScriptApi(Sculk engine) {
        this.engine = engine;
        host.bind("sculk", this);
    }

    public void load(String name, String source) {
        host.eval(name, source);
    }

    public Object eval(String source) {
        return host.eval("eval", source);
    }

    /**
     * Evaluates {@code source} for an interactive caller and never throws:
     * {@code {"ok":true,"result":<JSON-safe value>}} or
     * {@code {"ok":false,"error":"..."}}. Java objects in the result are
     * shown as {@link ObjectModel} trees.
     */
    public Map<String, Object> evalForRepl(String source) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> audit = Sculk.entry("eval", Map.of());
        audit.put("source", source.length() > 4096 ? source.substring(0, 4096) + "..." : source);
        engine.log(audit);
        try {
            Object value = host.eval("repl", source);
            out.put("ok", true);
            out.put("result", jsonSafe(value, 6));
        } catch (RhinoException e) {
            out.put("ok", false);
            out.put("error", e.details() + " (" + e.sourceName() + ":" + e.lineNumber() + ")");
        } catch (RuntimeException | LinkageError e) {
            out.put("ok", false);
            out.put("error", e.toString());
        }
        return out;
    }

    /** Stops this API: its listeners are removed and its pending timers cancelled. */
    @Override
    public void close() {
        closed = true;
        for (Consumer<PacketEvent> l : packetListeners) engine.removePacketListener(l);
        for (Consumer<SculkConnection> l : connectionListeners) engine.removeConnectionListener(l);
        packetListeners.clear();
        connectionListeners.clear();
        timers.shutdownNow();
    }

    // ---- script-visible API ----

    public void on(String event, Function fn) {
        if (closed) throw new IllegalStateException("script API was reloaded");
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
        timers.schedule(() -> invoke("timer", fn), (long) delayMillis, TimeUnit.MILLISECONDS);
    }

    public void log(Object message) {
        Sculk.LOG.info("[script] {}", message);
    }

    /** Writes {@code data} (a JS object) to the event log as {@code {"kind":"script", ...}}. */
    public void emit(Object data) {
        Map<String, Object> e = Sculk.entry("script", Map.of());
        e.put("data", plain(ScriptHost.toJava(data)));
        engine.log(e);
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
            Sculk.LOG.error("script {} callback failed", what, e);
            Map<String, Object> entry = Sculk.entry("script_error", Map.of());
            entry.put("callback", what);
            entry.put("error", e.toString());
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
