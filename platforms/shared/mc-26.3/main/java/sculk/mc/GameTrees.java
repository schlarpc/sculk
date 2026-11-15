package sculk.mc;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JavaOps;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.EndTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import sculk.core.model.ObjectModel;
import sculk.core.util.Bytes;

/**
 * Minecraft 26.3's types as {@link ObjectModel} trees:
 * <ul>
 *   <li>NBT as JSON: compounds as objects (keys sorted), lists and int/long
 *       arrays as lists, byte arrays as {@code {"$bytes": hex}}, numbers
 *       and strings as themselves;</li>
 *   <li>item stacks as {@code {item, count, components}}, the components
 *       as the game writes them in commands where their codec allows,
 *       {@code "!id": null} for a removed default;</li>
 *   <li>identifiers, resource keys, registered holders and anything in a
 *       built-in registry (items, blocks, entity types...) as {@code "ns:path"};</li>
 *   <li>block positions and vectors as {@code {x, y, z}}, block states as
 *       {@code "id[prop=value]"}, text components as JSON text.</li>
 * </ul>
 */
public final class GameTrees implements ObjectModel.Renderer {
    /** NBT has its own depth limit (512); show this much of it whatever the tree depth. */
    private static final int NBT_DEPTH = 32;
    /** Item stacks are small and bounded; show their components whole wherever the stack sits. */
    private static final int ITEM_DEPTH = 8;
    private static final Object NONE = new Object();
    private final Map<Class<?>, Object> registryByClass = new ConcurrentHashMap<>();

    @Override
    public Object render(Object value, int depth) {
        if (value instanceof Tag tag) return nbt(tag, NBT_DEPTH);
        if (value instanceof Identifier id) return id.toString();
        if (value instanceof ResourceKey<?> key) return key.identifier().toString();
        if (value instanceof Holder<?> holder) {
            Optional<? extends ResourceKey<?>> key = holder.unwrapKey();
            return key.isPresent() ? key.get().identifier().toString() : ObjectModel.toTree(holder.value(), depth);
        }
        if (value instanceof BlockPos pos) return xyz(pos.getX(), pos.getY(), pos.getZ());
        if (value instanceof Vec3 v) return xyz(v.x, v.y, v.z);
        if (value instanceof BlockState state) return BlockStateParser.serialize(state);
        if (value instanceof Component c) return encoded(ComponentSerialization.CODEC, c, depth);
        if (value instanceof ItemStack stack) return item(stack, depth);
        if (value instanceof DataComponentPatch patch) return components(patch, depth);
        String registered = registeredId(value);
        if (registered != null) return registered;
        return ObjectModel.UNHANDLED;
    }

    private static Map<String, Object> xyz(Object x, Object y, Object z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }

    private static Object item(ItemStack stack, int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        m.put("count", stack.getCount());
        DataComponentPatch patch = stack.getComponentsPatch();
        if (!patch.isEmpty()) m.put("components", components(patch, Math.max(depth - 1, ITEM_DEPTH)));
        return m;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object components(DataComponentPatch patch, int depth) {
        java.util.TreeMap<String, Object> out = new java.util.TreeMap<>();
        DataComponentPatch.SplitResult split = patch.split();
        for (TypedDataComponent<?> c : split.added()) {
            Codec codec = c.type().codec();
            out.put(id(c.type()), codec == null ? ObjectModel.toTree(c.value(), depth - 1) : encoded(codec, c.value(), depth - 1));
        }
        for (DataComponentType<?> type : split.removed()) out.put("!" + id(type), null);
        return new LinkedHashMap<>(out);
    }

    private static String id(DataComponentType<?> type) {
        return String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type));
    }

    /** {@code value} through its codec into plain Java values, or by reflection where the codec needs more (registries). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object encoded(Codec codec, Object value, int depth) {
        try {
            Optional<Object> out = codec.encodeStart(JavaOps.INSTANCE, value).result();
            if (out.isPresent()) return ObjectModel.toTree(out.get(), Math.max(depth, NBT_DEPTH));
        } catch (RuntimeException e) {
            // fall through
        }
        if (value instanceof Component c) return c.getString(); // its codec failing again must not loop
        return ObjectModel.toTree(value, depth);
    }

    static Object nbt(Tag tag, int depth) {
        if (tag instanceof StringTag s) return s.value();
        if (tag instanceof ByteTag b) return b.value();
        if (tag instanceof ShortTag s) return s.value();
        if (tag instanceof IntTag i) return i.value();
        if (tag instanceof LongTag l) return l.value();
        if (tag instanceof FloatTag f) return f.value();
        if (tag instanceof DoubleTag d) return d.value();
        if (tag instanceof ByteArrayTag a) return Map.of("$bytes", Bytes.hex(a.getAsByteArray(), 1024));
        if (tag instanceof EndTag) return null;
        if (depth <= 0) return tag.toString();
        if (tag instanceof IntArrayTag a) {
            List<Object> out = new ArrayList<>();
            for (int v : a.getAsIntArray()) {
                if (out.size() >= ObjectModel.MAX_ELEMENTS) break;
                out.add(v);
            }
            return out;
        }
        if (tag instanceof LongArrayTag a) {
            List<Object> out = new ArrayList<>();
            for (long v : a.getAsLongArray()) {
                if (out.size() >= ObjectModel.MAX_ELEMENTS) break;
                out.add(v);
            }
            return out;
        }
        if (tag instanceof ListTag list) {
            List<Object> out = new ArrayList<>();
            for (Tag t : list) {
                if (out.size() >= ObjectModel.MAX_ELEMENTS) break;
                out.add(nbt(t, depth - 1));
            }
            return out;
        }
        if (tag instanceof CompoundTag c) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : new TreeSet<>(c.keySet())) {
                if (out.size() >= ObjectModel.MAX_ELEMENTS) break;
                out.put(key, nbt(c.get(key), depth - 1));
            }
            return out;
        }
        return tag.toString();
    }

    /** "ns:path" when {@code value} is in a built-in registry (an item, a block, an entity type...). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String registeredId(Object value) {
        Class<?> cls = value.getClass();
        if (cls.isRecord() || cls.isArray() || cls.getName().startsWith("java.")) return null;
        Object found = registryByClass.get(cls);
        if (found == null) {
            found = NONE;
            for (Registry<?> registry : BuiltInRegistries.REGISTRY) {
                // getResourceKey, not getKey: defaulted registries give their default (air) for strangers.
                if (((Registry) registry).getResourceKey(value).isPresent()) {
                    found = registry;
                    break;
                }
            }
            registryByClass.put(cls, found);
        }
        if (found == NONE) return null;
        Optional<ResourceKey<?>> key = ((Registry) found).getResourceKey(value);
        return key.map(k -> k.identifier().toString()).orElse(null);
    }
}
