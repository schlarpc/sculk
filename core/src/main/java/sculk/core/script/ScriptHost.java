package sculk.core.script;

import java.util.ArrayList;
import java.util.List;
import sculk.shaded.org.mozilla.javascript.CompilerEnvirons;
import sculk.shaded.org.mozilla.javascript.Context;
import sculk.shaded.org.mozilla.javascript.ErrorReporter;
import sculk.shaded.org.mozilla.javascript.EvaluatorException;
import sculk.shaded.org.mozilla.javascript.Function;
import sculk.shaded.org.mozilla.javascript.Parser;
import sculk.shaded.org.mozilla.javascript.RhinoException;
import sculk.shaded.org.mozilla.javascript.Scriptable;
import sculk.shaded.org.mozilla.javascript.ScriptableObject;

/**
 * Embeds Rhino. The standard objects live in one shared scope; each script
 * gets its own scope on top of it ({@link #newScope()}), so scripts see the
 * same built-ins but not each other's top-level variables. Rhino scopes are
 * not thread-safe and hooks fire on Netty threads, so every entry point is
 * synchronized on the host. Scripts see only the core API, never raw
 * Minecraft classes, so one script runs on every platform.
 */
public final class ScriptHost {
    private final ScriptableObject shared;

    public ScriptHost() {
        try (Context cx = enter()) {
            shared = cx.initStandardObjects();
        }
    }

    private static Context enter() {
        Context cx = Context.enter();
        cx.setLanguageVersion(Context.VERSION_ES6);
        // Hand Java Strings/numbers/booleans to scripts as JS primitives, so
        // that e.channel === 'mod:x' works as script authors expect.
        cx.getWrapFactory().setJavaPrimitiveWrap(false);
        // Packages.net.minecraft... resolves through sculk's own loader, which sees the game and its
        // mods, whatever thread (a Netty thread, the render thread) the script runs on.
        cx.setApplicationClassLoader(ScriptHost.class.getClassLoader());
        return cx;
    }

    /** A new top-level scope whose prototype holds the standard objects: Rhino's "sharing scopes" pattern. */
    public synchronized Scriptable newScope() {
        try (Context cx = enter()) {
            Scriptable scope = cx.newObject(shared);
            scope.setPrototype(shared);
            scope.setParentScope(null);
            return scope;
        }
    }

    /** Exposes a Java object to scripts in {@code scope} as a global. */
    public synchronized void bind(Scriptable scope, String name, Object value) {
        try (Context cx = enter()) {
            ScriptableObject.putProperty(scope, name, Context.javaToJS(value, scope, cx));
        }
    }

    /** Evaluates {@code source} in {@code scope} and returns the result converted to a Java object. */
    public synchronized Object eval(Scriptable scope, String sourceName, String source) {
        try (Context cx = enter()) {
            return toJava(cx.evaluateString(scope, loopConstsAsLet(source, cx), sourceName, 1, null));
        }
    }

