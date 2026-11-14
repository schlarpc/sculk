package sculk.core.packet;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where one field of an event sits in its frame: bytes {@code [off, off + len)}.
 * {@code field} is the field's path in the event, such as {@code mod.channel}.
 */
public final class Span {
    private final String field;
    private final int off;
    private final int len;

    public Span(String field, int off, int len) {
        this.field = field;
        this.off = off;
        this.len = len;
    }

    public String getField() { return field; }
    public int getOff() { return off; }
    public int getLen() { return len; }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("off", off);
        m.put("len", len);
        return m;
    }

    @Override
    public String toString() {
        return field + "[" + off + "+" + len + "]";
    }
}
