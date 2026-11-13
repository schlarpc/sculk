package sculk.fabric;

import net.minecraft.network.ProtocolInfo;

/** Added to PacketDecoder/PacketEncoder by mixin: exposes the codec's protocol state. */
public interface ProtocolInfoAccess {
    ProtocolInfo<?> sculk$protocolInfo();
}
