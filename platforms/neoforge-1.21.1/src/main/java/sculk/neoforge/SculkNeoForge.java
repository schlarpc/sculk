package sculk.neoforge;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import sculk.core.Sculk;

@Mod(Sculk.MOD_ID)
public final class SculkNeoForge {
    public SculkNeoForge(ModContainer container) {
        NeoForgePlatform platform = new NeoForgePlatform();
        platform.settings = SculkConfig.register(container);
        Sculk.init(platform);
    }
}
