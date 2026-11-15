package sculk.core.script;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import sculk.core.Sculk;
import sculk.core.util.Bytes;

/**
 * {@code sculk.writer()}: builds bytes the way the game's
 * {@code FriendlyByteBuf} writes them, for {@code replaceFrame} and
 * {@code conn.inject}. The counterpart of {@link Reader}, with the same
 * names; each write returns the writer, so calls chain:
 * {@code sculk.writer().varInt(0x19).string('ae2:x').i32(5).toBytes()}.
 */
public final class Writer {
    private final ByteBuf buf = Unpooled.buffer();

    Writer() {}

    public Writer bool(boolean v) { buf.writeBoolean(v); return this; }
    public Writer i8(int v) { buf.writeByte(v); return this; }
    public Writer u8(int v) { buf.writeByte(v); return this; }
    public Writer i16(int v) { buf.writeShort(v); return this; }
    public Writer u16(int v) { buf.writeShort(v); return this; }
    public Writer i32(int v) { buf.writeInt(v); return this; }
    /** A long: a number, or a string for one past 2^53 (as {@link Reader#i64} gives them). */
    public Writer i64(Object v) { buf.writeLong(toLong(v)); return this; }
    public Writer f32(double v) { buf.writeFloat((float) v); return this; }
    public Writer f64(double v) { buf.writeDouble(v); return this; }

    public Writer varInt(int v) {
        while ((v & ~0x7F) != 0) {
            buf.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf.writeByte(v);
        return this;
    }

    public Writer varLong(Object value) {
        long v = toLong(value);
        while ((v & ~0x7FL) != 0) {
            buf.writeByte((int) (v & 0x7F) | 0x80);
            v >>>= 7;
        }
        buf.writeByte((int) v);
        return this;
    }

    /** A string: VarInt length in bytes, then UTF-8. */
    public Writer string(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        varInt(b.length);
        buf.writeBytes(b);
        return this;
    }

    public Writer uuid(String s) {
        UUID u = UUID.fromString(s);
        buf.writeLong(u.getMostSignificantBits());
        buf.writeLong(u.getLeastSignificantBits());
        return this;
    }

    /** Raw bytes: a byte array or a hex string. */
    public Writer bytes(Object bytes) { buf.writeBytes(raw(bytes)); return this; }

    /** A VarInt length, then the bytes. */
    public Writer byteArray(Object bytes) {
        byte[] b = raw(bytes);
        varInt(b.length);
        buf.writeBytes(b);
        return this;
    }

    // ---- game types, through the platform ----

    /** An NBT compound: the game's tag, or SNBT text such as '{a:1b}'. */
    public Writer nbt(Object tag) { return game("nbt", tag); }
    /** A resource location: "ns:path" or the game's own. */
    public Writer identifier(Object id) { return game("identifier", id); }
    /** A block position: {x, y, z} or the game's BlockPos. */
    public Writer blockPos(Object pos) { return game("blockPos", pos); }
    /** An item stack (the game's ItemStack, from a reader or a packet), written as the game writes optional stacks. */
    public Writer itemStack(Object stack) { return game("itemStack", stack); }
    /** A text component: the game's, or a string for plain text. */
    public Writer component(Object text) { return game("component", text); }

    /** A value of a game type by name, as {@link Reader#read}. */
    public Writer write(String type, Object value) { return game(type, value); }

    private Writer game(String type, Object value) {
        Sculk.get().platform().writeGame(buf, type, ScriptHost.toJava(value), Sculk.currentChannel());
        return this;
    }

    // ---- the result ----

    /** Bytes written so far. */
    public int length() { return buf.readableBytes(); }

    /** What has been written, as a byte array (for replaceFrame, inject, sculk.reader). */
    public byte[] toBytes() {
        byte[] out = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), out);
        return out;
    }

    public String hex() { return Bytes.hex(toBytes()); }

    private static byte[] raw(Object bytes) {
        Object v = ScriptHost.toJava(bytes);
        if (v instanceof byte[] b) return b;
        if (v instanceof CharSequence hex) return Bytes.unhex(hex.toString());
        throw new IllegalArgumentException("expected bytes or a hex string, not " + (v == null ? "null" : v.getClass().getName()));
    }

    private static long toLong(Object v) {
        v = ScriptHost.toJava(v);
        if (v instanceof CharSequence s) return Long.parseLong(s.toString());
        if (v instanceof Number n) return n instanceof Double d ? (long) (double) d : n.longValue();
        throw new IllegalArgumentException("expected a number or a numeric string, not " + v);
    }
}
