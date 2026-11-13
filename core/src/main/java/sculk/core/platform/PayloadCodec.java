package sculk.core.platform;

/**
 * Encodes and decodes one mod channel's payload bytes, using the mod's own codec.
 * Only needed where the game itself leaves payloads opaque (Forge 1.20.1 SimpleChannel).
 */
public interface PayloadCodec {
    Object decode(byte[] data) throws Exception;

    byte[] encode(Object message) throws Exception;
}
