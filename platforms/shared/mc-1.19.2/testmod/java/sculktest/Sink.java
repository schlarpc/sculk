package sculktest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Clientbound: one of everything a mod payload commonly carries. The server
 * sends {@link Expected#sink}; sculk must show it field for field (see
 * scripts/integ.py), and scripts edit and inject copies of it. On 1.20.1 a
 * message on the test mod's SimpleChannel (discriminator 0).
 */
public record Sink(
        CompoundTag tag,
        List<ItemStack> items,
        BlockPos pos,
        UUID uuid,
        ResourceLocation id,
        Component text,
        int varInt,
        long varLong,
        String unicode,
        Optional<String> present,
        Optional<String> absent,
        Mode mode,
        byte[] blob,
        Map<String, Integer> counts) {
    public enum Mode { ALPHA, BETA, GAMMA }

    public static void write(Sink s, FriendlyByteBuf buf) {
        buf.writeNbt(s.tag);
        buf.writeCollection(s.items, FriendlyByteBuf::writeItem);
        buf.writeBlockPos(s.pos);
        buf.writeUUID(s.uuid);
        buf.writeResourceLocation(s.id);
        buf.writeComponent(s.text);
        buf.writeVarInt(s.varInt);
        buf.writeVarLong(s.varLong);
        buf.writeUtf(s.unicode);
        buf.writeOptional(s.present, FriendlyByteBuf::writeUtf);
        buf.writeOptional(s.absent, FriendlyByteBuf::writeUtf);
        buf.writeEnum(s.mode);
        buf.writeByteArray(s.blob);
        buf.writeMap(s.counts, FriendlyByteBuf::writeUtf, FriendlyByteBuf::writeVarInt);
    }

    public static Sink read(FriendlyByteBuf buf) {
        return new Sink(
                buf.readNbt(),
                buf.readList(FriendlyByteBuf::readItem),
                buf.readBlockPos(),
                buf.readUUID(),
                buf.readResourceLocation(),
                buf.readComponent(),
                buf.readVarInt(),
                buf.readVarLong(),
                buf.readUtf(),
                buf.readOptional(FriendlyByteBuf::readUtf),
                buf.readOptional(FriendlyByteBuf::readUtf),
                buf.readEnum(Mode.class),
                buf.readByteArray(),
                // Keeps the wire order, so the payload re-encodes byte for byte.
                buf.readMap(LinkedHashMap::new, FriendlyByteBuf::readUtf, FriendlyByteBuf::readVarInt));
    }
}
