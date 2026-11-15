package sculk.core.model;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sculk.core.util.Bytes;

/**
 * Turns arbitrary payload objects into plain trees (maps, lists, strings,
 * numbers) for logging, the UI and scripts, and builds modified copies of
 * records for re-encoding.
 */
public final class ObjectModel {
    /** Elements shown per collection, map or array; the rest are left out. */
    public static final int MAX_ELEMENTS = 64;
    /** Bytes shown as hex per byte array or buffer. */
    public static final int MAX_BYTES = 1024;
    /**
     * Bytes shown as hex in one whole tree; past it, byte arrays show only
     * their length (and text). Keeps packets made of many buffers (chunk
     * light data) from logging tens of kilobytes each.
     */
    public static final int TREE_BYTES = 8192;
    /** Characters of {@code $text} per byte array. */
    public static final int MAX_TEXT = 2048;
    /** Shortest run of printable ASCII that {@code $text} keeps, as {@code strings(1)} does. */
    static final int MIN_RUN = 4;

    /**
     * Values (objects, lists, maps and leaves) in one whole tree at most;
     * past it, what is left shows as {@link #CUT}. Keeps one packet (a mod
     * syncing a 3D array of block matchers) from logging megabytes.
     */
    public static final int TREE_NODES = 4096;
    /** What stands for the values a tree had no room left for. */
    public static final String CUT = "…";

    /** Hex bytes left, values left, and 1 once something was cut, for the tree being built on this thread; null outside {@link #toTree}. */
    private static final ThreadLocal<int[]> BYTES_LEFT = new ThreadLocal<>();

    /** True once the tree being built has used up its values: callers stop adding and leave a {@link #CUT}. */
    private static boolean full() {
        int[] left = BYTES_LEFT.get();
        return left != null && left[1] <= 0;
    }

    /** {@link #CUT}, noting that this tree lost values to its budget. */
    private static String cut() {
        int[] left = BYTES_LEFT.get();
        if (left != null) left[2] = 1;
        return CUT;
    }

    /**
     * Game-specific views of game types (NBT, item stacks, identifiers,
     * positions, text components...) that reflection would show badly or
     * not at all. Supplied by the platform ({@link sculk.core.platform.Platform#renderer}).
     */
    public interface Renderer {
        /**
         * The tree for {@code value}, or {@link #UNHANDLED} to fall back to
         * reflection. Children go back through {@link ObjectModel#toTree}.
         */
        Object render(Object value, int depth);
    }

    /** What a {@link Renderer} returns for values it leaves to reflection. */
    public static final Object UNHANDLED = new Object();

    /**
     * A view of one class (and its subclasses) that a script supplies
     * ({@code sculk.decoder}): given the live object and the tree sculk
     * would show, the tree to show instead, or null to keep it.
     */
    public interface Decoder {
        Object decode(Object value, Object tree);
    }

