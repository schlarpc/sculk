package sculk.neoforge;

import io.netty.channel.Channel;
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
 * NeoForge 26.3: protocol era C, unobfuscated. The protocol code is shared
 * with Fabric 26.3 ({@code platforms/shared/mc-26.3}); this class holds
 * what is NeoForge's own: mods, config, commands, chat.
 */
final class NeoForgePlatform implements Platform {
    /** Sculk's config, from {@link SculkConfig}. */
    volatile sculk.core.Settings.Source settings;

    @Override
    public sculk.core.Settings.Source settings() {
        return settings;
    }

    private final Map<Class<?>, String> modByClass = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public java.nio.file.Path configDir() {
        return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean dedicatedServer() {
        return FMLLoader.getCurrent().getDist().isDedicatedServer();
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
        return "neoforge";
    }

    @Override
    public String minecraftVersion() {
        return FMLLoader.getCurrent().getVersionInfo().mcVersion();
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
    public Object readGame(io.netty.buffer.ByteBuf buf, String type, io.netty.channel.Channel channel) {
        return sculk.mc.GameBytes.read(buf, type, channel, dedicatedServer());
    }

    @Override
    public void observe(Object packet, PacketInfo info, sculk.core.game.GameState state) {
        sculk.mc.GameObserver.observe(packet, state);
        // Mods open menus with extra data (AE2 does) through NeoForge's own payload, not the vanilla packet.
        if (info.payload() instanceof net.neoforged.neoforge.network.payload.AdvancedOpenScreenPayload p) {
            state.openMenu(p.windowId(), sculk.mc.GameObserver.key(net.minecraft.core.registries.BuiltInRegistries.MENU, p.menuType()),
                    p.name().getString());
        }
    }

    @Override
    public void writeGame(io.netty.buffer.ByteBuf buf, String type, Object value, io.netty.channel.Channel channel) {
        sculk.mc.GameBytes.write(buf, type, value, channel, dedicatedServer());
    }

    @Override
    public Set<String> modIds() {
        return ModList.get().getMods().stream().map(m -> m.getModId()).collect(Collectors.toSet());
    }

    @Override
    public String modOf(Class<?> cls) {
        String mod = modByClass.computeIfAbsent(cls, NeoForgePlatform::lookup);
        return mod.isEmpty() ? null : mod;
    }

    /** FML 12 has no per-mod JPMS modules: find the mod file whose contents hold the class file. */
    private static String lookup(Class<?> cls) {
        // Hidden classes (lambdas) are not resources; use their nest host.
        String name = cls.getNestHost().getName();
        int slash = name.indexOf('/');
        if (slash >= 0) name = name.substring(0, slash);
        String resource = name.replace('.', '/') + ".class";
        for (IModFileInfo file : ModList.get().getModFiles()) {
            if (!file.getMods().isEmpty() && file.getFile().getContents().containsFile(resource)) {
                return file.getMods().get(0).getModId();
            }
        }
        return "";
    }

}
