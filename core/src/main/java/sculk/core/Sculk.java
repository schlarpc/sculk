package sculk.core;

import java.io.IOException;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sculk.core.control.ControlServer;
import sculk.core.control.EventStore;
import sculk.core.control.StoredEvent;
import sculk.core.log.EventLog;
import sculk.core.model.ObjectModel;
import sculk.core.net.Direction;
import sculk.core.net.SculkConnection;
import sculk.core.packet.PacketEvent;
import sculk.core.payload.Attribution;
import sculk.core.payload.PayloadRegistration;
import sculk.core.payload.PayloadRegistry;
import sculk.core.platform.PacketInfo;
import sculk.core.platform.PayloadCodec;
import sculk.core.platform.Platform;
import sculk.core.platform.Side;
import sculk.core.script.ScriptApi;
import sculk.core.script.ScriptHost;
import sculk.core.util.Bytes;

/**
 * Process-wide engine.
 *
 * <p>A platform adapter calls {@link #init(Platform)} once from its mod
 * entrypoint. Its mixins then report connections and packets through
 * {@link sculk.core.net.WireHooks}, which calls back into this class.
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code sculk.eventLog}: path of a JSON Lines event log to write;</li>
 *   <li>{@code sculk.scripts}: script files to load at startup, separated by
 *       the platform path separator.</li>
 *   <li>{@code sculk.control}: port for the loopback control server (0 picks
 *       a free port); off when unset. See {@link ControlServer};</li>
 *   <li>{@code sculk.controlFile}: where the control server writes its port
 *       and token (default {@code sculk-control.json});</li>
 *   <li>{@code sculk.controlEvents}, {@code sculk.controlFrameBytes}: how many
 *       events, and how many bytes of whole frames, the control server keeps
 *       in memory (defaults 100000 and 256 MiB).</li>
 * </ul>
 */
public final class Sculk {
    public static final String MOD_ID = "sculk";
    public static final Logger LOG = LoggerFactory.getLogger("sculk");

    private static final int TREE_DEPTH = 6;
    private static final int LOG_FRAME_BYTES = 4096;

    private static volatile Sculk instance;

    private final Platform platform;
    private final Attribution attribution;
    private final AtomicLong sequence = new AtomicLong();
    private final List<Consumer<PacketEvent>> packetListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SculkConnection>> connectionListeners = new CopyOnWriteArrayList<>();
    private final List<SculkConnection> connections = new CopyOnWriteArrayList<>();
    private final AtomicBoolean registryLogged = new AtomicBoolean();
    private final EventLog eventLog;
    private final EventStore eventStore;
    /** Guards event ids so that ids, the log file and the store share one order. */
    private final Object logLock = new Object();
    private long lastEventId;
    private final List<String> scriptPaths = new ArrayList<>();
    private ScriptApi scripts;
    private ControlServer control;

    private Sculk(Platform platform, EventLog eventLog, EventStore eventStore) {
        this.platform = platform;
        this.attribution = new Attribution(platform);
        this.eventLog = eventLog;
        this.eventStore = eventStore;
    }

    public static synchronized Sculk init(Platform platform) {
        Objects.requireNonNull(platform, "platform");
        if (instance != null) {
            throw new IllegalStateException("sculk already initialised by " + instance.platform.describe());
        }
        EventLog log = null;
        String logPath = System.getProperty("sculk.eventLog");
        if (logPath != null && !logPath.isBlank()) {
            try {
                log = new EventLog(Path.of(logPath));
            } catch (IOException e) {
                LOG.error("cannot open event log {}", logPath, e);
            }
        }
        String controlPort = System.getProperty("sculk.control");
        boolean controlOn = controlPort != null && !controlPort.isBlank();
        EventStore store = controlOn
                ? new EventStore(
                        Integer.getInteger("sculk.controlEvents", 100_000),
                        Long.getLong("sculk.controlFrameBytes", 256L << 20))
                : null;
        Sculk sculk = new Sculk(platform, log, store);
        instance = sculk;
        LOG.info("sculk core initialised on {}", platform.describe());
        LOG.info("scripting: {}", ScriptHost.selfTest());
        sculk.log(entry("start", Map.of("platform", platform.describe())));
        String paths = System.getProperty("sculk.scripts");
        if (paths != null) {
            for (String p : paths.split(File.pathSeparator)) {
                if (!p.isBlank()) sculk.scriptPaths.add(p.trim());
            }
        }
        sculk.loadStartupScripts();
        if (controlOn) {
            try {
                Path file = Path.of(System.getProperty("sculk.controlFile", "sculk-control.json"));
                sculk.control = new ControlServer(sculk, store, Integer.parseInt(controlPort.trim()), file);
            } catch (IOException | RuntimeException e) {
                LOG.error("cannot start control server on port {}", controlPort, e);
            }
        }
        return sculk;
    }

