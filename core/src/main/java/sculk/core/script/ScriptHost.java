package sculk.core.script;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * Embeds Rhino. Scripts see only the core packet model and the action API,
 * never raw Minecraft classes, so one script runs on every platform.
 */
public final class ScriptHost {
    private final ScriptableObject scope;

    public ScriptHost() {
        try (Context cx = enter()) {
            scope = cx.initStandardObjects();
        }
    }

    private static Context enter() {
        Context cx = Context.enter();
        cx.setLanguageVersion(Context.VERSION_ES6);
        return cx;
    }

    /** Exposes a Java object to scripts as a global. */
    public void bind(String name, Object value) {
        try (Context cx = enter()) {
            ScriptableObject.putProperty(scope, name, Context.javaToJS(value, scope, cx));
        }
    }

    /** Evaluates {@code source} and returns the result converted to a Java object. */
    public Object eval(String sourceName, String source) {
        try (Context cx = enter()) {
            Object result = cx.evaluateString(scope, source, sourceName, 1, null);
            return result instanceof Scriptable ? result : Context.jsToJava(result, Object.class);
        }
    }
}
