package sculktest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * The payload the server sends, built the same way on every platform so
 * scripts/integ.py can hold one expected tree. Keep the two in step.
 */
public final class Expected {
    private Expected() {}

    public static CompoundTag tag() {
        CompoundTag t = new CompoundTag();
        t.putByte("byte", (byte) -128);
        t.put("boolean", ByteTag.valueOf(true));
        t.putShort("short", (short) -32768);
        t.putInt("int", Integer.MAX_VALUE);
        t.putLong("long", Long.MIN_VALUE);
        t.putFloat("float", 0.15625f);
        t.putDouble("double", -1.0E300);
        t.putString("string", "ünïcödé ☃ 𝄞");
        t.putByteArray("bytes", new byte[] {-1, 0, 1, 127});
        t.putIntArray("ints", new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE});
        t.putLongArray("longs", new long[] {Long.MIN_VALUE, 0, Long.MAX_VALUE});
        ListTag strings = new ListTag();
        for (String s : new String[] {"a", "b", "c"}) strings.add(StringTag.valueOf(s));
        t.put("strings", strings);
        ListTag compounds = new ListTag();
        for (int k = 1; k <= 2; k++) {
            CompoundTag c = new CompoundTag();
            c.putInt("k", k);
            compounds.add(c);
        }
        t.put("compounds", compounds);
        ListTag lists = new ListTag();
        ListTag first = new ListTag();
        first.add(IntTag.valueOf(1));
        first.add(IntTag.valueOf(2));
        ListTag second = new ListTag();
        second.add(IntTag.valueOf(3));
        lists.add(first);
        lists.add(second);
        t.put("lists", lists);
        CompoundTag c = new CompoundTag();
        c.putString("c", "deep");
        CompoundTag b = new CompoundTag();
        b.put("b", c);
        CompoundTag a = new CompoundTag();
        a.put("a", b);
        t.put("nested", a);
        t.put("empty_compound", new CompoundTag());
        t.put("empty_list", new ListTag());
        return t;
    }

    public static List<ItemStack> items() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.setHoverName(new TextComponent("Sculk Blade").withStyle(ChatFormatting.AQUA));
        sword.enchant(Enchantments.SHARPNESS, 5);
        sword.getOrCreateTag().putString("owner", "sculktest");
        List<ItemStack> items = new ArrayList<>();
        items.add(sword);
        items.add(new ItemStack(Items.STONE, 64));
        items.add(ItemStack.EMPTY);
        return items;
    }

    public static Sink sink() {
        byte[] blob = new byte[300];
        for (int i = 0; i < blob.length; i++) blob[i] = (byte) i;
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("zeta", 26);
        counts.put("alpha", 1);
        counts.put("mid", 13);
        return new Sink(
                tag(),
                items(),
                new BlockPos(-30_000_000, -64, 29_999_999),
                UUID.fromString("0badc0de-5c01-4cee-8000-00000000beef"),
                new ResourceLocation("sculktest", "thing/with_path"),
                new TextComponent("Hello, ").withStyle(ChatFormatting.BOLD)
                        .append(new TextComponent("sculk").withStyle(ChatFormatting.DARK_AQUA)),
                -1,
                Long.MIN_VALUE,
                "ünïcödé ☃ 𝄞 \u0000 end",
                Optional.of("here"),
                Optional.empty(),
                Sink.Mode.GAMMA,
                blob,
                counts);
    }

    /** The fields of {@code actual} that differ from {@code expected}; empty when they all match. */
    public static List<String> differences(Sink expected, Sink actual) {
        List<String> out = new ArrayList<>();
        if (!expected.tag().equals(actual.tag())) out.add("tag");
        if (!sameItems(expected.items(), actual.items())) out.add("items");
        if (!expected.pos().equals(actual.pos())) out.add("pos");
        if (!expected.uuid().equals(actual.uuid())) out.add("uuid");
        if (!expected.id().equals(actual.id())) out.add("id");
        if (!expected.text().equals(actual.text())) out.add("text");
        if (expected.varInt() != actual.varInt()) out.add("varInt");
        if (expected.varLong() != actual.varLong()) out.add("varLong");
        if (!expected.unicode().equals(actual.unicode())) out.add("unicode");
        if (!expected.present().equals(actual.present())) out.add("present");
        if (!expected.absent().equals(actual.absent())) out.add("absent");
        if (expected.mode() != actual.mode()) out.add("mode");
        if (!Arrays.equals(expected.blob(), actual.blob())) out.add("blob");
        if (!new ArrayList<>(expected.counts().entrySet()).equals(new ArrayList<>(actual.counts().entrySet()))) out.add("counts");
        return out;
    }

    private static boolean sameItems(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) return false;
        }
        return true;
    }
}
