package sculk.neoforge.mixin;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sculk.core.payload.PayloadRegistry;

/** Captures every modded payload registration (and who made it) as NeoForge accepts it. */
@Mixin(NetworkRegistry.class)
abstract class NetworkRegistryMixin {
    @Inject(method = "register", at = @At("HEAD"))
    private static void sculk$register(
            CustomPacketPayload.Type<?> type,
            StreamCodec<?, ?> codec,
            IPayloadHandler<?> handler,
            List<ConnectionProtocol> protocols,
            Optional<PacketFlow> flow,
            String version,
            boolean optional,
            CallbackInfo ci) {
        String flowId = flow.map(f -> f.name().toLowerCase(Locale.ROOT)).orElse("*");
        for (ConnectionProtocol protocol : protocols) {
            PayloadRegistry.record(type.id().toString(), protocol.id(), flowId, null, null, codec);
        }
    }
}
