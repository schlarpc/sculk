package sculk.core.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PlainValuesTest {
    @Test
    void wholeNumbersBecomeLongsOnlyWhereALongHoldsThem() {
        assertEquals(42L, ScriptApi.plain(42.0d));
        assertEquals(-1e300, ScriptApi.plain(-1e300));
        assertEquals(1e300, ScriptApi.jsonSafe(1e300, 2));
        assertEquals(0.5, ScriptApi.plain(0.5));
    }
}
