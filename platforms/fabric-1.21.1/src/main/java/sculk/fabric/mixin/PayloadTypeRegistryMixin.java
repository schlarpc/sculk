package sculk.fabric.mixin;

import java.util.Locale;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import sculk.core.payload.PayloadRegistry;

/**
 * Captures payload registrations in Fabric API's networking module. Every
 * public register/registerLarge overload goes through register(type, codec).
 */
@Mixin(PayloadTypeRegistryImpl.class)
abstract class PayloadTypeRegistryMixin {
    // Fabric API for 1.21.1 names these state and side (later: protocol and flow).
    @Shadow @Final private ConnectionProtocol state;
    @Shadow @Final private PacketFlow side;

    @Inject(
            method = "register(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;Lnet/minecraft/network/codec/StreamCodec;)Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$TypeAndCodec;",
            at = @At("HEAD"))
    private void sculk$register(
            CustomPacketPayload.Type<?> type, StreamCodec<?, ?> codec, CallbackInfoReturnable<?> cir) {
        PayloadRegistry.record(
                type.id().toString(), state.id(), side.name().toLowerCase(Locale.ROOT), null, null, codec);
    }
}