    /** Decoders by class name, newest last; the newest wins. */
    private static final Map<String, List<Decoder>> DECODERS = new java.util.concurrent.ConcurrentHashMap<>();
    /** Objects being decoded on this thread, so a decoder that trees its own object gets the plain tree. */
    private static final ThreadLocal<java.util.Set<Object>> DECODING =
            ThreadLocal.withInitial(() -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    /**
     * Adds a decoder for objects of class {@code className} (or a subclass);
     * the returned Runnable removes it.
     */
    public static Runnable addDecoder(String className, Decoder decoder) {
        DECODERS.compute(className, (k, list) -> {
            List<Decoder> out = list == null ? new ArrayList<>() : new ArrayList<>(list);
            out.add(decoder);
            return List.copyOf(out);
        });
        return () -> DECODERS.computeIfPresent(className, (k, list) -> {
            List<Decoder> out = new ArrayList<>(list);
            out.remove(decoder);
            return out.isEmpty() ? null : List.copyOf(out);
        });
    }

    private static Decoder decoderFor(Class<?> cls) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            List<Decoder> list = DECODERS.get(c.getName());
            if (list != null && !list.isEmpty()) return list.get(list.size() - 1);
        }
        return null;
    }

    private static volatile Renderer renderer;

    private ObjectModel() {}

    /** Installs the platform's renderer (null for none). Called once at startup. */
    public static void setRenderer(Renderer r) {
        renderer = r;
    }

    /**
     * The tree for {@code value}, at most {@code depth} objects deep. Byte
     * arrays and buffers become {@code {"$bytes": hex}}, with {@code $len}
     * when the hex is cut short and {@code $text} holding their runs of
     * printable text (one per line), so text kept as bytes can be read and
     * searched.
     */
    public static Object toTree(Object value, int depth) {
        return toTree(value, depth, TREE_NODES);
    }

    /**
     * {@link #toTree(Object, int)} with room for {@code nodes} values. A
     * tree that ran out has {@code "$cut": true} at its top (when its top
     * is an object), so readers and filters can tell it is partial.
     */
    public static Object toTree(Object value, int depth, int nodes) {
        if (BYTES_LEFT.get() != null) return walk(value, depth);
        int[] left = {TREE_BYTES, Math.max(1, nodes), 0};
        BYTES_LEFT.set(left);
        try {
            Object tree = walk(value, depth);
            if (left[2] != 0 && tree instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> top = (Map<String, Object>) m;
                top.put("$cut", true);
            }
            return tree;
        } finally {
            BYTES_LEFT.remove();
        }
    }

    private static Object walk(Object value, int depth) {
        int[] left = BYTES_LEFT.get();
        if (left != null && left[1]-- <= 0) return cut();
        if (!DECODERS.isEmpty() && value != null && !(value instanceof Number || value instanceof Boolean
                || value instanceof CharSequence || value instanceof Map<?, ?> || value instanceof Collection<?>)) {
            Decoder d = decoderFor(value.getClass());
            java.util.Set<Object> busy = DECODING.get();
            if (d != null && busy.add(value)) {
                try {
                    Object tree = plainWalk(value, depth);
                    Object decoded = d.decode(value, tree);
                    return decoded == null ? tree : decoded;
                } finally {
                    busy.remove(value);
                }
            }
        }
        return plainWalk(value, depth);
    }

    private static Object plainWalk(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
            return value instanceof CharSequence cs ? cs.toString() : value;
        }
        if (value instanceof Character || value instanceof Enum<?>) return value.toString();
        if (value instanceof byte[] bytes) return bytes(bytes, bytes.length);
        // Payloads that keep a raw buffer (FriendlyByteBuf and the like): its readable bytes.
        if (value instanceof io.netty.buffer.ByteBuf buf) {
            byte[] bytes = new byte[Math.min(buf.readableBytes(), 64 * 1024)];
            buf.getBytes(buf.readerIndex(), bytes);
            return bytes(bytes, buf.readableBytes());
        }
        if (value instanceof Optional<?> opt) return opt.map(v -> toTree(v, depth)).orElse(null);
        Renderer r = renderer;
        if (r != null) {
            Object rendered;
            try {
                rendered = r.render(value, depth);
            } catch (RuntimeException | LinkageError e) {
                rendered = UNHANDLED; // a renderer bug must not lose the event; reflection still works
            }
            if (rendered != UNHANDLED) return rendered;
        }
        if (depth <= 0) return value.toString();
        if (value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int n = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < Math.min(n, MAX_ELEMENTS); i++) {
                if (full()) {
                    out.add(cut());
                    break;
                }
                out.add(toTree(java.lang.reflect.Array.get(value, i), depth - 1));
            }
            return out;
        }
        if (value instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object o : c) {
                if (out.size() >= MAX_ELEMENTS) break;
                if (full()) {
                    out.add(cut());
                    break;
                }
                out.add(toTree(o, depth - 1));
            }
            return out;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (out.size() >= MAX_ELEMENTS) break;
                if (full()) {
                    out.put(CUT, cut());
                    break;
                }
                out.put(key(e.getKey()), toTree(e.getValue(), depth - 1));
            }
            return out;
        }
        // Guava multimaps and tables (the game ships Guava; core does not depend on it): as their
        // map view, not their internals, which repeat every value (a GameProfile's properties).
        java.lang.reflect.Method view = GUAVA_VIEW.get(value.getClass());
        if (view != null) {
            try {
                return walk(view.invoke(value), depth);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // Fall through to reflection.
            }
        }
        Class<?> cls = value.getClass();
        // A lambda's fields are what it captured, not data: name what it is instead.
        if (cls.isHidden() || cls.getName().contains("$$Lambda")) {
            Class<?>[] is = cls.getInterfaces();
            return "(lambda) " + (is.length > 0 ? is[0].getSimpleName() : cls.getName().replaceFirst("\\$\\$Lambda.*", ""));
        }
        if (isJdk(cls.getName())) return value.toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("$class", cls.getName());
        try {
            if (cls.isRecord()) {
                for (java.lang.reflect.Method accessor : ACCESSORS.get(cls)) {
                    out.put(accessor.getName(), toTree(accessor.invoke(value), depth - 1));
                }
            } else {
                for (Field f : FIELDS.get(cls)) out.putIfAbsent(f.getName(), toTree(f.get(value), depth - 1));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            out.put("$string", value.toString());
            out.put("$reflectionError", e.toString());
        }
        return out;
    }

    /**
     * A record's component accessors, made accessible, in order. Looked up
     * once per class: every packet goes through here, so the reflection
     * lookups must not be paid per packet. Throws (each time) what
     * setAccessible threw.
     */
    private static final ClassValue<java.lang.reflect.Method[]> ACCESSORS = new ClassValue<>() {
        @Override
        protected java.lang.reflect.Method[] computeValue(Class<?> cls) {
            RecordComponent[] rcs = cls.getRecordComponents();
            java.lang.reflect.Method[] out = new java.lang.reflect.Method[rcs.length];
            for (int i = 0; i < rcs.length; i++) {
                out[i] = rcs[i].getAccessor();
                out[i].setAccessible(true);
            }
            return out;
        }
    };

    /** JDK classes, shown by toString: their fields are implementation, and modules may refuse reflection. */
    private static boolean isJdk(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.sun.");
    }

    /** Guava's map views: a Multimap's asMap(), a Table's rowMap(); null for other classes. */
    private static final ClassValue<java.lang.reflect.Method> GUAVA_VIEW = new ClassValue<>() {
        @Override
        protected java.lang.reflect.Method computeValue(Class<?> cls) {
            try {
                Class<?> multimap = findInterface(cls, "com.google.common.collect.Multimap");
                if (multimap != null) return multimap.getMethod("asMap");
                Class<?> table = findInterface(cls, "com.google.common.collect.Table");
                if (table != null) return table.getMethod("rowMap");
            } catch (NoSuchMethodException e) {
                // Not the Guava we know: reflection it is.
            }
            return null;
        }
    };

    private static Class<?> findInterface(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().equals(name)) return i;
                Class<?> deeper = findInterface(i, name);
                if (deeper != null) return deeper;
            }
        }
        return null;
    }

    /** A class's instance fields, its own first and then its superclasses', made accessible; as {@link #ACCESSORS}. */
    private static final ClassValue<Field[]> FIELDS = new ClassValue<>() {
        @Override
        protected Field[] computeValue(Class<?> cls) {
            List<Field> out = new ArrayList<>();
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    out.add(f);
                }
            }
            return out.toArray(new Field[0]);
        }
    };

    /** {@code bytes}, the first bytes of {@code len}, as {@code {$bytes, $len?, $text?}}. */
    private static Map<String, Object> bytes(byte[] bytes, int len) {
        int[] left = BYTES_LEFT.get();
        int shown = Math.min(Math.min(bytes.length, MAX_BYTES), left == null ? MAX_BYTES : Math.max(left[0], 0));
        if (left != null) left[0] -= shown;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("$bytes", shown < len ? Bytes.hex(java.util.Arrays.copyOf(bytes, shown)) + "..." : Bytes.hex(bytes));
        if (shown < len) out.put("$len", len);
        String text = text(bytes);
        if (text != null) out.put("$text", text);
        return out;
    }

    /** The runs of at least {@link #MIN_RUN} printable ASCII characters in {@code bytes}, one per line, or null if none. */
    static String text(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int start = -1;
        for (int i = 0; i <= bytes.length && out.length() < MAX_TEXT; i++) {
            boolean printable = i < bytes.length && bytes[i] >= 0x20 && bytes[i] < 0x7f;
            if (printable && start < 0) start = i;
            if (!printable && start >= 0) {
                if (i - start >= MIN_RUN) {
                    if (out.length() > 0) out.append('\n');
                    out.append(new String(bytes, start, i - start, java.nio.charset.StandardCharsets.US_ASCII));
                }
                start = -1;
            }
        }
        if (out.length() == 0) return null;
        return out.length() > MAX_TEXT ? out.substring(0, MAX_TEXT) + "..." : out.toString();
    }

    /** A map key as a string: the renderer's view when that is a string (an identifier, say), else {@code toString}. */
    private static String key(Object k) {
        if (k instanceof String s) return s;
        if (k != null && !(k instanceof Number) && !(k instanceof Enum<?>)) {
            Object tree = toTree(k, 1);
            if (tree instanceof String s) return s;
        }
        return String.valueOf(k);
    }

    /**
     * A string form of {@link #toTree} that ignores map and collection order,
     * for "same content, different encoding order" comparisons.
     */
    public static String canonical(Object value) {
        return canonicalTree(toTree(value, 12));
    }

    private static String canonicalTree(Object tree) {
        if (tree instanceof Map<?, ?> m) {
            java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) sorted.put(String.valueOf(e.getKey()), canonicalTree(e.getValue()));
            return sorted.toString();
        }
        if (tree instanceof List<?> l) {
            List<String> items = new ArrayList<>();
            for (Object o : l) items.add(canonicalTree(o));
            java.util.Collections.sort(items);
            return items.toString();
        }
        return String.valueOf(tree);
    }

    /** Converts script-friendly values (JS numbers arrive as Double) to the declared type. */
    static Object coerce(Object value, Class<?> type) {
        if (value instanceof Number n) {
            if (type == int.class || type == Integer.class) return n.intValue();
            if (type == long.class || type == Long.class) return n.longValue();
            if (type == short.class || type == Short.class) return n.shortValue();
            if (type == byte.class || type == Byte.class) return n.byteValue();
            if (type == float.class || type == Float.class) return n.floatValue();
            if (type == double.class || type == Double.class) return n.doubleValue();
        }
        if (value instanceof CharSequence cs && type == String.class) return cs.toString();
        return value;
    }

    /** Returns a copy of {@code record} with component {@code name} set to {@code newValue}. */
    public static Object withComponent(Object record, String name, Object newValue) throws ReflectiveOperationException {
        Class<?> cls = record.getClass();
        if (!cls.isRecord()) throw new IllegalArgumentException(cls.getName() + " is not a record");
        RecordComponent[] components = cls.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        boolean found = false;
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            if (components[i].getName().equals(name)) {
                args[i] = coerce(newValue, types[i]);
                found = true;
            } else {
                var accessor = components[i].getAccessor();
                accessor.setAccessible(true);
                args[i] = accessor.invoke(record);
            }
        }
        if (!found) throw new IllegalArgumentException(cls.getName() + " has no component " + name);
        var ctor = cls.getDeclaredConstructor(types);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }
}
