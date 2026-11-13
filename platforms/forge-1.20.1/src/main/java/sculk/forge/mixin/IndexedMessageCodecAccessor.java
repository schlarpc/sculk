package sculk.forge.mixin;

import it.unimi.dsi.fastutil.shorts.Short2ObjectArrayMap;
import net.minecraftforge.network.simple.IndexedMessageCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = IndexedMessageCodec.class, remap = false)
public interface IndexedMessageCodecAccessor {
    @Accessor("indicies")
    Short2ObjectArrayMap<?> sculk$indicies();
}
