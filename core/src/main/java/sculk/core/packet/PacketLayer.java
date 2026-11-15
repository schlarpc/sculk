package sculk.core.packet;

import java.util.LinkedHashMap;
import java.util.Map;
import sculk.core.model.ObjectModel;
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
    /**
     * What the packet carries: for a custom payload, the decoded payload
     * (for {@code replacePayload}; null when nothing decoded it), else the
     * packet object itself.
     */
    public Object getData() { return info.isCustomPayload() ? info.payload() : object; }
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

    /** The layer as logged, with {@code data} as an {@link ObjectModel} tree at most {@code treeDepth} deep. */
    public Map<String, Object> toJson(int treeDepth) {
        return toJson(treeDepth, true);
    }

    /** The layer, with or without {@code data} (the costly part). */
    public Map<String, Object> toJson(int treeDepth, boolean withData) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", getId());
        m.put("state", getState());
        m.put("flow", getFlow());
        m.put("type", getType());
        m.put("javaClass", getJavaClass());
        m.put("owner", owner);
        if (!withData) return m;
        Object data = getData();
        m.put("data", data == null ? null : ObjectModel.toTree(data, treeDepth));
        return m;
    }
}
