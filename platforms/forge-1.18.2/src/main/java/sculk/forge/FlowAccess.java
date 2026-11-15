package sculk.forge;

import net.minecraft.network.protocol.PacketFlow;

/** Added to PacketDecoder/PacketEncoder by mixin: exposes which way the codec faces. */
public interface FlowAccess {
    PacketFlow sculk$flow();
}
