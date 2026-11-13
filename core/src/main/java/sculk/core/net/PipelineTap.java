package sculk.core.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import sculk.core.Sculk;
import sculk.core.platform.Side;

/**
 * Byte-level access to a game connection, written only against Netty.
 *
 * <p>Netty is not obfuscated and Minecraft's handler names ("splitter",
 * "decoder", "encoder", "prepender", "compress", "decompress", "packet_handler")
 * have been stable across every supported version, so this layer is shared by
 * all platforms. For now it only records the pipeline layout. Frame taps and
 * raw injection hang off the same anchors (see docs/ARCHITECTURE.md).
 */
public final class PipelineTap {
    private PipelineTap() {}

    public static void install(Channel channel, Side side) {
        ChannelPipeline pipeline = channel.pipeline();
        Sculk.LOG.info("[{}] connection {} active, pipeline: {}", side, channel.remoteAddress(), pipeline.names());
    }
}
