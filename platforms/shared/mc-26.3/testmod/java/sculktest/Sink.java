package sculktest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * Clientbound: one of everything a mod payload commonly carries. The server
 * sends {@link Expected#sink}; sculk must show it field for field (see
 * scripts/integ.py), and scripts edit and inject copies of it.
 */
public record Sink(
        CompoundTag tag,
        List<ItemStack> items,
        BlockPos pos,
        UUID uuid,
        Identifier id,
        Component text,
        int varInt,
        long varLong,
        String unicode,
        Optional<String> present,
        Optional<String> absent,
        Mode mode,
        byte[] blob,
        Map<String, Integer> counts) implements CustomPacketPayload {
    public enum Mode { ALPHA, BETA, GAMMA }

    public static final Type<Sink> TYPE = new Type<>(Identifier.fromNamespaceAndPath("sculktest", "sink"));

    // Decodes into a LinkedHashMap: keeps the wire order, so the payload re-encodes byte for byte.
    private static final StreamCodec<io.netty.buffer.ByteBuf, Map<String, Integer>> COUNTS =
            ByteBufCodecs.map(LinkedHashMap::new, ByteBufCodecs.STRING_UTF8, ByteBufCodecs.VAR_INT);

    public static final StreamCodec<RegistryFriendlyByteBuf, Sink> STREAM_CODEC = StreamCodec.of(Sink::write, Sink::read);

    static void write(RegistryFriendlyByteBuf buf, Sink s) {
        buf.writeNbt(s.tag);
        ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, s.items);
        buf.writeBlockPos(s.pos);
        buf.writeUUID(s.uuid);
        buf.writeIdentifier(s.id);
        ComponentSerialization.STREAM_CODEC.encode(buf, s.text);
        buf.writeVarInt(s.varInt);
        buf.writeVarLong(s.varLong);
        buf.writeUtf(s.unicode);
        buf.writeOptional(s.present, FriendlyByteBuf::writeUtf);
        buf.writeOptional(s.absent, FriendlyByteBuf::writeUtf);
        buf.writeEnum(s.mode);
        buf.writeByteArray(s.blob);
        COUNTS.encode(buf, s.counts);
    }

    static Sink read(RegistryFriendlyByteBuf buf) {
        return new Sink(
                buf.readNbt(),
                ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf),
                buf.readBlockPos(),
                buf.readUUID(),
                buf.readIdentifier(),
                ComponentSerialization.STREAM_CODEC.decode(buf),
                buf.readVarInt(),
                buf.readVarLong(),
                buf.readUtf(),
                buf.readOptional(FriendlyByteBuf::readUtf),
                buf.readOptional(FriendlyByteBuf::readUtf),
                buf.readEnum(Mode.class),
                buf.readByteArray(),
                // Keeps the wire order, so the payload re-encodes byte for byte.
                COUNTS.decode(buf));
    }

    @Override
    public Type<Sink> type() {
        return TYPE;
    }
}
