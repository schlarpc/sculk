package sculk.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import sculk.core.Sculk;
import sculk.core.platform.Platform;

public final class SculkFabric implements ModInitializer, Platform {
    @Override
    public void onInitialize() {
        Sculk.init(this);
    }

    @Override
    public String loader() {
        return "fabric";
    }

    @Override
    public String minecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }
}
