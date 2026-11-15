package sculk.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
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
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import sculk.core.model.ObjectModel;
import sculk.core.util.Bytes;

/**
 * Minecraft 1.19.2's types as {@link ObjectModel} trees:
 * <ul>
 *   <li>NBT as JSON: compounds as objects (keys sorted), lists and int/long
 *       arrays as lists, byte arrays as {@code {"$bytes": hex}}, numbers
 *       and strings as themselves;</li>
 *   <li>item stacks as {@code {item, count, tag}} (no components before 1.20.5);</li>
 *   <li>identifiers, resource keys, registered holders and anything in a
 *       built-in registry (items, blocks, entity types...) as {@code "ns:path"};</li>
 *   <li>block positions and vectors as {@code {x, y, z}}, block states as
 *       {@code "id[prop=value]"}, text components as JSON text.</li>
 * </ul>
 */
public final class GameTrees implements ObjectModel.Renderer {
    /** NBT has its own depth limit (512); show this much of it whatever the tree depth. */
    private static final int NBT_DEPTH = 32;
    private static final Object NONE = new Object();
    private final Map<Class<?>, Object> registryByClass = new ConcurrentHashMap<>();

    @Override
    public Object render(Object value, int depth) {
        if (value instanceof Tag tag) return nbt(tag, NBT_DEPTH);
        if (value instanceof ResourceLocation id) return id.toString();
        if (value instanceof ResourceKey<?> key) return key.location().toString();
        if (value instanceof Holder<?> holder) {
            Optional<? extends ResourceKey<?>> key = holder.unwrapKey();
            return key.isPresent() ? key.get().location().toString() : ObjectModel.toTree(holder.value(), depth);
        }
        if (value instanceof BlockPos pos) return xyz(pos.getX(), pos.getY(), pos.getZ());
        if (value instanceof Vec3 v) return xyz(v.x, v.y, v.z);
        if (value instanceof BlockState state) return BlockStateParser.serialize(state);
        if (value instanceof Component c) return json(Component.Serializer.toJsonTree(c));
        if (value instanceof ItemStack stack) return item(stack);
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

    private static Object item(ItemStack stack) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", Registry.ITEM.getKey(stack.getItem()).toString());
        m.put("count", stack.getCount());
        if (stack.getTag() != null) m.put("tag", nbt(stack.getTag(), NBT_DEPTH));
        return m;
    }

    /** JSON text (Gson) as plain Java values. */
    private static Object json(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e instanceof JsonPrimitive p) {
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsNumber();
            return p.getAsString();
        }
        if (e instanceof JsonArray a) {
            List<Object> out = new ArrayList<>();
            for (JsonElement x : a) out.add(json(x));
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> x : ((JsonObject) e).entrySet()) out.put(x.getKey(), json(x.getValue()));
        return out;
    }

    static Object nbt(Tag tag, int depth) {
        if (tag instanceof StringTag s) return s.getAsString();
        if (tag instanceof ByteTag b) return b.getAsByte();
        if (tag instanceof ShortTag s) return s.getAsShort();
        if (tag instanceof IntTag i) return i.getAsInt();
        if (tag instanceof LongTag l) return l.getAsLong();
        if (tag instanceof FloatTag f) return f.getAsFloat();
        if (tag instanceof DoubleTag d) return d.getAsDouble();
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
            for (String key : new TreeSet<>(c.getAllKeys())) {
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
            for (Registry<?> registry : Registry.REGISTRY) {
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
        return key.map(k -> k.location().toString()).orElse(null);
    }
}
