package sculktest;

import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Serverbound: what the client's handler made of a {@link Sink}, with the
 * sink itself echoed back, so the server side decodes the same structures.
 * On 1.20.1 a message on the test mod's SimpleChannel (discriminator 1).
 */
public record Report(String note, boolean matched, List<String> differences, Sink echo) {
    public static void write(Report r, FriendlyByteBuf buf) {
        buf.writeUtf(r.note);
        buf.writeBoolean(r.matched);
        buf.writeCollection(r.differences, FriendlyByteBuf::writeUtf);
        Sink.write(r.echo, buf);
    }

    public static Report read(FriendlyByteBuf buf) {
        return new Report(buf.readUtf(), buf.readBoolean(), buf.readList(FriendlyByteBuf::readUtf), Sink.read(buf));
    }
}
