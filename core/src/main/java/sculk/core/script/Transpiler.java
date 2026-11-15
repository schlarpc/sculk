package sculk.core.script;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import sculk.core.Sculk;
import sculk.core.util.Bytes;
import sculk.shaded.org.mozilla.javascript.Context;
import sculk.shaded.org.mozilla.javascript.Function;
import sculk.shaded.org.mozilla.javascript.JavaScriptException;
import sculk.shaded.org.mozilla.javascript.Scriptable;
import sculk.shaded.org.mozilla.javascript.ScriptableObject;

/**
 * Runs script source through Babel before Rhino sees it, lowering the
 * syntax Rhino gets wrong or lacks: block scoping in loops (Rhino gives a
 * loop body's const and let one binding for the whole loop,
 * mozilla/rhino#326 and #1386), {@code for (const x of ...)}, {@code ?.},
 * {@code ??}, nested destructuring and object rest and spread. Only those
 * transforms: what Rhino handles stays as written. {@code retainLines}
 * keeps every line where it was, so errors point at the script's own lines.
 *
 * <p>Babel ({@code @babel/standalone}, packed under {@code sculk/babel/},
 * see nix/babel/) is 3 MB of JavaScript that takes Rhino about two
 * seconds to load, so it loads once per game, on a background thread from
 * {@link #preload} at startup, in its own sealed scope (no Java access).
 * Results are kept by a hash of the source, in memory and, given a cache
 * directory, on disk, so an unchanged script is not transformed again,
 * even after a restart. Without Babel on the classpath, source passes
 * through as it is.
 */
public final class Transpiler {
    static final String RESOURCE = "/sculk/babel/babel.min.js";
    static final String VERSION_RESOURCE = "/sculk/babel/VERSION";
    /** The transforms, and what keeps the output line for line. Part of every cache key. */
    static final String OPTIONS = "{plugins: ['transform-block-scoping', 'transform-optional-chaining',"
            + " 'transform-nullish-coalescing-operator', 'transform-destructuring', 'transform-object-rest-spread',"
            + " 'transform-spread'], retainLines: true, sourceType: 'script', compact: false, babelrc: false,"
            + " configFile: false}";
    private static final int MEMO = 256;

    private static volatile Transpiler shared;

    /** A syntax error Babel found: where, and what. Lines and columns count from 1. */
    public static final class SyntaxError extends RuntimeException {
        public final int line, column;

        SyntaxError(String message, int line, int column) {
            super(message);
            this.line = line;
            this.column = column;
        }
    }

