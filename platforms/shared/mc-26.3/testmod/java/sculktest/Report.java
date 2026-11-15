package sculktest;

import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Serverbound: what the client's handler made of a {@link Sink}, with the
 * sink itself echoed back, so the server side decodes the same structures.
 */
public record Report(String note, boolean matched, List<String> differences, Sink echo) implements CustomPacketPayload {
    public static final Type<Report> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sculktest", "report"));

    private static final StreamCodec<io.netty.buffer.ByteBuf, List<String>> STRINGS =
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list());

    public static final StreamCodec<RegistryFriendlyByteBuf, Report> STREAM_CODEC = StreamCodec.of(
            (buf, r) -> {
                buf.writeUtf(r.note);
                buf.writeBoolean(r.matched);
                STRINGS.encode(buf, r.differences);
                Sink.write(buf, r.echo);
            },
            buf -> new Report(buf.readUtf(), buf.readBoolean(), STRINGS.decode(buf), Sink.read(buf)));

    @Override
    public Type<Report> type() {
        return TYPE;
    }
}
