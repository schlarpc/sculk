package sculk.core.packet;

import java.util.Objects;
import sculk.core.net.Direction;
import sculk.core.net.SculkConnection;
import sculk.core.platform.PacketInfo;

/**
 * One packet crossing a connection, as seen at the game's codec.
 *
 * <p>Listeners may change what happens to it by calling {@link #drop()},
 * {@link #replaceFrame(byte[])} or {@link #replacePacket(Object)} before the
 * hook returns. Getters use JavaBean names so scripts can write {@code e.type}.
 */
public final class PacketEvent {
    public enum Action { PASS, DROP, REPLACE_FRAME, REPLACE_PACKET }

    private final long sequence;
    private final long timestampMillis;
    private final SculkConnection connection;
    private final Direction direction;
    private final Object packet;
    private final byte[] frame;
    private final PacketInfo info;
    private final String owner;
    private final boolean injected;
    private final Object codec;

    private Action action = Action.PASS;
    private byte[] replacementFrame;
    private Object replacementPacket;

    public PacketEvent(
            long sequence,
            SculkConnection connection,
            Direction direction,
            Object packet,
            byte[] frame,
            PacketInfo info,
            String owner,
            boolean injected,
            Object codec) {
        this.sequence = sequence;
        this.timestampMillis = System.currentTimeMillis();
        this.connection = Objects.requireNonNull(connection);
        this.direction = Objects.requireNonNull(direction);
        this.packet = packet;
        this.frame = frame;
        this.info = Objects.requireNonNull(info);
        this.owner = owner;
        this.injected = injected;
        this.codec = codec;
    }

    public long getSequence() { return sequence; }
    public long getTimestampMillis() { return timestampMillis; }
    public SculkConnection getConnection() { return connection; }
    public Direction getDirection() { return direction; }
    public String getDir() { return direction.id(); }
    public String getSide() { return connection.side().id(); }
    public Object getPacket() { return packet; }
    /** The uncompressed, unencrypted frame (packet id + body), or null if never serialised. */
    public byte[] getFrame() { return frame; }
    public PacketInfo getInfo() { return info; }
    public String getState() { return info.state(); }
    public String getType() { return info.type(); }
    public String getChannel() { return info.payloadChannel(); }
    public Object getPayload() { return info.payload(); }
    public String getOwner() { return owner; }
    public boolean isInjected() { return injected; }
    public Object getCodec() { return codec; }

    public void drop() {
        action = Action.DROP;
    }

    public void replaceFrame(byte[] newFrame) {
        action = Action.REPLACE_FRAME;
        replacementFrame = newFrame.clone();
    }

    public void replacePacket(Object newPacket) {
        action = Action.REPLACE_PACKET;
        replacementPacket = Objects.requireNonNull(newPacket);
    }

    public Action action() { return action; }
    public byte[] replacementFrame() { return replacementFrame; }
    public Object replacementPacket() { return replacementPacket; }

    @Override
    public String toString() {
        return connection.side().id() + " " + direction.id() + " " + info.state() + " " + info.type()
                + (info.payloadChannel() != null ? " [" + info.payloadChannel() + " owner=" + owner + "]" : "")
                + (frame != null ? " " + frame.length + "B" : "")
                + (injected ? " (injected)" : "");
    }
}
