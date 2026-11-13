package sculk.core.platform;

/**
 * The contract between the engine and one (loader, Minecraft version) target.
 *
 * <p>This is the only seam that differs per target. Keep it narrow: anything
 * that can be done with Netty, reflection, or plain Java belongs in core.
 * docs/ARCHITECTURE.md describes the views that are planned for this
 * interface: protocol, payload registry, attribution, and actions.
 */
public interface Platform {
    /** Loader id, for example {@code "neoforge"}, {@code "fabric"} or {@code "forge"}. */
    String loader();

    /** Minecraft version string, for example {@code "1.21.1"}. */
    String minecraftVersion();

    default String describe() {
        return loader() + " " + minecraftVersion();
    }
}
