package sculk.neoforge;

import net.neoforged.fml.common.Mod;
import sculk.core.Sculk;

@Mod(Sculk.MOD_ID)
public final class SculkNeoForge {
    public SculkNeoForge() {
        Sculk.init(new NeoForgePlatform());
    }
}
