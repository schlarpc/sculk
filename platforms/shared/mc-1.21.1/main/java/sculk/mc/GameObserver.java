package sculk.mc;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import sculk.core.game.GameState;

/**
 * Feeds a connection's {@link GameState} from the vanilla packets that say
 * something about the game ({@code Platform.observe}): entities spawned
 * and removed, players listed, menus opened and closed, the login.
 */
public final class GameObserver {
    private GameObserver() {}

    public static void observe(Object packet, GameState s) {
        if (packet instanceof ClientboundAddEntityPacket p) {
            s.addEntity(p.getId(), String.valueOf(p.getUUID()), key(BuiltInRegistries.ENTITY_TYPE, p.getType()), p.getX(), p.getY(), p.getZ());
        } else if (packet instanceof ClientboundRemoveEntitiesPacket p) {
            p.getEntityIds().forEach((int id) -> s.removeEntity(id));
        } else if (packet instanceof ClientboundPlayerInfoUpdatePacket p) {
            for (var e : p.entries()) {
                if (e.profile() != null) s.playerName(String.valueOf(e.profileId()), e.profile().getName());
            }
        } else if (packet instanceof ClientboundOpenScreenPacket p) {
            s.openMenu(p.getContainerId(), key(BuiltInRegistries.MENU, p.getType()), p.getTitle().getString());
        } else if (packet instanceof ClientboundContainerClosePacket p) {
            s.closeMenu(p.getContainerId());
        } else if (packet instanceof ServerboundContainerClosePacket p) {
            s.closeMenu(p.getContainerId());
        } else if (packet instanceof ClientboundLoginPacket p) {
            s.login(p.playerId());
        } else if (packet instanceof ClientboundRespawnPacket) {
            s.respawn();
        } else if (packet instanceof ClientboundGameProfilePacket p) {
            s.self(String.valueOf(p.gameProfile().getId()), p.gameProfile().getName());
        }
    }

    /** A registry entry's "ns:path", or its toString if it is not registered. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String key(Registry registry, Object value) {
        Object k = value == null ? null : registry.getKey(value);
        return k != null ? k.toString() : String.valueOf(value);
    }
}
