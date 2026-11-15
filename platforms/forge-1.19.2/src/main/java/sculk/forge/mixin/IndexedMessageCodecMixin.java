package sculk.forge.mixin;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkInstance;
import net.minecraftforge.network.simple.IndexedMessageCodec;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sculk.core.payload.PayloadRegistry;

/** Captures every SimpleChannel message registration: channel, discriminator, class, direction. */
@Mixin(value = IndexedMessageCodec.class, remap = false)
abstract class IndexedMessageCodecMixin {
    @Shadow @Final private NetworkInstance networkInstance;

    @Inject(method = "addCodecIndex", at = @At("HEAD"))
    private void sculk$addCodecIndex(
            int index,
            Class<?> messageType,
            BiConsumer<?, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, ?> decoder,
            BiConsumer<?, Supplier<NetworkEvent.Context>> consumer,
            Optional<NetworkDirection> direction,
            CallbackInfoReturnable<?> cir) {
        String channel = networkInstance == null ? "?" : networkInstance.getChannelName().toString();
        String protocol = direction.map(d -> d.name().startsWith("LOGIN") ? "login" : "play").orElse("*");
        String flow = direction.map(d -> d.name().endsWith("TO_CLIENT") ? "clientbound" : "serverbound").orElse("*");
        PayloadRegistry.record(channel, protocol, flow, index, messageType.getName(), this);
    }
}
