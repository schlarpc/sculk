package sculk.forge.mixin;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sculk.core.Sculk;
import sculk.core.platform.Side;

@Mixin(Connection.class)
abstract class ConnectionMixin {
    @Inject(method = "channelActive", at = @At("TAIL"))
    private void sculk$channelActive(ChannelHandlerContext ctx, CallbackInfo ci) {
        Sculk sculk = Sculk.get();
        if (sculk == null) return;
        Side side = ((Connection) (Object) this).getReceiving() == PacketFlow.CLIENTBOUND ? Side.CLIENT : Side.SERVER;
        sculk.onChannelActive(ctx.channel(), side);
    }
}
