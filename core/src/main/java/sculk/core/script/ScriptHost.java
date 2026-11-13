package sculk.core.script;

import sculk.shaded.org.mozilla.javascript.Context;
import sculk.shaded.org.mozilla.javascript.RhinoException;
import sculk.shaded.org.mozilla.javascript.Scriptable;
import sculk.shaded.org.mozilla.javascript.ScriptableObject;

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

    /**
     * Exercises the parts of Rhino that relocation can break: the RegExp
     * engine (found via ServiceLoader) and error messages (a resource bundle
     * looked up by name). Returns a one-line summary, or throws.
     */
    public static String selfTest() {
        ScriptHost host = new ScriptHost();
        Object regexp = host.eval("selftest", "/s(c)ulk/.exec('sculk')[1]");
        if (!"c".equals(regexp)) {
            throw new IllegalStateException("Rhino RegExp self-test returned " + regexp);
        }
        String message;
        try {
            host.eval("selftest", "(");
            throw new IllegalStateException("Rhino accepted invalid syntax");
        } catch (RhinoException e) {
            message = e.details();
        }
        if (message == null || message.isBlank() || message.contains("no message resource found")) {
            throw new IllegalStateException("Rhino message bundle missing: " + message);
        }
        return "rhino " + Context.class.getPackage().getName() + " ok";
    }

    /** Evaluates {@code source} and returns the result converted to a Java object. */
    public Object eval(String sourceName, String source) {
        try (Context cx = enter()) {
            Object result = cx.evaluateString(scope, source, sourceName, 1, null);
            return result instanceof Scriptable ? result : Context.jsToJava(result, Object.class);
        }
    }
}
