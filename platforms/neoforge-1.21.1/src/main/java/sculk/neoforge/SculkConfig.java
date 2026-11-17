package sculk.neoforge;

import java.util.HashMap;
import java.util.Map;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import sculk.core.Settings;

/**
 * Sculk's {@link Settings} as a NeoForge startup config:
 * {@code config/sculk.toml}, which NeoForge writes with every
 * setting and its comment, and loads as soon as it is registered, so sculk
 * can start from the mod's constructor. Each side keeps its own; a server
 * never sends it to clients.
 */
final class SculkConfig {
    private SculkConfig() {}

    /** Builds, registers and (being a startup config) loads the config. */
    static Settings.Source register(ModContainer container) {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        Map<String, ModConfigSpec.ConfigValue<?>> values = new HashMap<>();
        for (Settings.Setting s : Settings.ALL) {
            b.comment(s.comment());
            Object d = s.defaultValue();
            ModConfigSpec.ConfigValue<?> v;
            if (d instanceof Integer i) v = b.defineInRange(s.key(), i.intValue(), 0, Integer.MAX_VALUE);
            else if (d instanceof Long l) v = b.defineInRange(s.key(), l.longValue(), 0L, Long.MAX_VALUE);
            else if (d instanceof Boolean bool) v = b.define(s.key(), bool.booleanValue());
            else v = b.define(s.key(), (String) d, o -> o instanceof String || o instanceof Number); // control = 25590 too
            values.put(s.key(), v);
        }
        container.registerConfig(ModConfig.Type.STARTUP, b.build(), "sculk.toml");
        return key -> {
            ModConfigSpec.ConfigValue<?> v = values.get(key);
            return v == null ? null : v.get();
        };
    }
}
