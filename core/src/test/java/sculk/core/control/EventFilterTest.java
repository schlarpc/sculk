package sculk.core.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EventFilterTest {
    private static Map<String, Object> packet() {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", 7L);
        e.put("kind", "packet");
        e.put("dir", "inbound");
        e.put("owner", "waystones");
        e.put("type", "minecraft:update_recipes");
        e.put("len", 1500);
        e.put("injected", false);
        e.put("channel", null);
        e.put("registeredVia", List.of("balm", "waystones"));
        e.put("payload", Map.of("pos", Map.of("x", 12, "y", 64), "name", "../../evil"));
        e.put("hex", "0aff");
        return e;
    }

    private static boolean test(String filter) {
        return EventFilter.compile(filter).test(packet());
    }

    @Test
    void blankMatchesEverything() {
        assertTrue(test(null));
        assertTrue(test("   "));
    }

    @Test
    void equalityAndBooleans() {
        assertTrue(test("kind == packet && dir == inbound && owner != minecraft"));
        assertFalse(test("kind == packet && dir == outbound"));
        assertTrue(test("kind == \"packet\" and not injected"));
        assertTrue(test("dir == outbound || owner == waystones"));
        assertTrue(test("!(dir == outbound)"));
        assertFalse(test("not (kind == packet)"));
    }

    @Test
    void numbers() {
        assertTrue(test("len > 1000"));
        assertTrue(test("len >= 1500 && len <= 1500"));
        assertFalse(test("len < 1000"));
        assertTrue(test("len == 0x5dc"));
        assertTrue(test("id == 7"));
        assertFalse(test("missing > 3"), "comparisons against an absent field are false");
    }

    @Test
    void presence() {
        assertTrue(test("payload"));
        assertFalse(test("channel"), "null is absent");
        assertFalse(test("injected"), "false is absent");
        assertFalse(test("nosuchfield"));
        assertTrue(test("channel == null"));
    }

    @Test
    void containsAndMatches() {
        assertTrue(test("type contains recipes"));
        assertTrue(test("payload contains \"../\""), "contains on an object searches its JSON");
        assertTrue(test("type matches \"recipe|chat\""));
        assertTrue(test("type ~ '^minecraft:'"));
        assertFalse(test("type matches '^chat'"));
    }

    @Test
    void dottedPathsAndLists() {
        assertTrue(test("payload.pos.x == 12"));
        assertTrue(test("payload.pos.y > 60"));
        assertTrue(test("registeredVia == waystones"), "a list matches when any element does");
        assertTrue(test("registeredVia.0 == balm"));
        assertFalse(test("registeredVia.1 == balm"));
        assertTrue(test("registeredVia contains waystones"));
    }

    @Test
    void listsOfObjects() {
        Map<String, Object> e = Map.of("errors", List.of(Map.of("script", "a.js"), Map.of("script", "b.js")));
        assertTrue(EventFilter.compile("errors.script == b.js").test(e));
        assertFalse(EventFilter.compile("errors.script == c.js").test(e));
    }

    @Test
    void quotedKeywordsAreValues() {
        Map<String, Object> e = Map.of("word", "and");
        assertTrue(EventFilter.compile("word == \"and\"").test(e));
    }

    @Test
    void syntaxErrors() {
        assertThrows(IllegalArgumentException.class, () -> EventFilter.compile("kind =="));
        assertThrows(IllegalArgumentException.class, () -> EventFilter.compile("(kind == packet"));
        assertThrows(IllegalArgumentException.class, () -> EventFilter.compile("kind == packet)"));
        assertThrows(IllegalArgumentException.class, () -> EventFilter.compile("\"kind\" == packet"));
        assertThrows(IllegalArgumentException.class, () -> EventFilter.compile("kind == 'open"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EventFilter.compile("kind packet"));
        assertEquals("unexpected 'packet'", e.getMessage());
    }

    @Test
    void badRegexIsASyntaxError() {
        assertThrows(IllegalArgumentException.class, () -> EventFilter.compile("type matches '('"));
    }
}
