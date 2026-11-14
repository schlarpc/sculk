package sculk.core.packet;

import java.util.LinkedHashMap;
import java.util.Map;
import sculk.core.platform.PacketInfo;
import sculk.core.util.Bytes;

/** The {@code packet} layer of a packet event: the game's packet, as its protocol knows it. */
public final class PacketLayer {
    private final Object object;
    private final PacketInfo info;
    private final byte[] frame;
    private final String owner;

    public PacketLayer(Object object, PacketInfo info, byte[] frame, String owner) {
        this.object = object;
        this.info = info;
        this.frame = frame;
        this.owner = owner;
    }

    /** The packet object itself, for {@code replacePacket} and {@code injectPacket}. */
    public Object getObject() { return object; }
    /** The packet id on the wire, or null if the packet was never serialised. */
    public Integer getId() {
        if (frame == null) return null;
        int id = Bytes.leadingVarInt(frame);
        return id < 0 ? null : id;
    }
    public String getState() { return info.state(); }
    public String getFlow() { return info.flow(); }
    public String getType() { return info.type(); }
    public String getJavaClass() { return object == null ? null : object.getClass().getName(); }
    /** The mod whose class this packet is, or minecraft. A custom payload's mod is in the mod layer. */
    public String getOwner() { return owner; }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", getId());
        m.put("state", getState());
        m.put("flow", getFlow());
        m.put("type", getType());
        m.put("javaClass", getJavaClass());
        m.put("owner", owner);
        return m;
    }
}
