package sculk.core.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import sculk.core.Sculk;
import sculk.core.model.ObjectModel;
import sculk.core.net.SculkConnection;
import sculk.core.packet.PacketEvent;
import sculk.core.util.Bytes;
import sculk.shaded.org.mozilla.javascript.Function;

/**
 * The {@code sculk} global that scripts see.
 *
 * <pre>{@code
 * sculk.on('packet', e => {
 *   if (e.channel === 'appleskin:exhaustion_sync' && e.dir === 'inbound') e.drop();
 * });
 * sculk.on('connection', c => sculk.log('hello ' + c.side));
 * }</pre>
 *
 * Packet events expose {@code seq, side, dir, state, type, channel, owner,
 * payload, packet, frame, injected, connection} and the methods
 * {@code drop()}, {@code replaceFrame(bytes)}, {@code replacePacket(obj)}.
 * Connections expose {@code id, side, remoteAddress} and
 * {@code inject(dir, bytes)}, {@code injectPacket(dir, obj)}, {@code replay(event)}.
 */
public final class ScriptApi {
    private final Sculk engine;
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

    // ---- script-visible API ----

    public void on(String event, Function fn) {
        switch (event) {
            case "packet" -> engine.addPacketListener(e -> invoke("packet", fn, e));
            case "connection" -> engine.addConnectionListener(c -> invoke("connection", fn, c));
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
