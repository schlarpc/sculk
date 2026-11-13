package sculk.core.util;

import java.util.HexFormat;

public final class Bytes {
    private static final HexFormat HEX = HexFormat.of();

    private Bytes() {}

    public static String hex(byte[] bytes) {
        return bytes == null ? null : HEX.formatHex(bytes);
    }

    public static String hex(byte[] bytes, int max) {
        if (bytes == null) return null;
        if (bytes.length <= max) return HEX.formatHex(bytes);
        return HEX.formatHex(bytes, 0, max) + "...";
    }

    public static byte[] unhex(String hex) {
        return HEX.parseHex(hex.replaceAll("\\s+", ""));
    }

    /** Reads the leading VarInt (the packet id) of a frame, or -1 if malformed. */
    public static int leadingVarInt(byte[] frame) {
        int value = 0;
        for (int i = 0; i < Math.min(5, frame.length); i++) {
            int b = frame[i];
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return value;
        }
        return -1;
    }
}
