package sculk.forge;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import sculk.core.Sculk;

@Mod(Sculk.MOD_ID)
public final class SculkForge {
    public SculkForge() {
        ForgePlatform platform = new ForgePlatform();
        platform.settings = SculkConfig.register();
        // Forge loads common configs after constructing the mods: start once ours is read.
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        bus.addListener((ModConfigEvent.Loading e) -> {
            if (e.getConfig().getSpec() == SculkConfig.spec) start(platform);
        });
        bus.addListener((FMLCommonSetupEvent e) -> start(platform));
    }

    private static synchronized void start(ForgePlatform platform) {
        if (Sculk.get() == null) Sculk.init(platform);
    }
}
