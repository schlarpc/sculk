package sculk.core.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import sculk.core.log.EventLog;

/**
 * A small display-filter language over event log entries, in the style of
 * Wireshark's:
 *
 * <pre>
 *   kind == packet &amp;&amp; dir == inbound &amp;&amp; owner != minecraft
 *   type matches "recipe|chat" and len > 1000
 *   payload contains "waystones" or data.step == "done"
 *   channel and not injected
 * </pre>
 *
 * <ul>
 *   <li>Fields are entry keys; dots walk into nested objects and lists
 *       ({@code payload.pos.x}, {@code registeredVia.0}). A name applied to a
 *       list applies to each element, and a comparison holds if it holds for
 *       any of them.</li>
 *   <li>Operators: {@code == != < <= > >=}, {@code contains} (substring; on
 *       objects and lists, of their JSON), {@code matches} or {@code ~}
 *       (regular expression, found anywhere).</li>
 *   <li>A field alone is true when it is present and not false.</li>
 *   <li>{@code && || !} or {@code and or not}; parentheses.</li>
 *   <li>Values are quoted strings, numbers, {@code true false null}, or a
 *       bare word, which is a string.</li>
 * </ul>
 */
public final class EventFilter {
    private static final Predicate<Map<String, Object>> ALL = e -> true;

    private final List<String> tokens = new ArrayList<>();
    private final List<Boolean> quoted = new ArrayList<>();
    private int pos;

    private EventFilter() {}

    /** Compiles {@code source}; a blank filter matches everything. Throws IllegalArgumentException on bad syntax. */
    public static Predicate<Map<String, Object>> compile(String source) {
        if (source == null || source.isBlank()) return ALL;
        EventFilter f = new EventFilter();
        f.tokenize(source);
        Predicate<Map<String, Object>> p = f.or();
        if (f.pos < f.tokens.size()) throw new IllegalArgumentException("unexpected '" + f.tokens.get(f.pos) + "'");
        return e -> {
            try {
                return p.test(e);
            } catch (RuntimeException ex) {
                return false;
            }
        };
    }

    // ---- lexer ----

