package sculk.core.platform;

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

    /** Ids of all loaded mods, including "minecraft" and the loader itself. */
    Set<String> modIds();

    /** The id of the mod whose jar defines {@code cls}, or null if unknown. */
    String modOf(Class<?> cls);
}
