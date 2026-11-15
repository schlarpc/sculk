package sculk.forge;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import sculk.core.platform.PayloadCodec;

/**
 * Vanilla's own custom payload channels, which Forge 1.20.1 leaves as raw
 * bytes (NeoForge and Fabric decode them into objects). These records are
 * sculk's view of them, so they show as trees like any other payload.
 */
final class VanillaPayloads {
    private VanillaPayloads() {}

    /** {@code minecraft:brand}: the client or server brand, such as {@code forge}. */
    public record Brand(String brand) {}

    /**
     * {@code minecraft:register} and {@code minecraft:unregister}: channel
     * names, each followed by a zero byte. This side accepts payloads on the
     * registered channels.
     */
    public record Channels(List<String> channels) {}

    /** A codec for one of vanilla's channels, or null. */
    static PayloadCodec codec(String channel) {
        return switch (channel) {
            case "minecraft:brand" -> BRAND;
            case "minecraft:register", "minecraft:unregister" -> CHANNELS;
            default -> null;
        };
    }

    private static final PayloadCodec BRAND = new PayloadCodec() {
        @Override
        public Object decode(byte[] data) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
            String brand = buf.readUtf();
            if (buf.isReadable()) throw new IllegalArgumentException(buf.readableBytes() + " bytes after the brand");
            return new Brand(brand);
        }

        @Override
        public byte[] encode(Object message) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buf.writeUtf(((Brand) message).brand());
                return ByteBufUtil.getBytes(buf);
            } finally {
                buf.release();
            }
        }
    };

    private static final PayloadCodec CHANNELS = new PayloadCodec() {
        @Override
        public Object decode(byte[] data) {
            List<String> names = new ArrayList<>();
            int start = 0;
            for (int i = 0; i <= data.length; i++) {
                if (i == data.length || data[i] == 0) {
                    if (i > start) names.add(new String(data, start, i - start, StandardCharsets.UTF_8));
                    start = i + 1;
                }
            }
            return new Channels(List.copyOf(names));
        }

        @Override
        public byte[] encode(Object message) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (String name : ((Channels) message).channels()) {
                out.writeBytes(name.getBytes(StandardCharsets.UTF_8));
                out.write(0);
            }
            return out.toByteArray();
        }
    };
}
