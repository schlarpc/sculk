package sculk.core.payload;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import sculk.core.platform.Platform;

/**
 * Decides which mod a payload belongs to.
 *
 * <p>Order of evidence: the channel namespace when it is a loaded mod id; then
 * the mod that defines the payload class; then the outermost mod on the
 * registration call stack. Mods that only appear as intermediaries (a
 * networking library registering on another mod's behalf) are reported
 * separately as {@link #registeredVia}.
 */
public final class Attribution {
    /** Loader and game ids that are never the "owner" of a mod payload. */
    private static final Set<String> FRAMEWORK = Set.of("minecraft", "neoforge", "forge", "fabricloader", "java", "sculk");

    private final Platform platform;

    public Attribution(Platform platform) {
        this.platform = platform;
    }

    public static String namespace(String channel) {
        int colon = channel.indexOf(':');
        return colon < 0 ? "minecraft" : channel.substring(0, colon);
    }

    /** Mods on the registration stacks for {@code channel}, innermost first, loader/game excluded. */
    public List<String> registeredVia(String channel) {
        Set<String> mods = new LinkedHashSet<>();
        for (PayloadRegistration reg : PayloadRegistry.forChannel(channel)) {
            for (Class<?> caller : reg.callers()) {
                String mod = platform.modOf(caller);
                if (mod != null && !isFramework(mod)) mods.add(mod);
            }
        }
        return new ArrayList<>(mods);
    }

    public String owner(String channel, Object payload) {
        String ns = namespace(channel);
        if (!isFramework(ns) && platform.modIds().contains(ns)) return ns;
        if (payload != null) {
            String mod = platform.modOf(payload.getClass());
            if (mod != null && !isFramework(mod)) return mod;
        }
        List<String> via = registeredVia(channel);
        if (!via.isEmpty()) return via.get(via.size() - 1);
        return ns;
    }

    public static boolean isFramework(String modId) {
        return FRAMEWORK.contains(modId) || modId.startsWith("fabric-") || modId.equals("fabric-api");
    }
}
