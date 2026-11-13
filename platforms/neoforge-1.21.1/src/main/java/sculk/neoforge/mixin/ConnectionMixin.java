package sculk.neoforge.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sculk.core.net.WireHooks;
import sculk.core.platform.Side;

@Mixin(Connection.class)
abstract class ConnectionMixin {
    @Inject(method = "channelActive", at = @At("TAIL"))
    private void sculk$channelActive(ChannelHandlerContext ctx, CallbackInfo ci) {
        boolean client = ((Connection) (Object) this).getReceiving() == PacketFlow.CLIENTBOUND;
        WireHooks.channelActive(ctx, client ? Side.CLIENT : Side.SERVER);
    }
}