    private void tokenize(String s) {
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"' || c == '\'') {
                StringBuilder sb = new StringBuilder();
                int j = i + 1;
                while (true) {
                    if (j >= s.length()) throw new IllegalArgumentException("unterminated string");
                    char d = s.charAt(j++);
                    if (d == c) break;
                    if (d == '\\' && j < s.length()) d = s.charAt(j++);
                    sb.append(d);
                }
                add(sb.toString(), true);
                i = j;
            } else if ("()".indexOf(c) >= 0) {
                add(String.valueOf(c), false);
                i++;
            } else if ("=!<>&|~".indexOf(c) >= 0) {
                int j = i + 1;
                if (j < s.length() && "=&|".indexOf(s.charAt(j)) >= 0) j++;
                add(s.substring(i, j), false);
                i = j;
            } else {
                int j = i;
                while (j < s.length() && !Character.isWhitespace(s.charAt(j)) && "()=!<>&|~\"'".indexOf(s.charAt(j)) < 0) j++;
                add(s.substring(i, j), false);
                i = j;
            }
        }
    }

    private void add(String token, boolean wasQuoted) {
        tokens.add(token);
        quoted.add(wasQuoted);
    }

    private boolean peek(String... options) {
        if (pos >= tokens.size() || quoted.get(pos)) return false;
        for (String o : options) {
            if (tokens.get(pos).equals(o)) return true;
        }
        return false;
    }

    private boolean take(String... options) {
        if (!peek(options)) return false;
        pos++;
        return true;
    }

    // ---- parser ----

    private Predicate<Map<String, Object>> or() {
        Predicate<Map<String, Object>> left = and();
        while (take("||", "or")) left = left.or(and());
        return left;
    }

    private Predicate<Map<String, Object>> and() {
        Predicate<Map<String, Object>> left = unary();
        while (take("&&", "and")) left = left.and(unary());
        return left;
    }

    private Predicate<Map<String, Object>> unary() {
        if (take("!", "not")) return unary().negate();
        if (take("(")) {
            Predicate<Map<String, Object>> inner = or();
            if (!take(")")) throw new IllegalArgumentException("missing ')'");
            return inner;
        }
        return comparison();
    }

    private Predicate<Map<String, Object>> comparison() {
        if (pos >= tokens.size()) throw new IllegalArgumentException("expected a field name");
        if (quoted.get(pos)) throw new IllegalArgumentException("expected a field name, not a string");
        String[] path = tokens.get(pos++).split("\\.");
        if (pos < tokens.size() && !quoted.get(pos)) {
            String op = tokens.get(pos);
            BiPredicate<Object, Object> test = switch (op) {
                case "==" -> (a, b) -> compare(a, b) == 0;
                case "!=" -> (a, b) -> compare(a, b) != 0;
                case "<" -> (a, b) -> a != null && compare(a, b) < 0;
                case "<=" -> (a, b) -> a != null && compare(a, b) <= 0;
                case ">" -> (a, b) -> a != null && compare(a, b) > 0;
                case ">=" -> (a, b) -> a != null && compare(a, b) >= 0;
                case "contains" -> (a, b) -> a != null && text(a).contains(String.valueOf(b));
                case "matches", "~" -> (a, b) -> a != null && ((Pattern) b).matcher(text(a)).find();
                default -> null;
            };
            if (test != null) {
                pos++;
                if (pos >= tokens.size()) throw new IllegalArgumentException("expected a value after " + op);
                Object value = value(tokens.get(pos), quoted.get(pos));
                pos++;
                Object operand = op.equals("matches") || op.equals("~") ? Pattern.compile(String.valueOf(value)) : value;
                boolean wholeValue = op.equals("contains") || op.equals("matches") || op.equals("~");
                return e -> any(lookup(e, path, 0), operand, test, wholeValue);
            }
        }
        return e -> any(lookup(e, path, 0), null, (a, b) -> a != null && !Boolean.FALSE.equals(a), true);
    }

    private static Object value(String token, boolean wasQuoted) {
        if (wasQuoted) return token;
        switch (token) {
            case "true": return Boolean.TRUE;
            case "false": return Boolean.FALSE;
            case "null": return null;
            default:
        }
        Double number = number(token);
        return number != null ? number : token;
    }

    // ---- evaluation ----

    /** Walks {@code path} into {@code value}. A name applied to a list is applied to every element. */
    private static Object lookup(Object value, String[] path, int index) {
        if (index == path.length || value == null) return value;
        String key = path[index];
        if (value instanceof Map<?, ?> m) return lookup(m.get(key), path, index + 1);
        if (value instanceof List<?> l) {
            if (key.chars().allMatch(Character::isDigit)) {
                int i = Integer.parseInt(key);
                return i < l.size() ? lookup(l.get(i), path, index + 1) : null;
            }
            List<Object> out = new ArrayList<>();
            for (Object o : l) {
                Object found = lookup(o, path, index);
                if (found instanceof List<?> nested) out.addAll(nested);
                else if (found != null) out.add(found);
            }
            return out;
        }
        return null;
    }

    /** Applies {@code test} to a value; for lists, to the list as a whole (if asked) or to any element. */
    private static boolean any(Object left, Object right, BiPredicate<Object, Object> test, boolean wholeValue) {
        if (left instanceof List<?> l) {
            if (wholeValue && test.test(left, right)) return true;
            for (Object o : l) {
                if (test.test(o, right)) return true;
            }
            return false;
        }
        return test.test(left, right);
    }

    private static int compare(Object a, Object b) {
        if (a == null || b == null) return a == b ? 0 : (a == null ? -1 : 1);
        Double x = number(a), y = number(b);
        if (x != null && y != null) return Double.compare(x, y);
        return text(a).compareTo(text(b));
    }

    private static Double number(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (!(o instanceof String s) || s.isEmpty()) return null;
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) return (double) Long.parseLong(s.substring(2), 16);
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(Object o) {
        return o instanceof Map<?, ?> || o instanceof List<?> ? EventLog.toJson(o) : String.valueOf(o);
    }
}