    private final CompletableFuture<Babel> babel;
    private final Path cacheDir;
    private final String version;
    private final Map<String, String> memo = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MEMO;
        }
    };

    private record Babel(Scriptable scope, Function transform) {}

    private Transpiler(Path cacheDir) {
        this.cacheDir = cacheDir;
        this.version = readVersion();
        this.babel = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                babel.complete(load());
            } catch (Throwable e) {
                babel.completeExceptionally(e);
            }
        }, "sculk-babel-load");
        t.setDaemon(true);
        t.start();
    }

    /** Starts loading Babel in the background, keeping results under {@code cacheDir} (null: memory only). */
    public static synchronized Transpiler preload(Path cacheDir) {
        if (shared == null) shared = new Transpiler(cacheDir);
        return shared;
    }

    /** The game's transpiler, started (without a disk cache) if nothing preloaded it. */
    public static Transpiler shared() {
        Transpiler t = shared;
        return t != null ? t : preload(null);
    }

    /** True if Babel is on the classpath (it is in every built jar, and in core's tests). */
    public static boolean available() {
        return Transpiler.class.getResource(RESOURCE) != null;
    }

    /**
     * {@code source} as Rhino should run it. Waits for Babel if it is
     * still loading. Throws {@link SyntaxError} for source Babel cannot
     * parse. Without Babel, returns {@code source} unchanged.
     */
    public String transform(String source) {
        if (!available()) return source;
        String key = key(source);
        synchronized (memo) {
            String hit = memo.get(key);
            if (hit != null) return hit;
        }
        String out = readCache(key);
        if (out == null) {
            out = run(source);
            writeCache(key, out);
        }
        synchronized (memo) {
            memo.put(key, out);
        }
        return out;
    }

    private String run(String source) {
        Babel b;
        try {
            b = babel.join();
        } catch (RuntimeException e) {
            Sculk.LOG.warn("Babel did not load; running scripts as written", e);
            return source;
        }
        // One Babel, one caller at a time: its scope is not for concurrent use.
        synchronized (b) {
            try (Context cx = Context.enter()) {
                cx.setLanguageVersion(Context.VERSION_ES6);
                cx.setInterpretedMode(true);
                Object result = b.transform().call(cx, b.scope(), b.scope(), new Object[] {source});
                return Context.toString(result);
            } catch (JavaScriptException e) {
                throw syntaxError(e);
            }
        }
    }

    /** Babel's error ({@code {message, loc: {line, column}}}, column from 0) as a SyntaxError. */
    private static RuntimeException syntaxError(JavaScriptException e) {
        Object v = e.getValue();
        if (v instanceof Scriptable err) {
            Object loc = ScriptableObject.getProperty(err, "loc");
            Object message = ScriptableObject.getProperty(err, "message");
            int line = 1, column = 1;
            if (loc instanceof Scriptable l) {
                line = (int) Context.toNumber(ScriptableObject.getProperty(l, "line"));
                column = (int) Context.toNumber(ScriptableObject.getProperty(l, "column")) + 1;
            }
            String text = message instanceof String s ? s : Context.toString(message);
            // Babel ends its message with "(line:column)"; the problem already says where.
            text = text.replaceFirst("\\s*\\(\\d+:\\d+\\)\\s*$", "").replaceFirst("^unknown(\\.js)?:\\s*", "");
            return new SyntaxError(text, line, column);
        }
        return e;
    }

    private static Babel load() throws IOException {
        long start = System.nanoTime();
        String code;
        try (InputStream in = Transpiler.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("no " + RESOURCE + " on the classpath");
            code = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Context cx = Context.enter()) {
            cx.setLanguageVersion(Context.VERSION_ES6);
            // Interpreted: compiling 3 MB of JavaScript to classes would fill metaspace for nothing.
            cx.setInterpretedMode(true);
            Scriptable scope = cx.initSafeStandardObjects();
            cx.evaluateString(scope, "var self = this, globalThis = this, console = {log: function () {},"
                    + " warn: function () {}, error: function () {}, info: function () {}, debug: function () {}};",
                    "babel-shims", 1, null);
            cx.evaluateString(scope, code, "babel.min.js", 1, null);
            Function transform = (Function) cx.evaluateString(scope,
                    "(function (src) { return Babel.transform(src, " + OPTIONS + ").code; })", "babel-transform", 1, null);
            Sculk.LOG.info("Babel loaded for scripts in {} ms", (System.nanoTime() - start) / 1_000_000);
            return new Babel(scope, transform);
        }
    }

    private String key(String source) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            d.update(version.getBytes(StandardCharsets.UTF_8));
            d.update((byte) 0);
            d.update(OPTIONS.getBytes(StandardCharsets.UTF_8));
            d.update((byte) 0);
            d.update(source.getBytes(StandardCharsets.UTF_8));
            return Bytes.hex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String readCache(String key) {
        if (cacheDir == null) return null;
        try {
            Path f = cacheDir.resolve(key + ".js");
            return Files.exists(f) ? Files.readString(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void writeCache(String key, String code) {
        if (cacheDir == null) return;
        try {
            Files.createDirectories(cacheDir);
            Path tmp = Files.createTempFile(cacheDir, key, ".tmp");
            Files.writeString(tmp, code);
            Files.move(tmp, cacheDir.resolve(key + ".js"), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Sculk.LOG.debug("could not cache a transpiled script", e);
        }
    }

    private static String readVersion() {
        try (InputStream in = Transpiler.class.getResourceAsStream(VERSION_RESOURCE)) {
            return in == null ? "unknown" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "unknown";
        }
    }
}