    /** Returns the engine, or {@code null} if no platform has initialised it yet. */
    public static Sculk get() {
        return instance;
    }

    public Platform platform() { return platform; }
    public Attribution attribution() { return attribution; }
    public List<SculkConnection> connections() { return new ArrayList<>(connections); }
    /** The control server, or null when it is off or failed to start. */
    public ControlServer control() { return control; }

    public void addPacketListener(Consumer<PacketEvent> listener) { packetListeners.add(listener); }
    public void addConnectionListener(Consumer<SculkConnection> listener) { connectionListeners.add(listener); }
    public void removePacketListener(Consumer<PacketEvent> listener) { packetListeners.remove(listener); }
    public void removeConnectionListener(Consumer<SculkConnection> listener) { connectionListeners.remove(listener); }

    // ---- hooks (called via WireHooks) ----

    public void onConnectionActive(SculkConnection conn) {
        if (connections.contains(conn)) return;
        connections.add(conn);
        conn.channel().closeFuture().addListener(f -> {
            connections.remove(conn);
            log(entry("disconnect", Map.of("conn", conn.getId(), "side", conn.side().id())));
        });
        LOG.info("[{}] connection #{} {} active", conn.side(), conn.getId(), conn.getRemoteAddress());
        Map<String, Object> e = entry("connection", Map.of());
        e.put("conn", conn.getId());
        e.put("side", conn.side().id());
        e.put("remote", String.valueOf(conn.getRemoteAddress()));
        e.put("pipeline", conn.channel().pipeline().names());
        log(e);
        if (registryLogged.compareAndSet(false, true)) logPayloadTypes();
        for (Consumer<SculkConnection> l : connectionListeners) {
            try {
                l.accept(conn);
            } catch (RuntimeException ex) {
                LOG.error("connection listener failed", ex);
            }
        }
    }

    public PacketEvent onPacket(
            SculkConnection conn, Direction dir, Object packet, byte[] frame, Object codec, boolean injected) {
        PacketInfo info;
        try {
            info = platform.describe(packet, codec);
        } catch (RuntimeException e) {
            info = new PacketInfo("?", "?", packet.getClass().getName(), null, null, null);
        }
        String owner = ownerOf(packet, info);
        PacketEvent event =
                new PacketEvent(sequence.incrementAndGet(), conn, dir, packet, frame, info, owner, injected, codec);
        for (Consumer<PacketEvent> l : packetListeners) {
            try {
                l.accept(event);
            } catch (RuntimeException ex) {
                LOG.error("packet listener failed for {}", event, ex);
            }
        }
        if (logging()) logPacket(event);
        return event;
    }

    /** Records a packet that sculk injected past the codec, so no codec hook will see it. */
    public void recordInjected(SculkConnection conn, Direction dir, Object packet, byte[] frame, Object codec) {
        try {
            if (packet == null && frame != null && codec != null) packet = platform.decode(frame, codec, conn.channel());
            if (frame == null && packet != null && codec != null) frame = platform.encode(packet, codec);
        } catch (Exception e) {
            LOG.debug("could not complete injected packet record", e);
        }
        if (packet == null) {
            Map<String, Object> e = entry("packet", Map.of());
            e.put("conn", conn.getId());
            e.put("side", conn.side().id());
            e.put("dir", dir.id());
            e.put("injected", true);
            e.put("len", frame == null ? 0 : frame.length);
            e.put("hex", Bytes.hex(frame, LOG_FRAME_BYTES));
            log(e, frame);
            return;
        }
        onPacket(conn, dir, packet, frame, codec, true);
    }

