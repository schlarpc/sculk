package sculk.forge;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;
import sculk.core.Sculk;
import sculk.core.platform.Platform;

@Mod(Sculk.MOD_ID)
public final class SculkForge implements Platform {
    public SculkForge() {
        Sculk.init(this);
    }

    @Override
    public String loader() {
        return "forge";
    }

    @Override
    public String minecraftVersion() {
        return FMLLoader.versionInfo().mcVersion();
    }
}
