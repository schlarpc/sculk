package sculk.neoforge;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import sculk.core.Sculk;
import sculk.core.platform.Platform;

@Mod(Sculk.MOD_ID)
public final class SculkNeoForge implements Platform {
    public SculkNeoForge() {
        Sculk.init(this);
    }

    @Override
    public String loader() {
        return "neoforge";
    }

    @Override
    public String minecraftVersion() {
        return FMLLoader.versionInfo().mcVersion();
    }
}
