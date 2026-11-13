package sculk.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import sculk.core.net.WireHooks;
import sculk.core.platform.PacketInfo;
import sculk.core.platform.Platform;
import sculk.core.platform.Side;

/**
 * A miniature "game" for unit tests: a frame format of
 * {@code [packet id byte][utf-8 text]}, where id 1 is a custom payload whose
 * text is {@code channel|body}. The codecs call {@link WireHooks} exactly as
 * the platform mixins do.
 */
public final class FakeGame {
    public record Packet(int id, String text) {}

    static final Path EVENT_LOG;

    static {
        try {
            EVENT_LOG = Files.createTempFile("sculk-test", ".jsonl");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
        System.setProperty("sculk.eventLog", EVENT_LOG.toString());
        Sculk.init(new FakePlatform());
    }

    static Sculk sculk() {
        return Sculk.get();
    }

    static byte[] frame(int id, String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 1];
        out[0] = (byte) id;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    /** Pipeline shaped like the game's: decoder and encoder named as Minecraft names them. */
    static EmbeddedChannel channel(Side side) {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("splitter", new io.netty.channel.ChannelInboundHandlerAdapter());
        ch.pipeline().addLast("decoder", new Decoder());
        ch.pipeline().addLast("prepender", new io.netty.channel.ChannelOutboundHandlerAdapter());
        ch.pipeline().addLast("encoder", new Encoder());
        ch.pipeline().addLast("packet_handler", new io.netty.channel.ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                WireHooks.channelActive(ctx, side);
            }
        });
        ch.pipeline().fireChannelActive();
        return ch;
    }

    static final class Decoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            int frameStart = in.readerIndex();
            int outStart = out.size();
            int id = in.readUnsignedByte();
            String text = in.readCharSequence(in.readableBytes(), StandardCharsets.UTF_8).toString();
            out.add(new Packet(id, text));
            WireHooks.afterDecode(ctx, this, in, frameStart, out, outStart);
        }
    }

    static final class Encoder extends MessageToByteEncoder<Packet> {
        @Override
        protected void encode(ChannelHandlerContext ctx, Packet msg, ByteBuf out) {
            int start = out.writerIndex();
            out.writeBytes(frame(msg.id(), msg.text()));
            WireHooks.afterEncode(ctx, this, msg, out, start);
        }
    }

    static final class FakePlatform implements Platform {
        @Override public String loader() { return "fake"; }
        @Override public String minecraftVersion() { return "0"; }

        @Override
        public PacketInfo describe(Object packet, Object codec) {
            Packet p = (Packet) packet;
            if (p.id() == 1) {
                String[] parts = p.text().split("\\|", 2);
                return new PacketInfo("play", "clientbound", "fake:custom_payload", parts[0], parts.length > 1 ? parts[1] : "", null);
            }
            return new PacketInfo("play", "clientbound", "fake:packet_" + p.id(), null, null, null);
        }

        @Override
        public byte[] encode(Object packet, Object codec) {
            Packet p = (Packet) packet;
            return frame(p.id(), p.text());
        }

        @Override
        public Object decode(byte[] frame, Object codec, io.netty.channel.Channel channel) {
            return new Packet(frame[0], new String(frame, 1, frame.length - 1, StandardCharsets.UTF_8));
        }

        @Override public Set<String> modIds() { return Set.of("minecraft", "testmod", "othermod"); }

        @Override
        public String modOf(Class<?> cls) {
            return cls.getName().startsWith("testmod.") ? "testmod" : null;
        }
    }
}
