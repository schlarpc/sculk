package sculk.core.packet;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sculk.core.net.Direction;
import sculk.core.util.Bytes;

/**
 * The {@code frame} layer of a packet event: the bytes on the wire
 * (uncompressed and unencrypted: packet id, then body), which way they went,
 * and where each field sits in them.
 */
public final class FrameLayer {
    private final long seq;
    private final Direction dir;
    private final byte[] bytes;
    private final String channel;
    private Span[] spans;

    /**
     * @param channel the custom payload channel, if any, so that its bytes
     *                can be told apart from the payload's
     */
    public FrameLayer(long seq, Direction dir, byte[] bytes, String channel) {
        this.seq = seq;
        this.dir = dir;
        this.bytes = bytes;
        this.channel = channel;
    }

    /** sculk's running count of packets seen, in this process. */
    public long getSeq() { return seq; }
    public String getDir() { return dir.id(); }
    public Direction direction() { return dir; }
    /** The frame, or null if the packet was never serialised. */
    public byte[] getBytes() { return bytes; }
    public Integer getLen() { return bytes == null ? null : bytes.length; }
    public String getHex() { return Bytes.hex(bytes); }

    /** Where {@code packet.id}, {@code mod.channel}, {@code mod.data} or {@code packet.body} sit in the frame. */
    public synchronized Span[] getSpans() {
        if (spans == null) spans = spans(bytes, channel).toArray(new Span[0]);
        return spans.clone();
    }

    /** The layer as logged; {@code maxHex} bytes of the frame at most. */
    public Map<String, Object> toJson(int maxHex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", seq);
        m.put("dir", dir.id());
        m.put("len", getLen());
        m.put("hex", Bytes.hex(bytes, maxHex));
        List<Map<String, Object>> s = new ArrayList<>();
        for (Span span : getSpans()) s.add(span.toJson());
        m.put("spans", s);
        return m;
    }

    /**
     * Splits a frame into the packet id and either the body or, for a custom
     * payload whose channel is written as a string right after the id (as in
     * every supported version), the channel and the payload's own bytes.
     */
    static List<Span> spans(byte[] frame, String channel) {
        List<Span> out = new ArrayList<>();
        if (frame == null) return out;
        int idLen = Bytes.varIntLength(frame, 0);
        if (idLen < 0) return out;
        out.add(new Span("packet.id", 0, idLen));
        int pos = idLen;
        if (channel != null) {
            int prefix = Bytes.varIntLength(frame, pos);
            if (prefix > 0) {
                int strLen = Bytes.varInt(frame, pos);
                int start = pos + prefix;
                byte[] expected = channel.getBytes(StandardCharsets.UTF_8);
                if (strLen == expected.length && start + strLen <= frame.length
                        && Arrays.equals(frame, start, start + strLen, expected, 0, expected.length)) {
                    out.add(new Span("mod.channel", pos, prefix + strLen));
                    pos = start + strLen;
                    if (pos < frame.length) out.add(new Span("mod.data", pos, frame.length - pos));
                    return out;
                }
            }
        }
        if (pos < frame.length) out.add(new Span("packet.body", pos, frame.length - pos));
        return out;
    }
}
