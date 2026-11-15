package sculk.mc;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;

/**
 * Game types for {@code sculk.reader} ({@code Platform.readGame}), read
 * with the game's own codecs. Item stacks and text need the registries of
 * a connection in play: the one whose packet is being handled, else (in a
 * game client) the client's own.
 */
public final class Reads {
    private Reads() {}

    public static Object read(ByteBuf buf, String type, Channel channel, boolean dedicatedServer) {
        // FriendlyByteBuf delegates to buf, so reads move buf's reader index.
        FriendlyByteBuf f = new FriendlyByteBuf(buf);
        return switch (type) {
            case "nbt" -> f.readNbt();
            case "identifier" -> f.readIdentifier();
            case "blockPos" -> f.readBlockPos();
            case "itemStack" -> ItemStack.OPTIONAL_STREAM_CODEC.decode(withRegistries(buf, channel, dedicatedServer));
            case "component" -> ComponentSerialization.TRUSTED_STREAM_CODEC.decode(withRegistries(buf, channel, dedicatedServer));
            default -> throw new IllegalArgumentException(
                    "unknown game type " + type + " (nbt, identifier, blockPos, itemStack, component)");
        };
    }

    private static RegistryFriendlyByteBuf withRegistries(ByteBuf buf, Channel channel, boolean dedicatedServer) {
        RegistryAccess registries = registries(channel, dedicatedServer);
        if (registries == null) throw new IllegalStateException("no game registries yet: no connection is in play");
        return new RegistryFriendlyByteBuf(buf, registries);
    }

    private static RegistryAccess registries(Channel channel, boolean dedicatedServer) {
        if (channel != null && channel.pipeline().get("packet_handler") instanceof Connection connection) {
            PacketListener listener = connection.getPacketListener();
            if (listener instanceof ServerGamePacketListenerImpl server) return server.player.registryAccess();
            if (!dedicatedServer) {
                RegistryAccess client = ClientSide.of(listener);
                if (client != null) return client;
            }
        }
        return dedicatedServer ? null : ClientSide.current();
    }

    /** Separate so that a dedicated server never loads client classes. */
    private static final class ClientSide {
        static RegistryAccess of(PacketListener listener) {
            return listener instanceof net.minecraft.client.multiplayer.ClientPacketListener c ? c.registryAccess() : null;
        }

        static RegistryAccess current() {
            var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
            return connection == null ? null : connection.registryAccess();
        }
    }
}
