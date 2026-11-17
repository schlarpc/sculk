package sculk.forge;

import java.util.HashMap;
import java.util.Map;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import sculk.core.Settings;

/**
 * Sculk's {@link Settings} as a Forge config: {@code config/sculk.toml},
 * which Forge writes with every setting and its comment, and loads after
 * the mods are constructed (so {@link SculkForge} starts sculk then).
 */
final class SculkConfig {
    static ForgeConfigSpec spec;

    private SculkConfig() {}

    /** Builds and registers the config; the source reads it once Forge has loaded it. */
    static Settings.Source register() {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        Map<String, ForgeConfigSpec.ConfigValue<?>> values = new HashMap<>();
        for (Settings.Setting s : Settings.ALL) {
            b.comment(s.comment());
            Object d = s.defaultValue();
            ForgeConfigSpec.ConfigValue<?> v;
            if (d instanceof Integer i) v = b.defineInRange(s.key(), i.intValue(), 0, Integer.MAX_VALUE);
            else if (d instanceof Long l) v = b.defineInRange(s.key(), l.longValue(), 0L, Long.MAX_VALUE);
            else if (d instanceof Boolean bool) v = b.define(s.key(), bool.booleanValue());
            else v = b.define(s.key(), (String) d, o -> o instanceof String || o instanceof Number); // control = 25590 too
            values.put(s.key(), v);
        }
        spec = b.build();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, spec, "sculk.toml");
        return key -> {
            ForgeConfigSpec.ConfigValue<?> v = values.get(key);
            return v == null ? null : v.get();
        };
    }
}
