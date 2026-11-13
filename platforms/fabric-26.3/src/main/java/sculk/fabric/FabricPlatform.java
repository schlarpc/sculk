package sculk.fabric;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import sculk.core.platform.PacketInfo;
import sculk.core.platform.Platform;

/**
 * Fabric 26.3: protocol era C, unobfuscated. Same shape as NeoForge 1.21.1,
 * except that ResourceLocation is now Identifier and class-to-mod lookup goes
 * through Fabric Loader's mod containers (there are no JPMS modules).
 */
final class FabricPlatform implements Platform {
    private static final String NONE = "";
    private final Map<Class<?>, String> modByClass = new ConcurrentHashMap<>();

    @Override
    public String loader() {
        return "fabric";
    }

    @Override
    public String minecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public PacketInfo describe(Object packet, Object codec) {
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
        return FabricLoader.getInstance().getAllMods().stream()
                .map(m -> m.getMetadata().getId())
                .collect(Collectors.toSet());
    }

    @Override
    public String modOf(Class<?> cls) {
        String mod = modByClass.computeIfAbsent(cls, FabricPlatform::lookup);
        return mod.isEmpty() ? null : mod;
    }

    private static String lookup(Class<?> cls) {
        // Hidden classes (lambdas) are not resources; use their nest host.
        String name = cls.getNestHost().getName();
        int slash = name.indexOf('/');
        if (slash >= 0) name = name.substring(0, slash);
        String resource = name.replace('.', '/') + ".class";
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            if (mod.findPath(resource).isPresent()) return mod.getMetadata().getId();
        }
        return NONE;
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
