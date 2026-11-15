package sculk.forge.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.PacketDecoder;
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

@Mixin(PacketDecoder.class)
abstract class PacketDecoderMixin implements FlowAccess {
    @Shadow @Final private PacketFlow flow;
    @Unique private int sculk$frameStart;
    @Unique private int sculk$outStart;

    // decode is a Netty override, so it keeps its name under SRG: remap = false.
    @Inject(method = "decode", at = @At("HEAD"), remap = false)
    private void sculk$head(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        sculk$frameStart = in.readerIndex();
        sculk$outStart = out.size();
    }

    @Inject(method = "decode", at = @At("RETURN"), remap = false)
    private void sculk$return(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        WireHooks.afterDecode(ctx, this, in, sculk$frameStart, out, sculk$outStart);
    }

    @Override
    public PacketFlow sculk$flow() {
        return flow;
    }
}
