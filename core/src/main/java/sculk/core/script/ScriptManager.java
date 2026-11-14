package sculk.core.script;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import sculk.core.Sculk;
import sculk.core.util.Bytes;
import sculk.shaded.org.mozilla.javascript.RhinoException;
import sculk.shaded.org.mozilla.javascript.Scriptable;

/**
 * The installed scripts and the running ones.
 *
 * <p>Scripts are {@code .js} files in one directory ({@code
 * <config>/sculk/scripts}, or {@code -Dsculk.scriptDir}), so a modpack can
 * ship them. A file named {@code x.js.disabled} is script {@code x.js},
 * disabled, the way launchers disable mod jars. Files named in {@code
 * -Dsculk.scripts} are scripts too; they keep their path as their name and
 * cannot be renamed, deleted or disabled from here.
 *
 * <p>A script may start with a metadata block:
 * <pre>
 * // ==Sculk==
 * // &#64;name        Drop exhaustion sync
 * // &#64;description Stops AppleSkin's exhaustion packets reaching the client
 * // &#64;run         startup      (startup: run at launch and on reload; manual: only when asked)
 * // &#64;dist        client       (client or server: run only in a game client, or a dedicated server)
 * // ==/Sculk==
 * </pre>
 *
 * <p>Each running script has its own scope and owns its listeners and timers
 * ({@link ScriptApi}), so one script starts, stops and restarts without
 * touching the others. Saving, enabling or renaming a running script
 * restarts it; disabling, deleting or stopping one stops it.
 */
