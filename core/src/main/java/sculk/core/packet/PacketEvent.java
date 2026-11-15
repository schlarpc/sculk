package sculk.core.packet;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import sculk.core.net.Direction;
import sculk.core.net.SculkConnection;
import sculk.core.platform.PacketInfo;

/**
 * One packet crossing a connection, as seen at the game's codec.
 *
 * <p>Scripts see the same tree the event log and the control server show,
 * so a path means the same thing in a filter and in code:
 * <pre>
 *   kind, t, injected, action
 *   conn    {id, side, remote}            the live connection: also inject(), replay()
 *   frame   {seq, dir, len, hex, spans}   scripts also get frame.bytes
 *   packet  {id, state, flow, type, javaClass, owner, data}   scripts also get packet.object
 *   mod     {channel, owner, registeredVia, javaClass, roundTrip, roundTripDetail}   custom payloads only
 * </pre>
 * {@code packet.data} is what the packet carries: a custom payload's
 * decoded payload, else the packet itself.
 * Shorthands, as in filters: {@code dir side owner type channel len}.
 * {@code owner} is the mod layer's owner when there is one, else the packet's.
 *
 * <p>Listeners may change what happens to the packet by calling
 * {@link #drop()}, {@link #replaceFrame(byte[])} or
 * {@link #replacePacket(Object)} or {@link #replacePayload(Object)} before
 * the hook returns.
 */
public final class PacketEvent {
    public enum Outcome { PASS, DROP, REPLACE_FRAME, REPLACE_PACKET }

    private final long timestampMillis;
    private final SculkConnection connection;
    private final Object codec;
    private final boolean injected;
    private final FrameLayer frame;
    private final PacketLayer packet;
    private final ModLayer mod;

    private Outcome outcome = Outcome.PASS;
    private byte[] replacementFrame;
    private Object replacementPacket;

    public PacketEvent(SculkConnection connection, Object codec, boolean injected,
            FrameLayer frame, PacketLayer packet, ModLayer mod) {
        this.timestampMillis = System.currentTimeMillis();
        this.connection = Objects.requireNonNull(connection);
        this.codec = codec;
        this.injected = injected;
        this.frame = Objects.requireNonNull(frame);
        this.packet = Objects.requireNonNull(packet);
        this.mod = mod;
    }

    // ---- the tree ----

    public String getKind() { return "packet"; }
    public long getT() { return timestampMillis; }
    public SculkConnection getConn() { return connection; }
    public FrameLayer getFrame() { return frame; }
    public PacketLayer getPacket() { return packet; }
    /** The custom payload layer, or null for other packets. */
    public ModLayer getMod() { return mod; }
    public boolean isInjected() { return injected; }
    /** What listeners decided so far: {@code pass}, {@code drop} or {@code replace}. */
    public String getAction() {
        return switch (outcome) {
            case PASS -> "pass";
            case DROP -> "drop";
            case REPLACE_FRAME, REPLACE_PACKET -> "replace";
        };
    }

    // ---- shorthands, the same as the filter language's ----

    public String getDir() { return frame.getDir(); }
    public String getSide() { return connection.getSide(); }
    public String getOwner() { return mod != null ? mod.getOwner() : packet.getOwner(); }
    public String getType() { return packet.getType(); }
    public String getChannel() { return mod == null ? null : mod.getChannel(); }
    public Integer getLen() { return frame.getLen(); }

    // ---- actions ----

    public void drop() {
        outcome = Outcome.DROP;
    }

    public void replaceFrame(byte[] newFrame) {
        outcome = Outcome.REPLACE_FRAME;
        replacementFrame = newFrame.clone();
    }

    public void replacePacket(Object newPacket) {
        outcome = Outcome.REPLACE_PACKET;
        replacementPacket = Objects.requireNonNull(newPacket);
    }

    /**
     * Replaces a custom payload packet with one carrying {@code payload} on
     * the same channel: usually an edited copy of {@code packet.data}
     * ({@code sculk.with}). The payload is encoded with the mod's own
     * codec; on a channel whose round trip is {@code mismatch} that may
     * not send what was decoded, so this logs a warning (once per channel).
     */
    public void replacePayload(Object payload) throws Exception {
        if (mod == null) throw new IllegalStateException("not a custom payload: " + this);
        if ("mismatch".equals(mod.getRoundTrip())) sculk.core.Sculk.get().warnOnce("replacePayload:" + mod.getChannel(),
                "replacePayload on " + mod.getChannel() + ", whose payloads do not re-encode faithfully (round trip: mismatch; "
                        + mod.getRoundTripDetail() + "): what is sent may not be what you edited. replaceFrame(bytes) sends exact bytes.");
        replacePacket(sculk.core.Sculk.get().platform()
                .payloadPacket(mod.getChannel(), payload, connection.clientbound(direction())));
    }

    // ---- for the engine ----

    public Direction direction() { return frame.direction(); }
    public byte[] frameBytes() { return frame.getBytes(); }
    public Object packetObject() { return packet.getObject(); }
    public Object codec() { return codec; }
    public Outcome outcome() { return outcome; }
    public byte[] replacementFrame() { return replacementFrame; }
    public Object replacementPacket() { return replacementPacket; }

    /**
     * The event as logged (without {@code id}): {@code maxHex} bytes of frame
     * at most, the decoded payload as a tree at most {@code treeDepth} deep.
     */
    public Map<String, Object> toJson(int maxHex, int treeDepth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "packet");
        m.put("t", timestampMillis);
        m.put("conn", connection.toJson());
        m.put("frame", frame.toJson(maxHex));
        m.put("packet", packet.toJson(treeDepth));
        if (mod != null) m.put("mod", mod.toJson());
        m.put("injected", injected);
        m.put("action", getAction());
        return m;
    }

    /**
     * The event as a filter sees it ({@link #toJson} without an id), but
     * leaving out what costs most unless asked: the decoded tree
     * ({@code packet.data}), the frame hex and the round trip.
     */
    public Map<String, Object> filterView(boolean data, boolean hex, boolean roundTrip, int treeDepth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "packet");
        m.put("t", timestampMillis);
        m.put("conn", connection.toJson());
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("seq", frame.getSeq());
        f.put("dir", frame.getDir());
        f.put("len", frame.getLen());
        if (hex) f.put("hex", frame.getHex());
        m.put("frame", f);
        m.put("packet", packet.toJson(treeDepth, data));
        if (mod != null) {
            Map<String, Object> md = new LinkedHashMap<>();
            md.put("channel", mod.getChannel());
            md.put("owner", mod.getOwner());
            md.put("registeredVia", mod.getRegisteredVia());
            md.put("javaClass", mod.getJavaClass());
            if (roundTrip) {
                md.put("roundTrip", mod.getRoundTrip());
                if (mod.getRoundTripDetail() != null) md.put("roundTripDetail", mod.getRoundTripDetail());
            }
            m.put("mod", md);
        }
        m.put("injected", injected);
        m.put("action", getAction());
        return m;
    }

    @Override
    public String toString() {
        return connection.getSide() + " " + frame.getDir() + " " + packet.getState() + " " + packet.getType()
                + (mod != null ? " [" + mod.getChannel() + " owner=" + mod.getOwner() + "]" : "")
                + (frame.getBytes() != null ? " " + frame.getBytes().length + "B" : "")
                + (injected ? " (injected)" : "");
    }
}
