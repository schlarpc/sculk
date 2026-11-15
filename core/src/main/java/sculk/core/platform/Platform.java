package sculk.core.platform;

import java.nio.file.Path;
import java.util.Set;

/**
 * The contract between the engine and one (loader, Minecraft version) target.
 *
 * <p>This is the only seam that differs per target. Keep it narrow: anything
 * that can be done with Netty, reflection, or plain Java belongs in core.
 *
 * <p>"Codec" arguments are the game's own {@code PacketDecoder} or
 * {@code PacketEncoder} instance for the connection, which carries the protocol
 * state. They may be null when core has no codec to offer, in which case the
 * platform should do its best or throw.
 */
public interface Platform {
    /** Loader id, for example {@code "neoforge"}, {@code "fabric"} or {@code "forge"}. */
    String loader();

    /** Minecraft version string, for example {@code "1.21.1"}. */
    String minecraftVersion();

    default String describe() {
        return loader() + " " + minecraftVersion();
    }

    /** The game's config directory; sculk keeps its scripts under {@code sculk/scripts} in it. */
    default Path configDir() {
        return Path.of("config");
    }

    /** True in a dedicated server; false in a game client, including its integrated server. */
    default boolean dedicatedServer() {
        return false;
    }

    /**
     * Registers the {@code /sculk} command ({@link sculk.core.command.SculkCommand#tree})
     * with the loader: in a game client as a client command, which works on
     * any server and never reaches it; in a dedicated server for operators
     * ({@link sculk.core.command.SculkCommand#OPERATOR_LEVEL}). Called once
     * from {@code Sculk.init}.
     */
    default void registerCommands() {}

    /**
     * Shows {@code line} to the person at this game, and no one else: in a
     * game client, a line in the local chat that never goes to the server;
     * in a dedicated server, whose person is at the console, the console
     * (as {@link ChatLine#plain}). Its buttons run their commands as the
     * player would.
     * Callable from any thread. See {@code Sculk.chat}.
     */
    default void chat(ChatLine line) {
        org.slf4j.LoggerFactory.getLogger("sculk").info("[chat] {}", line.plain());
    }

    /** Describes a packet object seen at (or destined for) {@code codec}. */
    PacketInfo describe(Object packet, Object codec);

    /** Serialises a packet exactly as {@code codec} would: packet id followed by body, uncompressed. */
    byte[] encode(Object packet, Object codec) throws Exception;

    /**
     * Parses one uncompressed frame (packet id and body) as {@code codec} would.
     * {@code channel} is the connection's channel, for versions that keep the
     * protocol state there rather than in the codec.
     */
    Object decode(byte[] frame, Object codec, io.netty.channel.Channel channel) throws Exception;

    /** The mod's payload codec for {@code channel}, if payloads on it are opaque to the game. */
    default PayloadCodec payloadCodec(String channel) {
        return null;
    }

    /**
     * A custom payload packet carrying {@code payload} (a decoded mod payload
     * object, as in {@code packet.data}) on {@code channel}, travelling
     * clientbound or serverbound: what {@code conn.injectPayload} and
     * {@code e.replacePayload} send. Where payloads are opaque on the wire
     * (Forge 1.20.1), the payload is encoded with the channel's own codec.
     */
    default Object payloadPacket(String channel, Object payload, boolean clientbound) throws Exception {
        throw new UnsupportedOperationException(describe() + " cannot build custom payload packets");
    }

    /**
     * Views of this game's types for {@link sculk.core.model.ObjectModel}
     * trees (NBT, item stacks, identifiers, positions, text...), or null to
     * show everything by reflection.
     */
    default sculk.core.model.ObjectModel.Renderer renderer() {
        return null;
    }

    /**
     * Reads one value of a game type from {@code buf} as the game's own
     * codec does, for {@code sculk.reader}: {@code nbt}, {@code identifier},
     * {@code blockPos}, {@code itemStack}, {@code component}. Types that need
     * the game's registries (item stacks, text) take them from the
     * connection on {@code channel} (the one whose packet is being handled;
     * may be null) or, failing that, from the game. Throws
     * IllegalArgumentException for a type this platform cannot read.
     */
    default Object readGame(io.netty.buffer.ByteBuf buf, String type, io.netty.channel.Channel channel) {
        throw new IllegalArgumentException(describe() + " cannot read game type " + type + " from bytes");
    }

    /**
     * Writes one value of a game type to {@code buf} as the game's own
     * codec does, for {@code sculk.writer}: the counterpart of
     * {@link #readGame}, with the same types and registries. Values are
     * the game's own objects, or the plain forms each type documents
     * ("ns:path", {x, y, z}, SNBT text, a plain string for text).
     */
    default void writeGame(io.netty.buffer.ByteBuf buf, String type, Object value, io.netty.channel.Channel channel) {
        throw new IllegalArgumentException(describe() + " cannot write game type " + type + " as bytes");
    }

    /**
     * Feeds {@code state} from one packet (spawned and removed entities,
     * listed players, opened and closed menus, the login), before scripts
     * see it. Called for every packet in either direction; the platform
     * picks the ones that say something. Must be quick and never throw
     * for packets it does not know.
     */
    default void observe(Object packet, PacketInfo info, sculk.core.game.GameState state) {}

    /** Ids of all loaded mods, including "minecraft" and the loader itself. */
    Set<String> modIds();

    /** The id of the mod whose jar defines {@code cls}, or null if unknown. */
    String modOf(Class<?> cls);
}
