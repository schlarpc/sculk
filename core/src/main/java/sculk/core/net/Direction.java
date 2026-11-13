package sculk.core.net;

/** Direction of a packet relative to this process. */
public enum Direction {
    /** Received from the peer: decoded by our side's decoder. */
    INBOUND,
    /** Sent to the peer: encoded by our side's encoder. */
    OUTBOUND;

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
