package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import sculk.core.script.ScriptHost;

class ScriptHostTest {
    @Test
    void evaluatesModernJavaScript() {
        ScriptHost host = new ScriptHost();
        var scope = host.newScope();
        host.bind(scope, "prefix", "sculk:");
        Object result = host.eval(scope, "test.js", "const f = (s) => `${prefix}${s}`; f('ok')");
        assertEquals("sculk:ok", result);
    }

    @Test
    void scopesShareBuiltInsButNotGlobals() {
        ScriptHost host = new ScriptHost();
        var a = host.newScope();
        var b = host.newScope();
        host.eval(a, "a.js", "var x = 1; const y = 2;");
        assertEquals("undefined", host.eval(b, "b.js", "typeof x + ''"));
        assertEquals("undefined", host.eval(b, "b.js", "typeof y + ''"));
        assertEquals(3.0, ((Number) host.eval(b, "b.js", "JSON.parse('[1,2]').length + Math.max(1, 1)")).doubleValue());
    }

    @Test
    void checkReportsSyntaxErrorsWithLines() {
        var problems = ScriptHost.check("x.js", "let a = 1;\nlet b = ;\n");
        assertEquals(false, problems.isEmpty());
        assertEquals(2, problems.get(0).line());
        assertEquals(true, ScriptHost.check("x.js", "sculk.on('packet', e => e.drop());").stream().noneMatch(p -> !p.warning()));
    }

    @Test
    void relocatedRhinoSelfTestPasses() {
        assertEquals("rhino sculk.shaded.org.mozilla.javascript ok", ScriptHost.selfTest());
    }
}
