package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import sculk.core.script.ScriptHost;

class ScriptHostTest {
    @Test
    void evaluatesModernJavaScript() {
        ScriptHost host = new ScriptHost();
        host.bind("prefix", "sculk:");
        Object result = host.eval("test.js", "const f = (s) => `${prefix}${s}`; f('ok')");
        assertEquals("sculk:ok", result);
    }
}
