package sculk.forge;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.network.simple.IndexedMessageCodec;
import sculk.core.payload.PayloadRegistration;
import sculk.core.payload.PayloadRegistry;
import sculk.core.platform.PacketInfo;
import sculk.core.platform.PayloadCodec;
import sculk.core.platform.Platform;
import sculk.forge.mixin.IndexedMessageCodecAccessor;
import sculk.forge.mixin.MessageHandlerAccessor;

/**
 * Forge 1.20.1: protocol era A. Packets have no ids of their own; the
 * protocol state lives on the channel; mod payloads are opaque bytes on a
 * channel, which for SimpleChannel start with a one-byte message discriminator.
 */
final class ForgePlatform implements Platform {
    private volatile Map<String, String> modByModule;

    @Override
    public String loader() {
        return "forge";
    }

    @Override
    public String minecraftVersion() {
        return FMLLoader.versionInfo().mcVersion();
    }

    @Override
    public PacketInfo describe(Object packet, Object codec) {
        Packet<?> p = (Packet<?>) packet;
        ConnectionProtocol protocol = ConnectionProtocol.getProtocolForPacket(p);
        PacketFlow flow = flow(codec, protocol, p);
        String state = protocol == null ? "?" : protocol.name().toLowerCase(Locale.ROOT);
        String name = p.getClass().getName();
        String type = name.substring(name.lastIndexOf('.') + 1);

        ResourceLocation channel = null;
        FriendlyByteBuf data = null;
        if (p instanceof ServerboundCustomPayloadPacket c) {
            channel = c.getIdentifier();
            data = c.getData();
        } else if (p instanceof ClientboundCustomPayloadPacket c) {
            channel = c.getIdentifier();
            data = c.getData();
        }
        if (channel == null) return new PacketInfo(state, flowId(flow), type, null, null, null);

        byte[] bytes = ByteBufUtil.getBytes(data);
        Object payload = null;
        PayloadCodec payloadCodec = payloadCodec(channel.toString());
        if (payloadCodec != null) {
            try {
                payload = payloadCodec.decode(bytes);
            } catch (Exception e) {
                payload = null;
            }
        }
        return new PacketInfo(state, flowId(flow), type, channel.toString(), payload, bytes);
    }

    @Override
    public byte[] encode(Object packet, Object codec) {
        Packet<?> p = (Packet<?>) packet;
        ConnectionProtocol protocol = ConnectionProtocol.getProtocolForPacket(p);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(protocol.getPacketId(flow(codec, protocol, p), p));
            p.write(buf);
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    @Override
    public Object decode(byte[] frame, Object codec, Channel channel) {
        ConnectionProtocol protocol = channel.attr(Connection.ATTRIBUTE_PROTOCOL).get();
        if (!(codec instanceof FlowAccess access)) throw new IllegalStateException("no flow available from codec " + codec);
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(frame));
        int id = buf.readVarInt();
        Packet<?> packet = protocol.createPacket(access.sculk$flow(), id, buf);
        if (packet == null) throw new IllegalArgumentException("bad packet id " + id + " in " + protocol);
        return packet;
    }

    @Override
    public PayloadCodec payloadCodec(String channel) {
        for (PayloadRegistration reg : PayloadRegistry.forChannel(channel)) {
            if (reg.codec() instanceof IndexedMessageCodec codec) return new SimpleChannelCodec(codec);
        }
        return null;
    }

    @Override
    public Set<String> modIds() {
        return ModList.get().getMods().stream().map(m -> m.getModId()).collect(Collectors.toSet());
    }

    @Override
    public String modOf(Class<?> cls) {
        String module = cls.getModule().getName();
        if (module == null) return null;
        Map<String, String> map = modByModule;
        if (map == null || !map.containsKey(module)) {
            map = new HashMap<>();
            for (IModFileInfo file : ModList.get().getModFiles()) {
                if (!file.getMods().isEmpty()) {
                    map.put(file.getFile().getSecureJar().name(), file.getMods().get(0).getModId());
                }
            }
            modByModule = map;
        }
        return map.get(module);
    }

    private static PacketFlow flow(Object codec, ConnectionProtocol protocol, Packet<?> p) {
        if (codec instanceof FlowAccess access) return access.sculk$flow();
        return protocol != null && protocol.getPacketId(PacketFlow.CLIENTBOUND, p) >= 0
                ? PacketFlow.CLIENTBOUND
                : PacketFlow.SERVERBOUND;
    }

    private static String flowId(PacketFlow flow) {
        return flow.name().toLowerCase(Locale.ROOT);
    }

    /** A SimpleChannel's own encoders/decoders, keyed by the discriminator byte. */
    private record SimpleChannelCodec(IndexedMessageCodec codec) implements PayloadCodec {
        @Override
        @SuppressWarnings("unchecked")
        public Object decode(byte[] data) {
            short discriminator = (short) (data[0] & 0xFF);
            Object handler = ((IndexedMessageCodecAccessor) codec).sculk$indicies().get(discriminator);
            if (handler == null) throw new IllegalArgumentException("unknown discriminator " + discriminator);
            Optional<Function<FriendlyByteBuf, Object>> decoder = ((MessageHandlerAccessor) handler).sculk$decoder();
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data, 1, data.length - 1));
            return decoder.orElseThrow(() -> new IllegalStateException("message has no decoder")).apply(buf);
        }

        @Override
        public byte[] encode(Object message) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            try {
                codec.build(message, buf);
                return ByteBufUtil.getBytes(buf);
            } finally {
                buf.release();
            }
        }
    }
}
