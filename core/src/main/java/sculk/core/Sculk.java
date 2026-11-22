package sculk.core;

import java.io.IOException;
import java.io.File;
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
import sculk.core.command.SculkCommand;
import sculk.core.control.ControlServer;
import sculk.core.control.EventStore;
import sculk.core.control.StoredEvent;
import sculk.core.log.EventLog;
import sculk.core.model.ObjectModel;
import sculk.core.net.Direction;
import sculk.core.net.SculkConnection;
import sculk.core.packet.FrameLayer;
import sculk.core.packet.ModLayer;
import sculk.core.packet.PacketEvent;
import sculk.core.packet.PacketLayer;
import sculk.core.payload.Attribution;
import sculk.core.payload.PayloadRegistration;
import sculk.core.payload.PayloadRegistry;
import sculk.core.platform.ChatLine;
import sculk.core.platform.PacketInfo;
import sculk.core.platform.PayloadCodec;
import sculk.core.platform.Platform;
import sculk.core.platform.Side;
import sculk.core.script.ScriptManager;
import sculk.core.script.Transpiler;
import sculk.core.script.ScriptHost;
import sculk.core.util.Bytes;

/**
 * Process-wide engine.
 *
 * <p>A platform adapter calls {@link #init(Platform)} once from its mod
 * entrypoint. Its mixins then report connections and packets through
 * {@link sculk.core.net.WireHooks}, which calls back into this class.
 *
 * <p>Settings are in {@link Settings}: the loader's config file for sculk,
 * each overridable with a system property {@code -Dsculk.<key>}. Two more
 * are system properties only, for dev runs: {@code sculk.scripts}, more
 * script files to load at startup (separated by the platform path
 * separator), and {@code sculk.watchMillis}, how often to poll the script
 * files (default 500).
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
    private ScriptManager scripts;
    private ControlServer control;
    private sculk.core.remote.RemoteAccess remote;

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
        Settings.use(platform.settings());
        EventLog log = null;
        String logPath = Settings.optional("eventLog");
        if (logPath != null) {
            try {
                log = new EventLog(Path.of(logPath));
            } catch (IOException e) {
                LOG.error("cannot open event log {}", logPath, e);
            }
        }
        String controlPort = Settings.string("control").trim();
        boolean controlOn = !java.util.Set.of("off", "false", "no", "none").contains(controlPort.toLowerCase(java.util.Locale.ROOT));
        EventStore store = null;
        if (controlOn) {
            long captureBytes = Settings.longValue("captureBytes");
            java.nio.file.Path captureDir = null;
            if (captureBytes > 0) {
                try {
                    String parent = Settings.optional("captureDir");
                    captureDir = EventStore.newCaptureDir(Path.of(parent != null ? parent : System.getProperty("java.io.tmpdir")));
                } catch (IOException e) {
                    LOG.error("cannot make a capture directory; keeping recent events in memory only", e);
                }
            }
            store = new EventStore(
                    Settings.intValue("controlEvents"),
                    Settings.longValue("controlFrameBytes"),
                    captureDir, captureBytes);
        }
        // Babel takes Rhino a second or two to load: start it first, while the game loads.
        Transpiler.preload(platform.configDir().resolve("sculk").resolve("cache").resolve("babel"));
        Sculk sculk = new Sculk(platform, log, store);
        ObjectModel.setRenderer(platform.renderer());
        instance = sculk;
        LOG.info("sculk core initialised on {}", platform.describe());
        LOG.info("scripting: {}", ScriptHost.selfTest());
        try {
            platform.registerCommands();
        } catch (RuntimeException | LinkageError e) {
            LOG.error("cannot register the /sculk command", e);
        }
        sculk.log(entry("start", Map.of("platform", platform.describe())));
        List<Path> extra = new ArrayList<>();
        String paths = System.getProperty("sculk.scripts");
        if (paths != null) {
            for (String p : paths.split(File.pathSeparator)) {
                if (!p.isBlank()) extra.add(Path.of(p.trim()));
            }
        }
        String dir = Settings.optional("scriptDir");
        Path scriptDir = dir != null ? Path.of(dir) : platform.configDir().resolve("sculk").resolve("scripts");
        sculk.scripts = new ScriptManager(sculk, scriptDir, extra);
        LOG.info("scripts in {}", sculk.scripts.dir());
        Map<String, Object> started = sculk.scripts.startAll();
        LOG.info("started scripts: {}", started.get("loaded"));
        if (Settings.bool("watch")) sculk.scripts.watch(Long.getLong("sculk.watchMillis", 500));
        if (controlOn) {
            try {
                Path file = Path.of(Settings.string("controlFile").trim());
                String token = Settings.optional("controlToken");
                int[] ports = controlPort.isEmpty() ? ControlServer.DEFAULT_PORTS : new int[] {Integer.parseInt(controlPort)};
                String address = Settings.optional("controlAddress");
                sculk.control = new ControlServer(sculk, store, address == null ? null : java.net.InetAddress.getByName(address), ports, file,
                        token == null || token.isBlank() ? null : token.trim());
                if (platform.dedicatedServer()) {
                    for (String line : SculkCommand.remoteUiHelp(sculk.control, null)) LOG.info(line);
                }
            } catch (IOException | RuntimeException e) {
                LOG.error("cannot start control server on {}", controlPort.isEmpty() ? "any port" : "port " + controlPort, e);
            }
        }
        if (Settings.bool("remote")) {
            try {
                Path base = platform.configDir().resolve("sculk");
                sculk.remote = new sculk.core.remote.RemoteAccess(base.resolve("remote"), base.resolve("cache"),
                        Settings.intValue("remotePort"), line -> sculk.chat(line, null), () -> {
                            ControlServer c = sculk.control;
                            if (c == null) return null;
                            java.net.URI uri = java.net.URI.create(c.url() + "/");
                            String token = c.token();
                            return new sculk.core.remote.Tunnel.Upstream() {
                                @Override
                                public java.net.URI base() {
                                    return uri;
                                }

                                @Override
                                public String token() {
                                    return token;
                                }
                            };
                        });
                LOG.info("remote access: {} (each new browser needs /sculk remote accept)", sculk.remote.link());
            } catch (IOException | RuntimeException e) {
                LOG.error("cannot start remote access", e);
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
    /** Remote access, or null when it is off or failed to start. */
    public sculk.core.remote.RemoteAccess remote() { return remote; }

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
            log(entry("disconnect", Map.of("conn", conn.toJson())));
        });
        LOG.info("[{}] connection #{} {} active", conn.sideEnum(), conn.getId(), conn.getRemoteAddress());
        Map<String, Object> c = conn.toJson();
        c.put("pipeline", conn.channel().pipeline().names());
        log(entry("connection", Map.of("conn", c)));
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
        PacketEvent event = event(conn, dir, packet, frame, info, codec, injected);
        if (packet != null) {
            try {
                platform.observe(packet, info, conn.gameState());
            } catch (RuntimeException | LinkageError e) {
                LOG.debug("could not follow game state from {}", packet.getClass().getName(), e);
            }
        }
        io.netty.channel.Channel outer = CURRENT_CHANNEL.get();
        CURRENT_CHANNEL.set(conn.channel());
        try {
            for (Consumer<PacketEvent> l : packetListeners) {
                try {
                    l.accept(event);
                } catch (RuntimeException ex) {
                    LOG.error("packet listener failed for {}", event, ex);
                }
            }
            if (logging()) log(event.toJson(info.isCustomPayload() ? LOG_FRAME_BYTES : 256, TREE_DEPTH), frame);
        } finally {
            CURRENT_CHANNEL.set(outer);
        }
        return event;
    }

    private static final ThreadLocal<io.netty.channel.Channel> CURRENT_CHANNEL = new ThreadLocal<>();

    private final java.util.Set<String> warned = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Logs a {@code warning} event ({@code {kind: "warning", text}}) and the
     * game log line, the first time only for each {@code key}: for advice
     * that would otherwise repeat with every packet.
     */
    public void warnOnce(String key, String text) {
        if (!warned.add(key)) return;
        LOG.warn(text);
        log(entry("warning", Map.of("text", text)));
    }

    /**
     * The channel of the connection whose packet this thread is handling
     * (in listeners, decoders and logging), or null: where a reader of game
     * types finds the registries that connection uses.
     */
    public static io.netty.channel.Channel currentChannel() {
        return CURRENT_CHANNEL.get();
    }

    private PacketEvent event(SculkConnection conn, Direction dir, Object packet, byte[] frame, PacketInfo info,
            Object codec, boolean injected) {
        String modOfClass = packet == null ? null : platform.modOf(packet.getClass());
        PacketLayer packetLayer = new PacketLayer(packet, info, frame, modOfClass == null ? "minecraft" : modOfClass);
        FrameLayer frameLayer = new FrameLayer(sequence.incrementAndGet(), dir, frame, info.payloadChannel());
        ModLayer mod = null;
        PacketEvent[] self = new PacketEvent[1];
        if (info.isCustomPayload()) {
            String channel = info.payloadChannel();
            mod = new ModLayer(channel, attribution.owner(channel, info.payload()), info.payload(), info.payloadBytes(),
                    () -> attribution.registeredVia(channel), () -> roundTrip(self[0], info));
        }
        self[0] = new PacketEvent(conn, codec, injected, frameLayer, packetLayer, mod);
        return self[0];
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
            // Not decodable: only the conn and frame layers are known.
            Map<String, Object> e = entry("packet", Map.of());
            e.put("conn", conn.toJson());
            e.put("frame", new FrameLayer(sequence.incrementAndGet(), dir, frame, null).toJson(LOG_FRAME_BYTES));
            e.put("injected", true);
            e.put("action", "pass");
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
     * Shows {@code text} to the player (see {@link Platform#chat}) and
     * records it as a {@code chat} event. {@code script} is the script that
     * said it, or null when sculk itself did.
     */
    public void chat(String text, String script) {
        chat(ChatLine.of(text), script);
    }

    /** {@link #chat(String, String)} with buttons; the event's {@code text} is {@link ChatLine#plain}. */
    public void chat(ChatLine line, String script) {
        try {
            platform.chat(line);
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("cannot show in chat: {}", line.plain(), e);
        }
        Map<String, Object> e = new LinkedHashMap<>();
        if (script != null) e.put("script", script);
        e.put("text", line.plain());
        log(entry("chat", e));
    }

    /**
     * Records a {@code mark} event: a note dropped into the event stream, so
     * a moment in play can be found among the packets later. {@code by} says
     * where it came from ({@code command} for {@code /sculk mark}). Returns
     * the event's id, or 0 when nothing records events.
     */
    public long mark(String text, String by) {
        LOG.info("mark: {}", text);
        return log(entry("mark", Map.of("text", text, "by", by)), null);
    }

    /**
     * Records an entry, giving it the next {@code id}. {@code frame}, for
     * packets, is the whole frame, kept in memory for the control server
     * (the entry itself may carry a shortened hex copy). Returns the entry's
     * id, or 0 when nothing records events.
     */
    public long log(Map<String, Object> entry, byte[] frame) {
        if (!logging()) return 0;
        synchronized (logLock) {
            long id = ++lastEventId;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", id);
            e.putAll(entry);
            String line = EventLog.line(e);
            if (eventLog != null) eventLog.writeLine(line);
            if (eventStore != null) eventStore.add(new StoredEvent(id, e, line, frame == null ? null : frame.clone()));
            return id;
        }
    }

    /** The id of the most recent event, or 0 before any. */
    public long lastEventId() {
        synchronized (logLock) {
            return lastEventId;
        }
    }

    /**
     * Re-serialises a decoded custom payload packet with the game's (and the
     * mod's) codecs and compares it with the bytes on the wire:
     * <ul>
     *   <li>{@code ok}: byte-identical;</li>
     *   <li>{@code equivalent}: different bytes that decode to the same
     *       content once collection order is ignored, typically hash-ordered
     *       sets and maps re-encoded in a different iteration order;</li>
     *   <li>{@code mismatch}: re-encoding changes meaning, so edited
     *       objects would not be sent faithfully;</li>
     *   <li>{@code opaque}: the packet re-encodes the same, but nothing
     *       decoded the payload to check;</li>
     *   <li>{@code error} or {@code n/a}.</li>
     * </ul>
     * Returns {outcome, detail}; the detail (the re-encoded hex, the error)
     * is null for ok, equivalent and n/a.
     */
    private String[] roundTrip(PacketEvent ev, PacketInfo info) {
        if (ev.frameBytes() == null || ev.codec() == null) return new String[] {"n/a", null};
        try {
            byte[] again = platform.encode(ev.packetObject(), ev.codec());
            if (!Arrays.equals(again, ev.frameBytes())) {
                Object redecoded = platform.decode(again, ev.codec(), ev.getConn().channel());
                Object payloadAgain = platform.describe(redecoded, ev.codec()).payload();
                if (info.payload() != null
                        && (info.payload().equals(payloadAgain)
                                || ObjectModel.canonical(info.payload()).equals(ObjectModel.canonical(payloadAgain)))) {
                    return new String[] {"equivalent", null};
                }
                return new String[] {"mismatch", "re-encoded packet: " + Bytes.hex(again, 128)};
            }
            if (info.payloadBytes() != null) {
                PayloadCodec codec = platform.payloadCodec(info.payloadChannel());
                if (codec == null || info.payload() == null) {
                    return new String[] {"opaque", "the packet re-encodes the same, but nothing decoded the payload to check"};
                }
                byte[] payloadAgain = codec.encode(info.payload());
                if (!Arrays.equals(payloadAgain, info.payloadBytes())) {
                    return codec.decode(payloadAgain).equals(info.payload())
                            ? new String[] {"equivalent", null}
                            : new String[] {"mismatch", "re-encoded payload: " + Bytes.hex(payloadAgain, 128)};
                }
            }
            return new String[] {"ok", null};
        } catch (Exception e) {
            return new String[] {"error", e.toString()};
        }
    }

    public List<Map<String, Object>> payloadTypes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PayloadRegistration r : PayloadRegistry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("channel", r.channel());
            m.put("owner", attribution.owner(r.channel(), null));
            m.put("registeredVia", attribution.registeredVia(r.channel()));
            m.put("javaClass", r.messageClass());
            m.put("codecClass", r.codec() == null ? null : r.codec().getClass().getName());
            m.put("protocol", r.protocol());
            m.put("flow", r.flow());
            m.put("discriminator", r.discriminator());
            out.add(m);
        }
        return out;
    }

    private void logPayloadTypes() {
        for (Map<String, Object> m : payloadTypes()) log(entry("payload_type", Map.of("mod", m)));
    }

    // ---- scripting ----

    public ScriptManager scripts() {
        return scripts;
    }

    /**
     * Drops all script state (globals, listeners, pending timers) and starts
     * the enabled startup scripts again from disk. Returns
     * {@code {"ok":bool,"loaded":[...],"skipped":[...],"errors":[{"script":...,"error":...}]}}.
     */
    public Map<String, Object> reloadScripts() {
        Map<String, Object> result = scripts.reloadAll();
        log(entry("script_reload", result));
        return result;
    }
}
