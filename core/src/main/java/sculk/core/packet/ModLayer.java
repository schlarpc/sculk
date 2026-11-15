package sculk.core.packet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@code mod} layer of a packet event: a custom payload's channel, the
 * mod it belongs to and how faithfully it re-encodes. What it decoded to
 * is the packet layer's {@code data}. Absent (null) for other packets.
 */
public final class ModLayer {
    private final String channel;
    private final String owner;
    private final Object data;
    private final byte[] opaqueBytes;
    private final Supplier<List<String>> registeredVia;
    private final Supplier<String[]> roundTrip;
    private List<String> registeredViaValue;
    private String[] roundTripValue;

    public ModLayer(String channel, String owner, Object data, byte[] opaqueBytes,
            Supplier<List<String>> registeredVia, Supplier<String[]> roundTrip) {
        this.channel = channel;
        this.owner = owner;
        this.data = data;
        this.opaqueBytes = opaqueBytes;
        this.registeredVia = registeredVia;
        this.roundTrip = roundTrip;
    }

    public String getChannel() { return channel; }
    /** The mod that owns the channel. */
    public String getOwner() { return owner; }
    /** Libraries the channel was registered through, outermost first, ending with the owner. */
    public synchronized List<String> getRegisteredVia() {
        if (registeredViaValue == null) registeredViaValue = List.copyOf(registeredVia.get());
        return registeredViaValue;
    }
    /** The decoded payload's class, or null when the platform could not decode it. */
    public String getJavaClass() { return data == null ? null : data.getClass().getName(); }
    /** On loaders where payloads stay opaque on the wire (Forge 1.20.1), the payload's raw bytes; otherwise null. */
    public byte[] getBytes() { return opaqueBytes; }

    /**
     * Whether re-encoding the decoded payload gives the bytes on the wire:
     * {@code ok}, {@code equivalent} (same content, collections reordered),
     * {@code mismatch} (edited objects would not be sent faithfully),
     * {@code opaque} (nothing decoded the payload to check), {@code error}
     * or {@code n/a}. Worked out on first use.
     */
    public String getRoundTrip() { return roundTrip()[0]; }
    /** What went wrong, for mismatch (the re-encoded hex), error and opaque; else null. */
    public String getRoundTripDetail() { return roundTrip()[1]; }

    private synchronized String[] roundTrip() {
        if (roundTripValue == null) roundTripValue = roundTrip.get();
        return roundTripValue;
    }

    /** The layer as logged. */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel", channel);
        m.put("owner", owner);
        m.put("registeredVia", getRegisteredVia());
        m.put("javaClass", getJavaClass());
        m.put("roundTrip", getRoundTrip());
        if (getRoundTripDetail() != null) m.put("roundTripDetail", getRoundTripDetail());
        return m;
    }
}