    /** Guesses our side from a packet's flow when the connection hook did not fire. */
    public Side guessSide(Object packet, Object codec, boolean inbound) {
        try {
            String flow = platform.describe(packet, codec).flow();
            boolean clientbound = "clientbound".equals(flow);
            return clientbound == inbound ? Side.CLIENT : Side.SERVER;
        } catch (RuntimeException e) {
            return Side.CLIENT;
        }
    }

    // ---- attribution ----

    private String ownerOf(Object packet, PacketInfo info) {
        if (info.isCustomPayload()) return attribution.owner(info.payloadChannel(), info.payload());
        String mod = platform.modOf(packet.getClass());
        return mod == null ? "minecraft" : mod;
    }

    // ---- event log ----

    public static Map<String, Object> entry(String kind, Map<String, Object> fields) {
        Map<String, Object> e = EventLog.entry(kind);
        e.putAll(fields);
        return e;
    }

    /** True when anything records events (the event log file or the control server's store). */
    public boolean logging() {
        return eventLog != null || eventStore != null;
    }

    public void log(Map<String, Object> entry) {
        log(entry, null);
    }

    /**
     * Records an entry, giving it the next {@code id}. {@code frame}, for
     * packets, is the whole frame, kept in memory for the control server
     * (the entry itself may carry a shortened hex copy).
     */
    public void log(Map<String, Object> entry, byte[] frame) {
        if (!logging()) return;
        synchronized (logLock) {
            long id = ++lastEventId;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", id);
            e.putAll(entry);
            String line = EventLog.line(e);
            if (eventLog != null) eventLog.writeLine(line);
            if (eventStore != null) eventStore.add(new StoredEvent(id, e, line, frame == null ? null : frame.clone()));
        }
    }

    /** The id of the most recent event, or 0 before any. */
    public long lastEventId() {
        synchronized (logLock) {
            return lastEventId;
        }
    }

    private void logPacket(PacketEvent ev) {
        PacketInfo info = ev.getInfo();
        Map<String, Object> e = entry("packet", Map.of());
        e.put("seq", ev.getSequence());
        e.put("conn", ev.getConnection().getId());
        e.put("side", ev.getSide());
        e.put("dir", ev.getDir());
        e.put("state", info.state());
        e.put("flow", info.flow());
        e.put("type", info.type());
        e.put("class", ev.getPacket().getClass().getName());
        e.put("owner", ev.getOwner());
        e.put("injected", ev.isInjected());
        e.put("action", ev.action().name().toLowerCase());
        byte[] frame = ev.getFrame();
        e.put("len", frame == null ? null : frame.length);
        e.put("packetId", frame == null ? null : Bytes.leadingVarInt(frame));
        if (info.isCustomPayload()) {
            e.put("channel", info.payloadChannel());
            e.put("registeredVia", attribution.registeredVia(info.payloadChannel()));
            Object payload = info.payload();
            e.put("payloadClass", payload == null ? null : payload.getClass().getName());
            e.put("payload", payload == null ? null : ObjectModel.toTree(payload, TREE_DEPTH));
            e.put("payloadHex", Bytes.hex(info.payloadBytes(), LOG_FRAME_BYTES));
            e.put("hex", Bytes.hex(frame, LOG_FRAME_BYTES));
            e.put("roundTrip", roundTrip(ev));
        } else {
            e.put("hex", Bytes.hex(frame, 256));
        }
        log(e, frame);
    }

