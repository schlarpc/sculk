package sculk.core.script;

import sculk.shaded.org.mozilla.javascript.Context;
import sculk.shaded.org.mozilla.javascript.Function;
import sculk.shaded.org.mozilla.javascript.RhinoException;
import sculk.shaded.org.mozilla.javascript.Scriptable;
import sculk.shaded.org.mozilla.javascript.ScriptableObject;

/**
 * Embeds Rhino. One global scope per host. Rhino scopes are not thread-safe
 * and hooks fire on Netty threads, so every entry point is synchronized.
 * Scripts see only the core API, never raw Minecraft classes, so one script
 * runs on every platform.
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
        // Hand Java Strings/numbers/booleans to scripts as JS primitives, so
        // that e.channel === 'mod:x' works as script authors expect.
        cx.getWrapFactory().setJavaPrimitiveWrap(false);
        return cx;
    }

    /** Exposes a Java object to scripts as a global. */
    public synchronized void bind(String name, Object value) {
        try (Context cx = enter()) {
            ScriptableObject.putProperty(scope, name, Context.javaToJS(value, scope, cx));
        }
    }

    /** Evaluates {@code source} and returns the result converted to a Java object. */
    public synchronized Object eval(String sourceName, String source) {
        try (Context cx = enter()) {
            Object result = cx.evaluateString(scope, source, sourceName, 1, null);
            return toJava(result);
        }
    }

    /** Calls a script function with Java arguments. */
    public synchronized Object call(Function fn, Object... args) {
        try (Context cx = enter()) {
            Object[] jsArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) jsArgs[i] = Context.javaToJS(args[i], scope, cx);
            return toJava(fn.call(cx, scope, scope, jsArgs));
        }
    }

    /** Converts script values to plain Java: JS objects and arrays become Maps and Lists (Rhino implements both). */
    static Object toJava(Object value) {
        if (value instanceof sculk.shaded.org.mozilla.javascript.Wrapper w) return w.unwrap();
        if (value instanceof Scriptable) return value;
        if (value == Context.getUndefinedValue()) return null;
        return Context.jsToJava(value, Object.class);
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
}
