package sculk.fabric.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import sculk.core.net.WireHooks;
import sculk.fabric.ProtocolInfoAccess;

@Mixin(PacketDecoder.class)
abstract class PacketDecoderMixin implements ProtocolInfoAccess {
    @Shadow @Final private ProtocolInfo<?> protocolInfo;
    @Unique private int sculk$frameStart;
    @Unique private int sculk$outStart;

    @Inject(method = "decode", at = @At("HEAD"))
    private void sculk$head(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        sculk$frameStart = in.readerIndex();
        sculk$outStart = out.size();
    }

    @Inject(method = "decode", at = @At("RETURN"))
    private void sculk$return(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        WireHooks.afterDecode(ctx, this, in, sculk$frameStart, out, sculk$outStart);
    }

    @Override
    public ProtocolInfo<?> sculk$protocolInfo() {
        return protocolInfo;
    }
}
