package sculk.core.script;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import sculk.core.Sculk;

/**
 * {@code sculk.reader(bytes)}: reads a byte blob the way the game's
 * {@code FriendlyByteBuf} does, for script decoders of mod data that a
 * payload keeps as raw bytes. Primitives are read here; game types
 * ({@link #nbt}, {@link #itemStack}, ...) go through the platform, so they
 * come out exactly as the game reads them (where the platform supports it).
 *
 * <p>Method names avoid Java's type names ({@code int}, {@code long}...),
 * which older JavaScript reserves: {@code i32()}, {@code i64()}, and so on.
 * Every read throws if the bytes run out.
 */
public final class Reader {
    private final ByteBuf buf;

    Reader(ByteBuf buf) {
        this.buf = buf;
    }

    /** A reader over {@code source}: a byte array, a Netty buffer (its readable bytes, not consumed) or a hex string. */
    static Reader of(Object source) {
        if (source instanceof byte[] b) return new Reader(Unpooled.wrappedBuffer(b));
        if (source instanceof ByteBuf b) return new Reader(b.duplicate());
        if (source instanceof CharSequence hex) return new Reader(Unpooled.wrappedBuffer(sculk.core.util.Bytes.unhex(hex.toString())));
        throw new IllegalArgumentException("sculk.reader takes bytes, a buffer or a hex string, not "
                + (source == null ? "null" : source.getClass().getName()));
    }

    /** Bytes left to read. */
    public int readable() { return buf.readableBytes(); }
    /** Bytes read so far. */
    public int position() { return buf.readerIndex(); }

    public boolean bool() { return buf.readBoolean(); }
    public int i8() { return buf.readByte(); }
    public int u8() { return buf.readUnsignedByte(); }
    public int i16() { return buf.readShort(); }
    public int u16() { return buf.readUnsignedShort(); }
    public int i32() { return buf.readInt(); }
    /** A long, as a string when a JavaScript number cannot hold it exactly. */
    public Object i64() { return exact(buf.readLong()); }
    public float f32() { return buf.readFloat(); }
    public double f64() { return buf.readDouble(); }

    public int varInt() {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            byte b = buf.readByte();
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return value;
        }
        throw new IllegalStateException("VarInt too big");
    }

    public Object varLong() {
        long value = 0;
        for (int i = 0; i < 10; i++) {
            byte b = buf.readByte();
            value |= (long) (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) return exact(value);
        }
        throw new IllegalStateException("VarLong too big");
    }

    /** A string: VarInt length in bytes, then UTF-8, as the game writes it. */
    public String string() {
        int len = varInt();
        if (len < 0 || len > buf.readableBytes()) throw new IllegalStateException("string of " + len + " bytes, " + buf.readableBytes() + " left");
        String s = buf.toString(buf.readerIndex(), len, StandardCharsets.UTF_8);
        buf.skipBytes(len);
        return s;
    }

    public String uuid() { return new UUID(buf.readLong(), buf.readLong()).toString(); }

    public byte[] bytes(int n) {
        byte[] out = new byte[n];
        buf.readBytes(out);
        return out;
    }

    /** A VarInt length, then that many bytes. */
    public byte[] byteArray() { return bytes(varInt()); }

    /** Everything left. */
    public byte[] rest() { return bytes(buf.readableBytes()); }

    public void skip(int n) { buf.skipBytes(n); }

    // ---- game types, through the platform ----

    /** An NBT compound (or null), as the live tag; trees show it as JSON. */
    public Object nbt() { return game("nbt"); }
    /** A resource location, "ns:path". */
    public Object identifier() { return game("identifier"); }
    /** A packed block position, as the game's BlockPos. */
    public Object blockPos() { return game("blockPos"); }
    /** An item stack (possibly empty), as the game's ItemStack. */
    public Object itemStack() { return game("itemStack"); }
    /** A text component. */
    public Object component() { return game("component"); }

    /** A value of a game type by name (nbt, identifier, blockPos, itemStack, component), where the platform knows it. */
    public Object read(String type) { return game(type); }

    private Object game(String type) {
        return Sculk.get().platform().readGame(buf, type, Sculk.currentChannel());
    }

    private static Object exact(long v) {
        return Math.abs(v) <= (1L << 53) ? (Object) (double) v : Long.toString(v);
    }
}
