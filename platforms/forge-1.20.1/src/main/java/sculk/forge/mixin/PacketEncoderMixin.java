package sculk.forge.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sculk.core.net.WireHooks;
import sculk.forge.FlowAccess;

@Mixin(PacketEncoder.class)
abstract class PacketEncoderMixin implements FlowAccess {
    // Full descriptor: PacketEncoder also has a synthetic bridge encode(ctx, Object, ByteBuf).
    // Class names are the same under SRG and the method is a Netty override: remap = false.
    private static final String ENCODE =
            "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V";

    @Shadow @Final private PacketFlow flow;
    @Unique private int sculk$start;

    @Inject(method = ENCODE, at = @At("HEAD"), remap = false)
    private void sculk$head(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        sculk$start = out.writerIndex();
    }

    @Inject(method = ENCODE, at = @At("RETURN"), remap = false)
    private void sculk$return(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        WireHooks.afterEncode(ctx, this, packet, out, sculk$start);
    }

    @Override
    public PacketFlow sculk$flow() {
        return flow;
    }
}
