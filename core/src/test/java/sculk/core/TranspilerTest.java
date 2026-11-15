package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import sculk.core.script.ScriptHost;
import sculk.core.script.Transpiler;
import sculk.shaded.org.mozilla.javascript.RhinoException;

class TranspilerTest {
    @Test
    void babelIsThereInTests() {
        assertTrue(Transpiler.available(), "nix build .#babel feeds core's test resources");
    }

    @Test
    void loopsGetABindingPerPassAndModernSyntaxRuns() {
        Object out = FakeGame.sculk().scripts().eval("""
                (() => {
                  const a = [], b = [], c = [], fs = [];
                  for (let i = 0; i < 3; i++) { const x = i * 10; a.push(x); }        // rhino#326
                  for (let j = 0; j < 2; ++j) { let y; b.push(String(y)); y = 1; }     // rhino#1386
                  for (let k = 0; k < 3; k++) { const kk = k; fs.push(() => kk); }    // closures
                  for (const z of [5, 6]) c.push(z);                                  // Rhino cannot parse this
                  const o = { p: { q: 1 } };
                  const { p: { q }, ...rest } = { p: { q: 7 }, r: 8 };
                  return [a.join(','), b.join(','), fs.map(f => f()).join(','), c.join(','),
                          o?.p?.q, o?.nope?.q ?? 'dflt', q, JSON.stringify(rest), Math.max(...[1, 9, 3])].join('|');
                })()
                """);
        assertEquals("0,10,20|undefined,undefined|0,1,2|5,6|1|dflt|7|{\"r\":8}|9", out);
    }

    @Test
    void errorsPointAtTheScriptsOwnLines() {
        RhinoException syntax = assertThrowsRhino("const a = 1;\nconst b = ;\n");
        assertEquals(2, syntax.lineNumber(), syntax.details());
        RhinoException runtime = assertThrowsRhino("for (const z of [1]) {\n  let q = z?.w;\n}\nnull.boom;\n");
        assertEquals(4, runtime.lineNumber(), runtime.details());
    }

    @Test
    void theSyntaxCheckAcceptsWhatRunsAndFindsWhatDoesNot() {
        assertEquals(List.of(), ScriptHost.check("x.js", "for (const z of [1]) print(z?.a ?? 2);"));
        List<ScriptHost.Problem> bad = ScriptHost.check("x.js", "let a = 1;\nlet b = ;\n");
        assertEquals(1, bad.size(), bad.toString());
        assertEquals(2, bad.get(0).line());
    }

    private static RhinoException assertThrowsRhino(String source) {
        try {
            new ScriptHost().eval(new ScriptHost().newScope(), "t.js", source);
        } catch (RhinoException e) {
            return e;
        }
        throw new AssertionError("expected an error from: " + source);
    }
}
