package sculk.core;

import io.netty.channel.Channel;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sculk.core.net.PipelineTap;
import sculk.core.platform.Platform;
import sculk.core.platform.Side;
import sculk.core.script.ScriptHost;

/**
 * Process-wide entry point for the engine.
 *
 * <p>A platform adapter calls {@link #init(Platform)} once from its mod
 * entrypoint, then forwards hook calls (from its mixins) to the instance
 * returned by {@link #get()}. Everything behind this class is shared by all
 * loaders and Minecraft versions.
 */
public final class Sculk {
    public static final String MOD_ID = "sculk";
    public static final Logger LOG = LoggerFactory.getLogger("sculk");

    private static volatile Sculk instance;

    private final Platform platform;

    private Sculk(Platform platform) {
        this.platform = platform;
    }

    public static synchronized Sculk init(Platform platform) {
        Objects.requireNonNull(platform, "platform");
        if (instance != null) {
            throw new IllegalStateException("sculk already initialised by " + instance.platform.describe());
        }
        instance = new Sculk(platform);
        LOG.info("sculk core initialised on {}", platform.describe());
        // Fail fast if the bundled Rhino jar is not visible to our classloader.
        LOG.info("scripting: {}", new ScriptHost().eval("init", "`rhino ${java.lang.Class.forName('org.mozilla.javascript.Context').getPackage().getImplementationVersion() || 'ok'}`"));
        return instance;
    }

    /** Returns the engine, or {@code null} if no platform has initialised it yet. */
    public static Sculk get() {
        return instance;
    }

    public Platform platform() {
        return platform;
    }

    /**
     * Called by the platform when a game connection's Netty channel becomes
     * active. The pipeline is fully built at this point for network
     * connections. In-memory (singleplayer) connections have no codec
     * handlers, so only object-level hooks see their traffic.
     */
    public void onChannelActive(Channel channel, Side side) {
        PipelineTap.install(channel, side);
    }
}