public final class ScriptManager {
    public static final String DISABLED = ".disabled";
    public static final String CONSOLE = "console";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9 _.()+,-]{0,95}\\.js");
    private static final Pattern META = Pattern.compile("^\\s*//\\s*@([A-Za-z][\\w-]*)(?:\\s+(.*?))?\\s*$");
    private static final int MAX_SOURCE = 1 << 20;

    /** A failure the caller can act on, with the HTTP status that fits it. */
    public static final class ScriptError extends RuntimeException {
        private final int status;

        ScriptError(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private final Sculk engine;
    private final Path dir;
    private final List<Path> extra;
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sculk-script-timer");
        t.setDaemon(true);
        return t;
    });
    private ScriptHost host = new ScriptHost();
    private volatile ScriptApi console;
    /**
     * Running scripts, in start order. Its own lock, not this manager's: a
     * script callback holds the host's lock and may look up another
     * script's scope, while the manager holds its own lock to start scripts
     * in the host.
     */
    private final Map<String, ScriptApi> running = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    /** The outcome of each script's last start: {t, ok, error?}. */
    private final Map<String, Map<String, Object>> lastRun = new LinkedHashMap<>();

    public ScriptManager(Sculk engine, Path dir, List<Path> extra) {
        this.engine = engine;
        this.dir = dir.toAbsolutePath().normalize();
        this.extra = List.copyOf(extra);
        try {
            Files.createDirectories(this.dir);
        } catch (IOException e) {
            Sculk.LOG.warn("cannot create script directory {}: {}", this.dir, e.toString());
        }
    }

    public Path dir() {
        return dir;
    }

    // ---- one script on disk ----

    private record Script(String name, Path path, boolean external, boolean enabled) {}

    /** Finds a script by name: a file in the directory (enabled or not), or one from {@code sculk.scripts}. */
    private Script find(String name) {
        if (name == null || name.isBlank()) throw new ScriptError(400, "name is required");
        for (Path p : extra) {
            if (p.toString().equals(name)) return new Script(name, p, true, true);
        }
        checkName(name);
        Path on = dir.resolve(name), off = dir.resolve(name + DISABLED);
        if (Files.isRegularFile(on)) return new Script(name, on, false, true);
        if (Files.isRegularFile(off)) return new Script(name, off, false, false);
        throw new ScriptError(404, "no script named " + name);
    }

    private static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches() || name.contains("..")) {
            throw new ScriptError(400, "bad script name '" + name + "': use letters, digits, spaces and _ . ( ) + , -, ending in .js");
        }
    }

    private boolean exists(String name) {
        return Files.exists(dir.resolve(name)) || Files.exists(dir.resolve(name + DISABLED))
                || extra.stream().anyMatch(p -> p.toString().equals(name));
    }

    /** All scripts, sculk.scripts ones first, then the directory's in name order. */
    private List<Script> scan() {
        List<Script> out = new ArrayList<>();
        for (Path p : extra) out.add(new Script(p.toString(), p, true, true));
        Map<String, Script> found = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path p : files) {
                if (!Files.isRegularFile(p)) continue;
                String file = p.getFileName().toString();
                boolean enabled = !file.endsWith(DISABLED);
                String name = enabled ? file : file.substring(0, file.length() - DISABLED.length());
                if (!NAME.matcher(name).matches()) continue;
                // Both x.js and x.js.disabled: the enabled one wins.
                if (enabled || !found.containsKey(name)) found.put(name, new Script(name, p, false, enabled));
            }
        } catch (NoSuchFileException e) {
            // Nothing installed.
        } catch (IOException e) {
            Sculk.LOG.warn("cannot list {}: {}", dir, e.toString());
        }
        out.addAll(found.values());
        return out;
    }

    /** The metadata block's {@code @key value} lines; repeated keys keep the last value. */
    public static Map<String, String> metadata(String source) {
        Map<String, String> meta = new LinkedHashMap<>();
        boolean inside = false;
        for (String line : source.split("\n", 200)) {
            String t = line.strip();
            if (t.matches("//\\s*==Sculk==")) {
                inside = true;
            } else if (t.matches("//\\s*==/Sculk==")) {
                break;
            } else if (inside) {
                Matcher m = META.matcher(line);
                if (m.matches()) meta.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2) == null ? "" : m.group(2));
                else if (!t.startsWith("//")) break;
            } else if (!t.isEmpty() && !t.startsWith("//")) {
                break;
            }
        }
        return meta;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (NoSuchFileException e) {
            throw new ScriptError(404, "no such file " + path);
        } catch (IOException e) {
            throw new ScriptError(500, "cannot read " + path + ": " + e);
        }
    }

    public static String hash(String source) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return Bytes.hex(java.util.Arrays.copyOf(d, 12));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String runMode(Map<String, String> meta) {
        return "manual".equalsIgnoreCase(meta.getOrDefault("run", "").trim()) ? "manual" : "startup";
    }

    /** Null if the script may run in this process, else why not. */
    private String distMismatch(Map<String, String> meta) {
        String want = meta.getOrDefault("dist", meta.getOrDefault("side", "")).trim().toLowerCase(Locale.ROOT);
        if (want.isEmpty() || want.equals("both") || want.equals("any")) return null;
        String here = engine.platform().dedicatedServer() ? "server" : "client";
        if (!want.equals("client") && !want.equals("server")) return "unknown @dist '" + want + "' (client, server or both)";
        return want.equals(here) ? null : "only for a " + (want.equals("server") ? "dedicated server" : "game client");
    }

    private Map<String, Object> info(Script s, String source) {
        Map<String, String> meta = metadata(source);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", s.name());
        out.put("origin", s.external() ? "property" : "dir");
        out.put("path", s.path().toAbsolutePath().toString());
        out.put("enabled", s.enabled());
        out.put("run", runMode(meta));
        out.put("title", meta.getOrDefault("name", ""));
        out.put("description", meta.getOrDefault("description", ""));
        out.put("meta", meta);
        String skip = distMismatch(meta);
        if (skip != null) out.put("skip", skip);
        out.put("hash", hash(source));
        out.put("size", source.length());
        try {
            out.put("modified", Files.getLastModifiedTime(s.path()).toMillis());
        } catch (IOException ignored) {
            // Gone since the scan.
        }
        ScriptApi api = running.get(s.name());
        int listeners = api == null ? 0 : api.listenerCount(), pendingTimers = api == null ? 0 : api.timerCount();
        out.put("state", api == null ? "stopped" : listeners + pendingTimers > 0 ? "active" : "idle");
        out.put("listeners", listeners);
        out.put("timers", pendingTimers);
        if (lastRun.containsKey(s.name())) out.put("lastRun", lastRun.get(s.name()));
        return out;
    }

    // ---- queries ----

    /** Every installed script with its metadata and state. */
    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Script s : scan()) {
            String source;
            try {
                source = read(s.path());
            } catch (ScriptError e) {
                continue;
            }
            out.add(info(s, source));
        }
        // Scripts started from source alone (tests, sculk.scripts files since deleted) still show.
        for (String name : runningNames()) {
            if (out.stream().noneMatch(m -> name.equals(m.get("name")))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", name);
                m.put("origin", "memory");
                m.put("enabled", true);
                ScriptApi api = running.get(name);
                m.put("state", api.listenerCount() + api.timerCount() > 0 ? "active" : "idle");
                m.put("listeners", api.listenerCount());
                m.put("timers", api.timerCount());
                out.add(m);
            }
        }
        return out;
    }

    /** Names of the scripts with a live scope, in start order. */
    public List<String> runningNames() {
        synchronized (running) {
            return List.copyOf(running.keySet());
        }
    }

    /** One script's info plus its source. */
    public synchronized Map<String, Object> source(String name) {
        Script s = find(name);
        String source = read(s.path());
        Map<String, Object> out = info(s, source);
        out.put("source", source);
        return out;
    }

    /** A script's top-level scope, or null if it is not running. */
    Scriptable scopeOf(String name) {
        ScriptApi api = CONSOLE.equals(name) ? console : running.get(name);
        return api == null ? null : api.scope();
    }

    // ---- changes ----

    /**
     * Creates or replaces a script. With {@code base} null the write is
     * unconditional; with {@code base} empty the script must not exist yet;
     * otherwise {@code base} must be the hash of the current source, or the
     * write fails with 409 (someone else changed it). A running script
     * restarts with the new source.
     */
    public synchronized Map<String, Object> save(String name, String source, String base) {
        if (name == null || name.isBlank()) throw new ScriptError(400, "name is required");
        if (source.length() > MAX_SOURCE) throw new ScriptError(413, "script too large");
        Script s;
        boolean created = false;
        if (exists(name)) {
            s = find(name);
            if (base != null) {
                if (base.isEmpty()) throw new ScriptError(409, name + " already exists");
                String current = hash(read(s.path()));
                if (!current.equals(base)) throw new ScriptError(409, name + " was changed by someone else (now " + current + ")");
            }
        } else {
            if (base != null && !base.isEmpty()) throw new ScriptError(409, name + " was deleted by someone else");
            checkName(name);
            s = new Script(name, dir.resolve(name), false, true);
            created = true;
        }
        write(s.path(), source);
        audit(name, created ? "create" : "save", null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        if (running.containsKey(name)) out.put("restart", start(name, source));
        out.putAll(info(s, source));
        return out;
    }

    private void write(Path path, String source) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Path tmp = path.resolveSibling("." + path.getFileName() + ".tmp");
            Files.writeString(tmp, source);
            try {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new ScriptError(500, "cannot write " + path + ": " + e);
        }
    }

    public synchronized Map<String, Object> delete(String name) {
        Script s = mutable(name);
        stopQuietly(name);
        try {
            Files.delete(s.path());
        } catch (IOException e) {
            throw new ScriptError(500, "cannot delete " + s.path() + ": " + e);
        }
        lastRun.remove(name);
        audit(name, "delete", null);
        return Map.of("ok", true, "name", name);
    }

    public synchronized Map<String, Object> rename(String name, String to) {
        Script s = mutable(name);
        if (to == null || to.isBlank()) throw new ScriptError(400, "to is required");
        checkName(to);
        if (name.equals(to)) return info(s, read(s.path()));
        // A case-only rename is fine on case-insensitive file systems; anything else must be new.
        if (exists(to) && !name.equalsIgnoreCase(to)) throw new ScriptError(409, to + " already exists");
        boolean wasRunning = running.containsKey(name);
        stopQuietly(name);
        Path target = dir.resolve(to + (s.enabled() ? "" : DISABLED));
        try {
            Files.move(s.path(), target);
        } catch (IOException e) {
            throw new ScriptError(500, "cannot rename " + s.path() + ": " + e);
        }
        Map<String, Object> last = lastRun.remove(name);
        if (last != null) lastRun.put(to, last);
        audit(name, "rename", to);
        Script moved = new Script(to, target, false, s.enabled());
        String source = read(target);
        Map<String, Object> out = new LinkedHashMap<>();
        if (wasRunning) out.put("restart", start(to, source));
        out.putAll(info(moved, source));
        return out;
    }

    /** Enables or disables a script. Disabling stops it; enabling starts a startup script. */
    public synchronized Map<String, Object> setEnabled(String name, boolean enabled) {
        Script s = mutable(name);
        Map<String, Object> out = new LinkedHashMap<>();
        if (s.enabled() != enabled) {
            Path target = dir.resolve(name + (enabled ? "" : DISABLED));
            try {
                Files.move(s.path(), target);
            } catch (IOException e) {
                throw new ScriptError(500, "cannot " + (enabled ? "enable " : "disable ") + name + ": " + e);
            }
            s = new Script(name, target, false, enabled);
            audit(name, enabled ? "enable" : "disable", null);
        }
        String source = read(s.path());
        if (!enabled) {
            stopQuietly(name);
        } else if (!running.containsKey(name) && runMode(metadata(source)).equals("startup") && distMismatch(metadata(source)) == null) {
            out.put("restart", start(name, source));
        }
        out.putAll(info(s, source));
        return out;
    }

    private Script mutable(String name) {
        Script s = find(name);
        if (s.external()) throw new ScriptError(400, name + " comes from -Dsculk.scripts; change it there");
        return s;
    }

    // ---- running ----

    /**
     * (Re)starts a script from its file, whatever its run mode, enabled or
     * not: an explicit run always runs. Returns {@code {"ok":..., "result"|"error":..., state}}.
     */
    public synchronized Map<String, Object> run(String name) {
        Script s = find(name);
        String source = read(s.path());
        Map<String, Object> out = start(name, source);
        out.putAll(info(s, source));
        return out;
    }

    /**
     * Starts a script from source alone, replacing any running script of
     * that name; throws if it fails. For tests and embedders.
     */
    public synchronized void load(String name, String source) {
        Map<String, Object> r = start(name, source);
        if (!Boolean.TRUE.equals(r.get("ok"))) throw new IllegalStateException(String.valueOf(r.get("error")));
    }

    /** Stops a running script. */
    public synchronized Map<String, Object> stop(String name) {
        if (!running.containsKey(name)) throw new ScriptError(409, name + " is not running");
        stopQuietly(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("name", name);
        out.put("state", "stopped");
        return out;
    }

    private void stopQuietly(String name) {
        ScriptApi api = running.remove(name);
        if (api == null) return;
        api.close();
        Map<String, Object> e = Sculk.entry("script_stop", Map.of());
        e.put("script", name);
        engine.log(e);
    }

    /** Runs {@code source} as script {@code name} in a fresh scope, stopping any earlier instance first. */
    private Map<String, Object> start(String name, String source) {
        stopQuietly(name);
        ScriptApi api = new ScriptApi(engine, this, host, timers, name);
        running.put(name, api);
        Map<String, Object> out = new LinkedHashMap<>();

        try {
            Object value = api.load(source);
            out.put("ok", true);
            out.put("result", ScriptApi.jsonSafe(value, 6));
            Sculk.LOG.info("started script {}", name);
        } catch (RhinoException e) {
            fail(name, api, out, ScriptApi.describe(e), e);
        } catch (RuntimeException | LinkageError e) {
            fail(name, api, out, e.toString(), e);
        }
        Map<String, Object> event = Sculk.entry(Boolean.TRUE.equals(out.get("ok")) ? "script_run" : "script_error", Map.of());
        event.put("script", name);
        if (out.containsKey("error")) {
            event.put("callback", "start");
            event.put("error", out.get("error"));
        }
        engine.log(event);
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("t", System.currentTimeMillis());
        last.put("ok", out.get("ok"));
        if (out.containsKey("error")) last.put("error", out.get("error"));
        lastRun.put(name, last);
        return out;
    }

    /** A script that throws while starting is stopped: half its listeners would be worse than none. */
    private void fail(String name, ScriptApi api, Map<String, Object> out, String error, Throwable t) {
        Sculk.LOG.error("script {} failed to start", name, t);
        api.close();
        running.remove(name, api);
        out.put("ok", false);
        out.put("error", error);
    }

    // ---- the console ----

    /** Evaluates code in the console's own scope, which lives until {@link #reloadAll()}. */
    public Map<String, Object> evalForRepl(String source) {
        Map<String, Object> audit = Sculk.entry("eval", Map.of());
        audit.put("source", source.length() > 4096 ? source.substring(0, 4096) + "..." : source);
        engine.log(audit);
        return console().evalForRepl(source);
    }

    /** Evaluates code in the console scope; throws on error. For tests. */
    public Object eval(String source) {
        return console().load(source);
    }

    private synchronized ScriptApi console() {
        if (console == null) console = new ScriptApi(engine, this, host, timers, CONSOLE);
        return console;
    }

    // ---- everything ----

    /**
     * Stops every script and the console, then starts the enabled startup
     * scripts in a fresh host. Returns {@code {"ok":bool,"loaded":[...],"skipped":[...],"errors":[{"script":...,"error":...}]}}.
     */
    public synchronized Map<String, Object> reloadAll() {
        for (String name : runningNames()) stopQuietly(name);
        if (console != null) console.close();
        console = null;
        host = new ScriptHost();
        return startAll();
    }

    /** Starts the enabled startup scripts. */
    public synchronized Map<String, Object> startAll() {
        List<String> loaded = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        for (Script s : scan()) {
            if (!s.enabled()) continue;
            String source;
            try {
                source = read(s.path());
            } catch (ScriptError e) {
                errors.add(Map.of("script", s.name(), "error", e.getMessage()));
                continue;
            }
            Map<String, String> meta = metadata(source);
            String skip = distMismatch(meta);
            if (!s.external() && runMode(meta).equals("manual")) continue;
            if (skip != null) {
                skipped.add(Map.of("script", s.name(), "reason", skip));
                continue;
            }
            Map<String, Object> r = start(s.name(), source);
            if (Boolean.TRUE.equals(r.get("ok"))) loaded.add(s.name());
            else errors.add(Map.of("script", s.name(), "error", r.get("error")));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", errors.isEmpty());
        result.put("loaded", loaded);
        result.put("skipped", skipped);
        result.put("errors", errors);
        return result;
    }

    private void audit(String name, String action, String to) {
        Map<String, Object> e = Sculk.entry("script_file", Map.of());
        e.put("script", name);
        e.put("action", action);
        if (to != null) e.put("to", to);
        engine.log(e);
    }
}
