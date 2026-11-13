package sculk.fabric.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sculk.core.net.WireHooks;
import sculk.fabric.ProtocolInfoAccess;

@Mixin(PacketEncoder.class)
abstract class PacketEncoderMixin implements ProtocolInfoAccess {
    // Full descriptor: PacketEncoder also has a synthetic bridge encode(ctx, Object, ByteBuf).
    private static final String ENCODE =
            "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V";

    @Shadow @Final private ProtocolInfo<?> protocolInfo;
    @Unique private int sculk$start;

    @Inject(method = ENCODE, at = @At("HEAD"))
    private void sculk$head(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        sculk$start = out.writerIndex();
    }

    @Inject(method = ENCODE, at = @At("RETURN"))
    private void sculk$return(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf out, CallbackInfo ci) {
        WireHooks.afterEncode(ctx, this, packet, out, sculk$start);
    }

    @Override
    public ProtocolInfo<?> sculk$protocolInfo() {
        return protocolInfo;
    }
}
