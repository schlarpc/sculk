package sculk.forge;

import net.minecraftforge.fml.common.Mod;
import sculk.core.Sculk;

@Mod(Sculk.MOD_ID)
public final class SculkForge {
    public SculkForge() {
        Sculk.init(new ForgePlatform());
    }
}
