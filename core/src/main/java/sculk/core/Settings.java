package sculk.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sculk's settings: one table, which each platform turns into its loader's
 * own config file (NeoForge's {@code sculk-startup.toml}, Forge's
 * {@code sculk-common.toml}, and on Fabric, which has no config API,
 * {@code sculk.toml} written by {@link #tomlFile}). A JVM system property
 * {@code -Dsculk.<key>} overrides the file, for dev runs and tests.
 */
public final class Settings {
    /** One setting: its key, default (a String, Boolean, Integer or Long, which is also its type) and comment. */
    public record Setting(String key, Object defaultValue, String comment) {}

    public static final List<Setting> ALL = List.of(
            new Setting("control", "",
                    "Control server (web UI, MCP, sculkctl): empty takes the first free port from 25580, a number asks for that port (0: any), off turns it off."),
            new Setting("controlFile", "sculk-control.json",
                    "Where the control server writes its port and token, relative to the game directory."),
            new Setting("controlToken", "",
                    "A fixed bearer token (16 characters or more) instead of a random one per launch; empty for random."),
            new Setting("captureBytes", 2L << 30,
                    "The control server captures every event to a temporary directory, deleted on exit, up to this many bytes; 0 keeps recent events in memory only."),
            new Setting("captureDir", "",
                    "Where to make that capture directory; empty for the system temporary directory."),
            new Setting("controlEvents", 50_000,
                    "How many of the newest events are also kept whole in memory."),
            new Setting("controlFrameBytes", 128L << 20,
                    "How many bytes of those events' frames are kept in memory."),
            new Setting("scriptDir", "",
                    "Where scripts are installed; empty for config/sculk/scripts."),
            new Setting("watch", true,
                    "Reload scripts when their files change."),
            new Setting("eventLog", "",
                    "Also append every event to this JSON Lines file; empty for none. It grows without limit."));

    /** Where a platform's config values come from: the value of a key (of its default's type), or null when unset. */
    @FunctionalInterface
    public interface Source {
        Object get(String key);
    }

    private static volatile Source source = key -> null;

    private Settings() {}

    /** Sets where values come from; {@code Sculk.init} calls it with the platform's. */
    static void use(Source s) {
        source = s == null ? key -> null : s;
    }

    /** The value of {@code key}, as a string: the system property, else the config, else the default. */
    public static String string(String key) {
        String prop = System.getProperty("sculk." + key);
        if (prop != null) return prop;
        Object v = null;
        try {
            v = source.get(key);
        } catch (RuntimeException e) {
            Sculk.LOG.warn("cannot read setting {}: {}", key, e.toString());
        }
        if (v == null) v = setting(key).defaultValue();
        return String.valueOf(v);
    }

    public static long longValue(String key) {
        String s = string(key).trim();
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            Sculk.LOG.warn("setting {} is not a number ({}); using {}", key, s, setting(key).defaultValue());
            return ((Number) setting(key).defaultValue()).longValue();
        }
    }

    public static int intValue(String key) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, longValue(key)));
    }

    public static boolean bool(String key) {
        return Boolean.parseBoolean(string(key).trim());
    }

    /** The value, or null when it is empty. */
    public static String optional(String key) {
        String s = string(key).trim();
        return s.isEmpty() ? null : s;
    }

    public static Setting setting(String key) {
        for (Setting s : ALL) {
            if (s.key().equals(key)) return s;
        }
        throw new IllegalArgumentException("no setting " + key);
    }

    // ---- a plain TOML file, for loaders without a config API ----

    /**
     * Reads {@code file}, a flat TOML file of {@code key = value} lines, and
     * writes it first, with every setting at its default and commented, when
     * it does not exist. Settings it leaves out keep their defaults.
     */
    public static Source tomlFile(Path file) {
        Map<String, Object> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String l = line.strip();
                if (l.isEmpty() || l.startsWith("#") || l.startsWith("[")) continue;
                int eq = l.indexOf('=');
                if (eq < 0) continue;
                String key = l.substring(0, eq).strip();
                String raw = l.substring(eq + 1).strip();
                values.put(key, parseValue(raw));
            }
        } catch (NoSuchFileException e) {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, defaultToml(), StandardCharsets.UTF_8);
            } catch (IOException w) {
                Sculk.LOG.warn("cannot write {}: {}", file, w.toString());
            }
        } catch (IOException e) {
            Sculk.LOG.warn("cannot read {}: {}; using the defaults", file, e.toString());
        }
        return values::get;
    }

    /** A TOML value: a quoted string (basic or literal), else the text up to a comment. */
    static Object parseValue(String raw) {
        if (raw.startsWith("\"")) {
            StringBuilder out = new StringBuilder();
            for (int i = 1; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c == '"') break;
                if (c == '\\' && i + 1 < raw.length()) {
                    char n = raw.charAt(++i);
                    out.append(switch (n) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        default -> n;
                    });
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        if (raw.startsWith("'")) {
            int end = raw.indexOf('\'', 1);
            return raw.substring(1, end < 0 ? raw.length() : end);
        }
        int hash = raw.indexOf('#');
        return (hash < 0 ? raw : raw.substring(0, hash)).strip().replace("_", "");
    }

    static String defaultToml() {
        StringBuilder out = new StringBuilder("# sculk settings. A JVM option -Dsculk.<key>=<value> overrides any of them.\n");
        for (Setting s : ALL) {
            out.append('\n').append("# ").append(s.comment()).append('\n');
            out.append(s.key()).append(" = ").append(tomlValue(s.defaultValue())).append('\n');
        }
        return out.toString();
    }

    private static String tomlValue(Object v) {
        if (v instanceof String str) return "\"" + str.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return String.valueOf(v);
    }
}
