package sculk.fabric;

import net.fabricmc.api.ModInitializer;
import sculk.core.Sculk;

public final class SculkFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Sculk.init(new FabricPlatform());
    }
}
