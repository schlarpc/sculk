package sculk.forge.mixin;

import java.util.Optional;
import java.util.function.Function;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraftforge.network.simple.IndexedMessageCodec$MessageHandler", remap = false)
public interface MessageHandlerAccessor {
    @Accessor("decoder")
    Optional<Function<FriendlyByteBuf, Object>> sculk$decoder();
}
