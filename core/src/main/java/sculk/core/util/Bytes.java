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
        return varIntLength(frame, 0) < 0 ? -1 : varInt(frame, 0);
    }

    /** Reads the VarInt at {@code off}; check {@link #varIntLength} first. */
    public static int varInt(byte[] bytes, int off) {
        int value = 0;
        for (int i = 0; i < 5 && off + i < bytes.length; i++) {
            int b = bytes[off + i];
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) break;
        }
        return value;
    }

    /** The length in bytes of the VarInt at {@code off}, or -1 if there is no whole one. */
    public static int varIntLength(byte[] bytes, int off) {
        for (int i = 0; i < 5 && off + i < bytes.length; i++) {
            if ((bytes[off + i] & 0x80) == 0) return i + 1;
        }
        return -1;
    }
}
