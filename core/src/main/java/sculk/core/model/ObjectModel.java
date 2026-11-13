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
    private static final int MAX_ELEMENTS = 64;

    private ObjectModel() {}

    public static Object toTree(Object value, int depth) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
            return value instanceof CharSequence cs ? cs.toString() : value;
        }
        if (value instanceof Character || value instanceof Enum<?>) return value.toString();
        if (value instanceof byte[] bytes) return Map.of("$bytes", Bytes.hex(bytes, 1024));
        if (value instanceof Optional<?> opt) return opt.map(v -> toTree(v, depth)).orElse(null);
        if (depth <= 0) return value.toString();
        if (value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int n = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < Math.min(n, MAX_ELEMENTS); i++) out.add(toTree(java.lang.reflect.Array.get(value, i), depth - 1));
            return out;
        }
        if (value instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object o : c) {
                if (out.size() >= MAX_ELEMENTS) break;
                out.add(toTree(o, depth - 1));
            }
            return out;
        }
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (out.size() >= MAX_ELEMENTS) break;
                out.put(String.valueOf(e.getKey()), toTree(e.getValue(), depth - 1));
            }
            return out;
        }
        Class<?> cls = value.getClass();
        if (cls.getName().startsWith("java.")) return value.toString();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("$class", cls.getName());
        try {
            if (cls.isRecord()) {
                for (RecordComponent rc : cls.getRecordComponents()) {
                    var accessor = rc.getAccessor();
                    accessor.setAccessible(true);
                    out.put(rc.getName(), toTree(accessor.invoke(value), depth - 1));
                }
            } else {
                for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        out.putIfAbsent(f.getName(), toTree(f.get(value), depth - 1));
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            out.put("$string", value.toString());
            out.put("$reflectionError", e.toString());
        }
        return out;
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