    /** Calls a script function with Java arguments. */
    public synchronized Object call(Function fn, Object... args) {
        try (Context cx = enter()) {
            Scriptable scope = ScriptableObject.getTopLevelScope(fn);
            Object[] jsArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) jsArgs[i] = Context.javaToJS(args[i], scope, cx);
            return toJava(fn.call(cx, scope, scope, jsArgs));
        }
    }

    /**
     * Calls a decoder function with the live object and its tree, the tree
     * as plain JavaScript objects and arrays so the function can change it
     * in place. Returns what the function returned, or the (possibly
     * changed) tree when it returned nothing.
     */
    public synchronized Object callDecoder(Function fn, Object value, Object tree) {
        try (Context cx = enter()) {
            Scriptable scope = ScriptableObject.getTopLevelScope(fn);
            Object jsTree = treeToJs(tree, cx, scope);
            Object result = fn.call(cx, scope, scope, new Object[] {Context.javaToJS(value, scope, cx), jsTree});
            return toJava(result == Context.getUndefinedValue() ? jsTree : result);
        }
    }

    /**
     * An ObjectModel tree as JavaScript objects and arrays, for a value a
     * {@code sculk} method hands back to the script calling it (Rhino shows
     * a Java Map's keys to JSON.stringify but not as properties).
     */
    static Object jsTree(Object tree, Scriptable scope) {
        Context cx = Context.getCurrentContext();
        if (cx == null) return tree; // Called from Java, not from a script.
        return treeToJs(tree, cx, scope);
    }

    /** An ObjectModel tree (maps, lists, strings, numbers) as JavaScript objects and arrays. */
    private static Object treeToJs(Object v, Context cx, Scriptable scope) {
        if (v instanceof java.util.Map<?, ?> m) {
            Scriptable o = cx.newObject(scope);
            for (var e : m.entrySet()) ScriptableObject.putProperty(o, String.valueOf(e.getKey()), treeToJs(e.getValue(), cx, scope));
            return o;
        }
        if (v instanceof java.util.List<?> l) {
            Object[] items = new Object[l.size()];
            for (int i = 0; i < items.length; i++) items[i] = treeToJs(l.get(i), cx, scope);
            return cx.newArray(scope, items);
        }
        if (v instanceof Number n) return n.doubleValue();
        if (v == null || v instanceof String || v instanceof Boolean) return v;
        return Context.javaToJS(v, scope, cx);
    }

    /**
     * {@code source} with each {@code const} declared inside a loop (up to
     * the nearest function) written as {@code let}, padded to the same
     * width so lines and columns do not move. Rhino gives a loop body's
     * {@code const} one binding for the whole loop, initialised on the
     * first pass only, so {@code const x = i * 2} stays 0 on every pass
     * (mozilla/rhino#326, open since 2017); its {@code let} gets the right
     * value each pass when it has an initialiser, which a const always has.
     * A loop head's {@code for (const x of ...)}, which Rhino does not parse
     * at all, becomes {@code for (let x of ...)} the same way. Source that
     * does not parse is returned as it is, for eval to report.
     */
    static String loopConstsAsLet(String source, Context cx) {
        if (!source.contains("const")) return source;
        source = LOOP_HEAD_CONST.matcher(source).replaceAll("$1let  ");
        List<Integer> at = new ArrayList<>();
        try {
            CompilerEnvirons env = new CompilerEnvirons();
            env.initFromContext(cx);
            sculk.shaded.org.mozilla.javascript.ast.AstRoot root = new Parser(env).parse(source, "const-check", 1);
            root.visit(node -> {
                if (node instanceof sculk.shaded.org.mozilla.javascript.ast.VariableDeclaration d && d.isConst() && inLoop(d)) {
                    at.add(d.getAbsolutePosition());
                }
                return true;
            });
        } catch (RuntimeException e) {
            return source;
        }
        if (at.isEmpty()) return source;
        StringBuilder out = new StringBuilder(source);
        for (int pos : at) {
            if (out.indexOf("const", pos) == pos) out.replace(pos, pos + 5, "let  ");
        }
        return out.toString();
    }

    private static final java.util.regex.Pattern LOOP_HEAD_CONST = java.util.regex.Pattern.compile("(\\bfor\\s*\\(\\s*)const\\b");

    private static boolean inLoop(sculk.shaded.org.mozilla.javascript.ast.AstNode node) {
        for (var n = node.getParent(); n != null; n = n.getParent()) {
            if (n instanceof sculk.shaded.org.mozilla.javascript.ast.Loop) return true;
            if (n instanceof sculk.shaded.org.mozilla.javascript.ast.FunctionNode) return false;
        }
        return false;
    }

    /** One problem {@link #check} found. Lines and columns count from 1. */
    public record Problem(int line, int column, String message, boolean warning) {}

    /** Parses {@code source} without running it and returns its syntax errors and warnings. */
    public static List<Problem> check(String sourceName, String source) {
        List<Problem> out = new ArrayList<>();
        ErrorReporter reporter = new ErrorReporter() {
            @Override
            public void warning(String message, String name, int line, String lineSource, int offset) {
                out.add(new Problem(line, offset + 1, message, true));
            }

            @Override
            public void error(String message, String name, int line, String lineSource, int offset) {
                out.add(new Problem(line, offset + 1, message, false));
            }

            @Override
            public EvaluatorException runtimeError(String message, String name, int line, String lineSource, int offset) {
                return new EvaluatorException(message, name, line, lineSource, offset);
            }
        };
        try (Context cx = enter()) {
            CompilerEnvirons env = new CompilerEnvirons();
            env.initFromContext(cx);
            env.setRecoverFromErrors(true);
            env.setIdeMode(true);
            env.setReservedKeywordAsIdentifier(true);
            // As eval sees it: for (const x of ...) is rewritten before Rhino parses it.
            new Parser(env, reporter).parse(LOOP_HEAD_CONST.matcher(source).replaceAll("$1let  "), sourceName, 1);
        } catch (EvaluatorException e) {
            if (out.isEmpty()) out.add(new Problem(e.lineNumber(), Math.max(1, e.columnNumber() + 1), e.details(), false));
        } catch (RuntimeException e) {
            out.add(new Problem(1, 1, String.valueOf(e.getMessage()), false));
        }
        return out;
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
        Scriptable scope = host.newScope();
        Object regexp = host.eval(scope, "selftest", "/s(c)ulk/.exec('sculk')[1]");
        if (!"c".equals(regexp)) {
            throw new IllegalStateException("Rhino RegExp self-test returned " + regexp);
        }
        String message;
        try {
            host.eval(scope, "selftest", "(");
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
