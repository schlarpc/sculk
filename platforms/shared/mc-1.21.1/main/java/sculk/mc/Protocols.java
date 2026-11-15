package sculk.mc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.Locale;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import sculk.core.platform.PacketInfo;

/**
 * The Platform methods that are the same for every loader on this Minecraft
 * version (protocol era C): every packet, mod payloads included, is encoded
 * and decoded by the codec's {@link ProtocolInfo}.
 */
public final class Protocols {
    private Protocols() {}

    public static PacketInfo describe(Object packet, Object codec) {
        ProtocolInfo<?> info = protocolInfo(codec);
        Packet<?> p = (Packet<?>) packet;
        String state = info == null ? "?" : info.id().id();
        String flow = (info == null ? p.type().flow() : info.flow()).name().toLowerCase(Locale.ROOT);
        String type = p.type().id().toString();
        CustomPacketPayload payload = null;
        if (p instanceof ServerboundCustomPayloadPacket c) payload = c.payload();
        else if (p instanceof ClientboundCustomPayloadPacket c) payload = c.payload();
        String channel = payload == null ? null : payload.type().id().toString();
        return new PacketInfo(state, flow, type, channel, payload, null);
    }

    @SuppressWarnings("unchecked")
    public static byte[] encode(Object packet, Object codec) {
        StreamCodec<ByteBuf, Object> streamCodec = (StreamCodec<ByteBuf, Object>) (Object) requireInfo(codec).codec();
        ByteBuf buf = Unpooled.buffer();
        try {
            streamCodec.encode(buf, packet);
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    public static Object decode(byte[] frame, Object codec) {
        return requireInfo(codec).codec().decode(Unpooled.wrappedBuffer(frame));
    }

    /** The payload carries its own type, so the channel is only checked. */
    public static Object payloadPacket(String channel, Object payload, boolean clientbound) {
        CustomPacketPayload p = (CustomPacketPayload) payload;
        String own = p.type().id().toString();
        if (!own.equals(channel)) throw new IllegalArgumentException("payload " + p.getClass().getName() + " belongs on " + own + ", not " + channel);
        return clientbound ? new ClientboundCustomPayloadPacket(p) : new ServerboundCustomPayloadPacket(p);
    }

    private static ProtocolInfo<?> protocolInfo(Object codec) {
        return codec instanceof ProtocolInfoAccess access ? access.sculk$protocolInfo() : null;
    }

    private static ProtocolInfo<?> requireInfo(Object codec) {
        ProtocolInfo<?> info = protocolInfo(codec);
        if (info == null) throw new IllegalStateException("no protocol info available from codec " + codec);
        return info;
    }
}
