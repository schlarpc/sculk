package sculk.fabric;

import io.netty.channel.Channel;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import sculk.core.Sculk;
import sculk.core.model.ObjectModel;
import sculk.core.platform.ChatLine;
import sculk.core.platform.PacketInfo;
import sculk.core.platform.Platform;
import sculk.mc.Chat;
import sculk.mc.GameTrees;
import sculk.mc.Protocols;

/**
 * Fabric 26.3: protocol era C, unobfuscated. The protocol code is shared with
 * NeoForge 26.3 ({@code platforms/shared/mc-26.3}); class to mod lookup goes
 * through Fabric Loader's mod containers (there are no JPMS modules).
 */
final class FabricPlatform implements Platform {
    private static final String NONE = "";
    private final Map<Class<?>, String> modByClass = new ConcurrentHashMap<>();

    @Override
    public java.nio.file.Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean dedicatedServer() {
        return FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.SERVER;
    }

    @Override
    public void registerCommands() {
        SculkCommands.register(dedicatedServer());
    }

    @Override
    public void chat(ChatLine line) {
        if (dedicatedServer()) Platform.super.chat(line);
        else ClientChat.show(line);
    }

    /** Separate so that a dedicated server never loads client classes. */
    private static final class ClientChat {
        static void show(ChatLine chatLine) {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) {
                Sculk.LOG.info("[chat] {}", chatLine.plain()); // The game window is not up yet.
                return;
            }
            Component line = Chat.tagged(chatLine);
            // Chat belongs to the render thread; a local line, never sent to the server.
            mc.execute(() -> mc.gui.hud.getChat().addClientSystemMessage(line));
        }
    }

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
        return Protocols.describe(packet, codec);
    }

    @Override
    public byte[] encode(Object packet, Object codec) {
        return Protocols.encode(packet, codec);
    }

    @Override
    public Object decode(byte[] frame, Object codec, Channel channel) {
        return Protocols.decode(frame, codec);
    }

    @Override
    public Object payloadPacket(String channel, Object payload, boolean clientbound) {
        return Protocols.payloadPacket(channel, payload, clientbound);
    }

    @Override
    public ObjectModel.Renderer renderer() {
        return new GameTrees();
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
}