    /**
     * Re-serialises a decoded custom payload packet with the game's (and the
     * mod's) codecs and compares it with the bytes on the wire:
     * <ul>
     *   <li>{@code ok}: byte-identical;</li>
     *   <li>{@code equivalent}: different bytes that decode to the same
     *       content once collection order is ignored, typically hash-ordered
     *       sets and maps re-encoded in a different iteration order;</li>
     *   <li>{@code mismatch: ...}: re-encoding changes meaning, so edited
     *       objects would not be sent faithfully.</li>
     * </ul>
     */
    private String roundTrip(PacketEvent ev) {
        if (ev.getFrame() == null || ev.getCodec() == null) return "n/a";
        try {
            PacketInfo info = ev.getInfo();
            byte[] again = platform.encode(ev.getPacket(), ev.getCodec());
            if (!Arrays.equals(again, ev.getFrame())) {
                Object redecoded = platform.decode(again, ev.getCodec(), ev.getConnection().channel());
                Object payloadAgain = platform.describe(redecoded, ev.getCodec()).payload();
                if (info.payload() != null
                        && (info.payload().equals(payloadAgain)
                                || ObjectModel.canonical(info.payload()).equals(ObjectModel.canonical(payloadAgain)))) {
                    return "equivalent";
                }
                return "mismatch: packet " + Bytes.hex(again, 128);
            }
            if (info.payloadBytes() != null) {
                PayloadCodec codec = platform.payloadCodec(info.payloadChannel());
                if (codec == null || info.payload() == null) return "ok (opaque payload)";
                byte[] payloadAgain = codec.encode(info.payload());
                if (!Arrays.equals(payloadAgain, info.payloadBytes())) {
                    return codec.decode(payloadAgain).equals(info.payload())
                            ? "equivalent"
                            : "mismatch: payload " + Bytes.hex(payloadAgain, 128);
                }
            }
            return "ok";
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    public List<Map<String, Object>> payloadTypes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PayloadRegistration r : PayloadRegistry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", r.channel());
            m.put("protocol", r.protocol());
            m.put("flow", r.flow());
            m.put("discriminator", r.discriminator());
            m.put("messageClass", r.messageClass());
            m.put("codecClass", r.codec() == null ? null : r.codec().getClass().getName());
            m.put("owner", attribution.owner(r.channel(), null));
            m.put("registeredVia", attribution.registeredVia(r.channel()));
            out.add(m);
        }
        return out;
    }

    private void logPayloadTypes() {
        for (Map<String, Object> m : payloadTypes()) log(entry("payload_type", m));
    }

    // ---- scripting ----

    public synchronized ScriptApi scripts() {
        if (scripts == null) scripts = new ScriptApi(this);
        return scripts;
    }

    /** The startup scripts ({@code sculk.scripts}), in load order. */
    public List<String> scriptPaths() {
        return List.copyOf(scriptPaths);
    }

    /**
     * Drops all script state (globals, listeners, pending timers) and loads
     * the startup scripts again from disk. Returns
     * {@code {"ok":bool,"loaded":[...],"errors":[{"script":...,"error":...}]}}.
     */
    public Map<String, Object> reloadScripts() {
        ScriptApi old;
        synchronized (this) {
            old = scripts;
            scripts = null;
        }
        if (old != null) old.close();
        Map<String, Object> result = loadStartupScripts();
        log(entry("script_reload", result));
        return result;
    }

    private Map<String, Object> loadStartupScripts() {
        List<String> loaded = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        ScriptApi api = scripts();
        for (String p : scriptPaths) {
            Path path = Path.of(p);
            try {
                api.load(path.toString(), Files.readString(path));
                LOG.info("loaded script {}", path);
                loaded.add(path.toString());
            } catch (IOException | RuntimeException e) {
                LOG.error("failed to load script {}", path, e);
                log(entry("script_error", Map.of("script", path.toString(), "error", e.toString())));
                errors.add(Map.of("script", path.toString(), "error", e.toString()));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", errors.isEmpty());
        result.put("loaded", loaded);
        result.put("errors", errors);
        return result;
    }
}
