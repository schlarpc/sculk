package sculk.neoforge;

import io.netty.channel.Channel;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import sculk.core.Sculk;
import sculk.mc.Chat;
import sculk.core.model.ObjectModel;
import sculk.core.platform.ChatLine;
import sculk.core.platform.PacketInfo;
import sculk.core.platform.Platform;
import sculk.mc.GameTrees;
import sculk.mc.Protocols;

/**
 * NeoForge 1.21.1: protocol era C. The protocol code is shared with the
 * other 1.21.1 loaders ({@code platforms/shared/mc-1.21.1}); this class holds
 * what is NeoForge's own: mods, config, commands, chat.
 */
final class NeoForgePlatform implements Platform {
    private volatile Map<String, String> modByModule;

    @Override
    public java.nio.file.Path configDir() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean dedicatedServer() {
        return FMLLoader.getDist().isDedicatedServer();
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
            mc.execute(() -> mc.gui.getChat().addMessage(line));
        }
    }

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
}
