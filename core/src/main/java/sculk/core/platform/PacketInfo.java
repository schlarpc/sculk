package sculk.core.platform;

/**
 * What a platform can say about one packet object.
 *
 * @param state          protocol state, for example {@code "play"} or {@code "configuration"}
 * @param flow           {@code "clientbound"} or {@code "serverbound"}
 * @param type           canonical packet type: the protocol id where the game has one
 *                       ({@code "minecraft:custom_payload"}), otherwise the Mojang class name
 * @param payloadChannel for custom payload packets, the payload channel id; otherwise null
 * @param payload        the decoded mod payload object, if the game or the platform decoded it
 * @param payloadBytes   for loaders whose payloads are opaque on the wire (Forge 1.20.1), the
 *                       raw payload bytes; otherwise null
 */
public record PacketInfo(
        String state, String flow, String type, String payloadChannel, Object payload, byte[] payloadBytes) {

    public boolean isCustomPayload() {
        return payloadChannel != null;
    }
}
