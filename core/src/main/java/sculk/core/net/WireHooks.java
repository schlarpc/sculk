package sculk.core.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import sculk.core.Sculk;
import sculk.core.packet.PacketEvent;
import sculk.core.platform.Side;

/**
 * Entry points for the platform mixins on the game's {@code PacketDecoder}
 * and {@code PacketEncoder}. Their {@code decode}/{@code encode} methods are
 * Netty overrides, so the names are the same in every version.
 *
 * <p>Hooking the codecs (rather than inserting handlers at pipeline positions)
 * means every frame is seen exactly once, uncompressed and unencrypted, paired
 * with the object it decodes to or was encoded from, whatever the pipeline
 * layout of the version.
 */
public final class WireHooks {
    private WireHooks() {}

    /**
     * After {@code PacketDecoder.decode}. {@code frameStart} is the reader index
     * of {@code in} and {@code outStart} the size of {@code out}, both captured
     * at method entry.
     */
    public static void afterDecode(
            ChannelHandlerContext ctx, Object decoder, ByteBuf in, int frameStart, List<Object> out, int outStart) {
        Sculk sculk = Sculk.get();
        if (sculk == null || out.size() <= outStart) return;
        try {
            SculkConnection conn = SculkConnection.peek(ctx.channel());
            if (conn == null) conn = SculkConnection.of(ctx.channel(), sculk.guessSide(out.get(outStart), decoder, true));
            conn.decoderCodec(decoder);
            boolean injected = conn.consumeInboundInjection();
            byte[] frame = null;
            if (out.size() - outStart == 1) {
                frame = new byte[in.readerIndex() - frameStart];
                in.getBytes(frameStart, frame);
            }
            for (int i = outStart; i < out.size(); i++) {
                PacketEvent event = sculk.onPacket(conn, Direction.INBOUND, out.get(i), frame, decoder, injected);
                switch (event.outcome()) {
                    case PASS -> {}
                    case DROP -> {
                        out.remove(i);
                        i--;
                    }
                    case REPLACE_PACKET -> out.set(i, event.replacementPacket());
                    case REPLACE_FRAME -> out.set(i, sculk.platform().decode(event.replacementFrame(), decoder, ctx.channel()));
                }
            }
        } catch (Throwable t) {
            Sculk.LOG.error("sculk decode hook failed; packet passed through unmodified", t);
        }
    }

    /**
     * After {@code PacketEncoder.encode}. {@code start} is the writer index of
     * {@code out} at method entry.
     */
    public static void afterEncode(ChannelHandlerContext ctx, Object encoder, Object packet, ByteBuf out, int start) {
        Sculk sculk = Sculk.get();
        if (sculk == null) return;
        try {
            SculkConnection conn = SculkConnection.peek(ctx.channel());
            if (conn == null) conn = SculkConnection.of(ctx.channel(), sculk.guessSide(packet, encoder, false));
            conn.encoderCodec(encoder);
            boolean injected = conn.consumeOutboundInjection(packet);
            byte[] frame = new byte[out.writerIndex() - start];
            out.getBytes(start, frame);
            PacketEvent event = sculk.onPacket(conn, Direction.OUTBOUND, packet, frame, encoder, injected);
            switch (event.outcome()) {
                case PASS -> {}
                // An empty write becomes a zero-length frame, which every
                // supported version's decoder ignores.
                case DROP -> out.writerIndex(start);
                case REPLACE_FRAME -> {
                    out.writerIndex(start);
                    out.writeBytes(event.replacementFrame());
                }
                case REPLACE_PACKET -> {
                    out.writerIndex(start);
                    out.writeBytes(sculk.platform().encode(event.replacementPacket(), encoder));
                }
            }
        } catch (Throwable t) {
            Sculk.LOG.error("sculk encode hook failed; packet passed through unmodified", t);
        }
    }

    /** Called when the game's {@code Connection} becomes active. */
    public static void channelActive(ChannelHandlerContext ctx, Side side) {
        Sculk sculk = Sculk.get();
        if (sculk == null) return;
        try {
            sculk.onConnectionActive(SculkConnection.of(ctx.channel(), side));
        } catch (Throwable t) {
            Sculk.LOG.error("sculk connection hook failed", t);
        }
    }
}
