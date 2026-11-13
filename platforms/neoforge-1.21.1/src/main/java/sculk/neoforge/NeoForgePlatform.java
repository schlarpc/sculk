package sculk.neoforge;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import sculk.core.platform.PacketInfo;
import sculk.core.platform.Platform;

/**
 * NeoForge 1.21.1: protocol era C. Every packet, mod payloads included, is
 * decoded by the game through the codec's {@link ProtocolInfo}.
 */
final class NeoForgePlatform implements Platform {
    private volatile Map<String, String> modByModule;

    @Override
    public String loader() {
        return "neoforge";
    }

    @Override
    public String minecraftVersion() {
        return FMLLoader.versionInfo().mcVersion();
    }

    @Override
    public PacketInfo describe(Object packet, Object codec) {
        ProtocolInfo<?> info = protocolInfo(codec);
        Packet<?> p = (Packet<?>) packet;
        String state = info == null ? "?" : info.id().id();
        String flow = info == null ? p.type().flow().name().toLowerCase(Locale.ROOT) : info.flow().name().toLowerCase(Locale.ROOT);
        String type = p.type().id().toString();
        CustomPacketPayload payload = null;
        if (p instanceof ServerboundCustomPayloadPacket c) payload = c.payload();
        else if (p instanceof ClientboundCustomPayloadPacket c) payload = c.payload();
        String channel = payload == null ? null : payload.type().id().toString();
        return new PacketInfo(state, flow, type, channel, payload, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public byte[] encode(Object packet, Object codec) {
        StreamCodec<ByteBuf, Object> streamCodec = (StreamCodec<ByteBuf, Object>) (Object) requireInfo(codec).codec();
        ByteBuf buf = Unpooled.buffer();
        try {
            streamCodec.encode(buf, packet);
            return ByteBufUtil.getBytes(buf);
        } finally {
            buf.release();
        }
    }

    @Override
    public Object decode(byte[] frame, Object codec, Channel channel) {
        return requireInfo(codec).codec().decode(Unpooled.wrappedBuffer(frame));
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

    private static ProtocolInfo<?> protocolInfo(Object codec) {
        return codec instanceof ProtocolInfoAccess access ? access.sculk$protocolInfo() : null;
    }

    private static ProtocolInfo<?> requireInfo(Object codec) {
        ProtocolInfo<?> info = protocolInfo(codec);
        if (info == null) throw new IllegalStateException("no protocol info available from codec " + codec);
        return info;
    }
}
