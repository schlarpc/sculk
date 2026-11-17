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
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import sculk.core.Sculk;
import sculk.core.script.Lifecycle.Disk;
import sculk.core.script.Lifecycle.Failed;
import sculk.core.script.Lifecycle.Plan;
import sculk.core.script.Lifecycle.Present;
import sculk.core.script.Lifecycle.RunState;
import sculk.core.script.Lifecycle.Running;
import sculk.core.script.Lifecycle.Start;
import sculk.core.script.Lifecycle.Stay;
import sculk.core.script.Lifecycle.Stop;
import sculk.core.script.Lifecycle.StopReason;
import sculk.core.script.Lifecycle.Stopped;
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
 * touching the others. What happens to a script when its file changes, or
 * when it is run, stopped or reloaded, is decided by {@link Lifecycle};
 * this class reads the files, carries out its plans and logs them. A change
 * made through this class and one made on disk by anything else go through
 * the same rules: {@link #watch} polls the files, and every method here that
 * writes a file reconciles it straight away.
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
     * Each script's state; running ones in start order. Its own lock, not
     * this manager's: a script callback holds the host's lock and may look
     * up another script's scope, while the manager holds its own lock to
     * start scripts in the host. A name missing here is not started.
     */
    private final Map<String, RunState> states = Collections.synchronizedMap(new LinkedHashMap<>());
    /** Each script's file as last reconciled: what a change is compared against. */
    private final Map<String, Present> seen = new HashMap<>();

    /** Enough of a file's attributes to notice it changed without reading it. */
    private record Fingerprint(String real, FileTime modified, long size) {}

    /** Each file's fingerprint when it was last read. */
    private final Map<String, Fingerprint> settled = new HashMap<>();
    /** The fingerprints at the previous poll: a file is read once it holds still for one poll. */
    private Map<String, Fingerprint> lastPoll = Map.of();
    private ScheduledExecutorService watcher;

    /** Why a script started or stopped, for the timeline. */
    private enum Cause {
        /** Its file changed, through this class or on disk. */
        FILE,
        /** Someone ran or stopped it. */
        USER,
        /** Launch or reload. */
        LAUNCH;

        String json() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

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

    /** Windows device names, which stay devices with any extension. */
    private static final Pattern RESERVED = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

    /**
     * Names are file names in {@link #dir} and nothing else: no separators,
     * no "..", no leading dot, no drive or stream colons, no device names.
     * Every path this class touches is {@code dir.resolve(name)} of a name
     * that passed this check, or a path from {@code sculk.scripts}.
     */
    private static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches() || name.contains("..") || RESERVED.matcher(name).matches()) {
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

    private RunState state(String name) {
        return states.getOrDefault(name, Lifecycle.NOT_STARTED);
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
        out.put("status", status(state(s.name())));
        return out;
    }

    /**
     * A state as JSON: {@code {"state":"running","hash","since","listeners","timers","rejected"?:{"hash","error"}}},
     * {@code {"state":"failed","hash","error","at"}} or {@code {"state":"stopped","why"}}.
     */
    static Map<String, Object> status(RunState s) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (s instanceof Running r) {
            out.put("state", "running");
            out.put("hash", r.hash());
            out.put("since", r.since());
            out.put("listeners", r.api().listenerCount());
            out.put("timers", r.api().timerCount());
            if (r.rejected() != null) out.put("rejected", Map.of("hash", r.rejected().hash(), "error", r.rejected().error()));
        } else if (s instanceof Failed f) {
            out.put("state", "failed");
            out.put("hash", f.hash());
            out.put("error", f.error());
            out.put("at", f.at());
        } else if (s instanceof Stopped st) {
            out.put("state", "stopped");
            out.put("why", st.why().json());
        }
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
                m.put("status", status(state(name)));
                out.add(m);
            }
        }
        return out;
    }

    /** Names of the scripts with a live scope, in start order. */
    public List<String> runningNames() {
        synchronized (states) {
            return states.entrySet().stream().filter(e -> e.getValue() instanceof Running).map(Map.Entry::getKey).toList();
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
        if (CONSOLE.equals(name)) {
            ScriptApi api = console;
            return api == null ? null : api.scope();
        }
        return states.get(name) instanceof Running r ? r.api().scope() : null;
    }

    // ---- reading files ----

    /** A script's file now, recording its fingerprint as read. */
    private Disk observe(String name) {
        Script s;
        String source;
        try {
            s = find(name);
            source = read(s.path());
        } catch (ScriptError e) {
            if (e.status() != 404) throw e;
            settled.remove(name);
            return Lifecycle.ABSENT;
        }
        Fingerprint f = fingerprint(s.path());
        if (f != null) settled.put(name, f);
        else settled.remove(name);
        Map<String, String> meta = metadata(source);
        return new Present(s.path(), s.external(), s.enabled(), source, hash(source),
                s.external() || runMode(meta).equals("startup"), distMismatch(meta), syntaxError(name, source));
    }

    private Present observePresent(String name) {
        if (observe(name) instanceof Present p) return p;
        throw new ScriptError(404, "no script named " + name);
    }

    /** The first syntax error in {@code source}, as {@code "message (name:line)"}, or null. */
    static String syntaxError(String name, String source) {
        for (ScriptHost.Problem p : ScriptHost.check(name, source)) {
            if (!p.warning()) return p.message() + " (" + name + ":" + p.line() + ")";
        }
        return null;
    }

    private static Fingerprint fingerprint(Path path) {
        try {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
            return new Fingerprint(path.toRealPath().toString(), a.lastModifiedTime(), a.size());
        } catch (IOException e) {
            return null;
        }
    }

    // ---- changes ----

    /**
     * Creates or replaces a script. With {@code base} null the write is
     * unconditional; with {@code base} empty the script must not exist yet;
     * otherwise {@code base} must be the hash of the current source, or the
     * write fails with 409 (someone else changed it). Then the new file goes
     * through the {@link Lifecycle} rules like any other change: a running
     * script restarts (or keeps running, if the new source does not
     * compile), and a new startup script starts.
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
        audit(name, created ? "create" : "save", null, "api");
        Map<String, Object> restart = reconcile(name, observe(name), false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        if (restart != null) out.put("restart", restart);
        out.putAll(info(s, source));
        return out;
    }

    private void write(Path path, String source) {
        try {
            // A script that is a symlink (to a checkout, say) is written through, not replaced by a file.
            if (Files.isSymbolicLink(path)) path = path.toRealPath();
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
        try {
            Files.delete(s.path());
        } catch (IOException e) {
            throw new ScriptError(500, "cannot delete " + s.path() + ": " + e);
        }
        audit(name, "delete", null, "api");
        reconcile(name, observe(name), false);
        return Map.of("ok", true, "name", name);
    }

    public synchronized Map<String, Object> rename(String name, String to) {
        Script s = mutable(name);
        if (to == null || to.isBlank()) throw new ScriptError(400, "to is required");
        checkName(to);
        if (name.equals(to)) return info(s, read(s.path()));
        // A case-only rename is fine on case-insensitive file systems; anything else must be new.
        if (exists(to) && !name.equalsIgnoreCase(to)) throw new ScriptError(409, to + " already exists");
        Path target = dir.resolve(to + (s.enabled() ? "" : DISABLED));
        try {
            Files.move(s.path(), target);
        } catch (IOException e) {
            throw new ScriptError(500, "cannot rename " + s.path() + ": " + e);
        }
        audit(name, "rename", to, "api");
        Present moved = observePresent(to);
        Map<String, Object> restart = moveState(name, to, moved);
        Map<String, Object> out = new LinkedHashMap<>();
        if (restart != null) out.put("restart", restart);
        out.putAll(info(new Script(to, target, false, s.enabled()), moved.source()));
        return out;
    }

    /** Enables or disables a script: the file is renamed, then reconciled (disabling stops it; enabling starts a startup script). */
    public synchronized Map<String, Object> setEnabled(String name, boolean enabled) {
        Script s = mutable(name);
        if (s.enabled() != enabled) {
            Path target = dir.resolve(name + (enabled ? "" : DISABLED));
            try {
                Files.move(s.path(), target);
            } catch (IOException e) {
                throw new ScriptError(500, "cannot " + (enabled ? "enable " : "disable ") + name + ": " + e);
            }
            s = new Script(name, target, false, enabled);
            audit(name, enabled ? "enable" : "disable", null, "api");
        }
        Present now = observePresent(name);
        Map<String, Object> restart = reconcile(name, now, false);
        Map<String, Object> out = new LinkedHashMap<>();
        if (restart != null) out.put("restart", restart);
        out.putAll(info(s, now.source()));
        return out;
    }

    private Script mutable(String name) {
        Script s = find(name);
        if (s.external()) throw new ScriptError(400, name + " comes from -Dsculk.scripts; change it there");
        return s;
    }

    /**
     * Brings a script's state in line with its file, now {@code after}.
     * Returns the start's outcome if it started or a change was refused,
     * else null. {@code fromDisk}: the change was found by {@link #pollOnce},
     * so it is logged here (changes made through this class log themselves).
     */
    private Map<String, Object> reconcile(String name, Disk after, boolean fromDisk) {
        Present before = seen.get(name);
        Disk was = before == null ? Lifecycle.ABSENT : before;
        if (was.equals(after)) return null;
        if (after instanceof Present p) seen.put(name, p);
        else seen.remove(name);
        if (fromDisk) audit(name, diskAction(was, after), null, "disk");
        Map<String, Object> out = apply(name, Lifecycle.fileChanged(state(name), was, after, now()), Cause.FILE);
        if (after instanceof Lifecycle.Absent) states.remove(name);
        return out;
    }

    private static String diskAction(Disk before, Disk after) {
        if (!(before instanceof Present b)) return "create";
        if (!(after instanceof Present a)) return "delete";
        if (b.enabled() != a.enabled()) return a.enabled() ? "enable" : "disable";
        return "change";
    }

    /** Moves a script's state to its new name and carries out {@link Lifecycle#renamed}. */
    private Map<String, Object> moveState(String from, String to, Present after) {
        RunState old = state(from);
        stopInstance(from, Cause.FILE);
        states.remove(from);
        seen.remove(from);
        settled.remove(from);
        seen.put(to, after);
        states.put(to, Lifecycle.NOT_STARTED);
        return apply(to, Lifecycle.renamed(old, after, now()), Cause.FILE);
    }

    // ---- watching the files ----

    /** Polls the script files every {@code periodMillis} and reconciles the ones that changed. Idempotent. */
    public synchronized void watch(long periodMillis) {
        if (watcher != null) return;
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sculk-script-watch");
            t.setDaemon(true);
            return t;
        });
        String[] lastError = {null};
        watcher.scheduleWithFixedDelay(() -> {
            try {
                pollOnce();
                lastError[0] = null;
            } catch (RuntimeException | LinkageError e) {
                // Every poll would repeat it: log a failure when it first appears, not twice a second.
                String text = e.toString();
                if (!text.equals(lastError[0])) Sculk.LOG.warn("watching scripts: {}", text);
                lastError[0] = text;
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * One poll: every file whose fingerprint differs from when it was last
     * read, and has not changed since the previous poll (so an editor has
     * finished writing it), is read and reconciled. A file that disappears
     * while another appears with the same content, in one poll, is a rename.
     * Public for tests, which call it instead of {@link #watch}.
     */
    public synchronized void pollOnce() {
        Map<String, Fingerprint> now = new HashMap<>();
        for (Script s : scan()) {
            Fingerprint f = fingerprint(s.path());
            if (f != null) now.put(s.name(), f);
        }
        TreeSet<String> names = new TreeSet<>(now.keySet());
        names.addAll(settled.keySet());
        Map<String, Disk> ready = new LinkedHashMap<>();
        for (String name : names) {
            Fingerprint f = now.get(name);
            if (Objects.equals(f, settled.get(name)) || !Objects.equals(f, lastPoll.get(name))) continue;
            try {
                ready.put(name, observe(name));
            } catch (ScriptError e) {
                Sculk.LOG.warn("cannot read script {}: {}", name, e.getMessage());
                if (f != null) settled.put(name, f);
            }
        }
        lastPoll = now;

        // Renames first, so the old name is not taken for a deletion.
        List<String> gone = new ArrayList<>();
        for (var e : ready.entrySet()) {
            if (e.getValue() instanceof Lifecycle.Absent && seen.containsKey(e.getKey())) gone.add(e.getKey());
        }
        for (var e : List.copyOf(ready.entrySet())) {
            if (!(e.getValue() instanceof Present p) || seen.containsKey(e.getKey())) continue;
            String from = gone.stream().filter(g -> seen.get(g).hash().equals(p.hash())).findFirst().orElse(null);
            if (from == null) continue;
            gone.remove(from);
            ready.remove(from);
            audit(from, "rename", e.getKey(), "disk");
            moveState(from, e.getKey(), p);
        }
        // A renamed script's new name is in seen now, so reconciling it changes nothing.
        for (var e : ready.entrySet()) reconcile(e.getKey(), e.getValue(), true);
    }

    // ---- running ----

    private static long now() {
        return System.currentTimeMillis();
    }

    /**
     * Carries out a plan. Returns the outcome {@code {"ok":..., "result"|"error":...}}
     * when it started the script, refused a change, or was asked for by
     * someone ({@link Cause#USER}); null when nothing worth reporting happened.
     */
    private Map<String, Object> apply(String name, Plan plan, Cause cause) {
        RunState before = state(name);
        if (plan instanceof Start st) return start(name, st.from().hash(), st.from().source(), cause);
        if (plan instanceof Stop sp) {
            stopInstance(name, cause);
            states.put(name, new Stopped(sp.why()));
            return null;
        }
        RunState next = ((Stay) plan).next();
        if (before instanceof Running && !(next instanceof Running)) throw new IllegalStateException("a plan to stay cannot stop " + name);
        states.put(name, next);
        if (next instanceof Running r && r.rejected() != null) {
            boolean fresh = !(before instanceof Running b && r.rejected().equals(b.rejected()));
            if (fresh) logError(name, "compile", cause, r.rejected().error(), true);
            if (fresh || cause == Cause.USER) return outcome(false, r.rejected().error(), true);
        } else if (next instanceof Failed f) {
            boolean fresh = !(before instanceof Failed b && b.hash().equals(f.hash()));
            if (fresh || cause == Cause.USER) {
                logError(name, "compile", cause, f.error(), false);
                return outcome(false, f.error(), false);
            }
        }
        return null;
    }

    private static Map<String, Object> outcome(boolean ok, String error, boolean kept) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("error", error);
        if (kept) out.put("kept", "the previous version is still running");
        return out;
    }

    /**
     * (Re)starts a script from its file, whatever its run mode, enabled or
     * not: an explicit run always runs. A source that does not compile
     * leaves a running script running. Returns {@code {"ok":..., "result"|"error":..., info}}.
     */
    public synchronized Map<String, Object> run(String name) {
        Script s = find(name);
        Present p = observePresent(name);
        // The run is from the file as it is now, so that is what the next change is compared to.
        seen.put(name, p);
        Map<String, Object> out = apply(name, Lifecycle.run(state(name), p, now()), Cause.USER);
        out = out == null ? new LinkedHashMap<>() : new LinkedHashMap<>(out);
        out.putAll(info(s, p.source()));
        return out;
    }

    /**
     * Starts a script from source alone, replacing any running script of
     * that name; throws if it fails. For tests and embedders.
     */
    public synchronized void load(String name, String source) {
        Map<String, Object> r = start(name, hash(source), source, Cause.USER);
        if (!Boolean.TRUE.equals(r.get("ok"))) throw new IllegalStateException(String.valueOf(r.get("error")));
    }

    /** Stops a running script, or gives up on one that failed to start. */
    public synchronized Map<String, Object> stop(String name) {
        RunState s = state(name);
        if (!Lifecycle.wanted(s)) throw new ScriptError(409, name + " is not running");
        apply(name, Lifecycle.stop(s), Cause.USER);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("name", name);
        out.put("status", status(state(name)));
        return out;
    }

    /** Closes the live instance, if any, leaving its state for the caller to set. */
    private void stopInstance(String name, Cause cause) {
        if (!(states.get(name) instanceof Running r)) return;
        r.api().close();
        Map<String, Object> e = Sculk.entry("script_stop", Map.of());
        e.put("script", name);
        e.put("cause", cause.json());
        engine.log(e);
    }

    /** Runs {@code source} as script {@code name} in a fresh scope, stopping any earlier instance first. */
    private Map<String, Object> start(String name, String hash, String source, Cause cause) {
        stopInstance(name, cause);
        ScriptApi api = new ScriptApi(engine, this, host, timers, name);
        long t = now();
        // Re-inserted, so running scripts stay in start order.
        states.remove(name);
        states.put(name, new Running(hash, api, t, null));
        Map<String, Object> out = new LinkedHashMap<>();
        String error = null;
        try {
            Object value = api.load(source);
            out.put("ok", true);
            out.put("result", ScriptApi.jsonSafe(value, 6));
            Sculk.LOG.info("started script {}", name);
        } catch (RhinoException e) {
            error = ScriptApi.describe(e);
            Sculk.LOG.error("script {} failed to start", name, e);
        } catch (RuntimeException | LinkageError e) {
            error = e.toString();
            Sculk.LOG.error("script {} failed to start", name, e);
        }
        if (error == null) {
            Map<String, Object> event = Sculk.entry("script_run", Map.of());
            event.put("script", name);
            event.put("cause", cause.json());
            engine.log(event);
        } else {
            // A script that throws while starting is stopped: half its listeners would be worse than none.
            api.close();
            states.put(name, new Failed(hash, error, t));
            out.put("ok", false);
            out.put("error", error);
            logError(name, "start", cause, error, false);
        }
        return out;
    }

    private void logError(String name, String callback, Cause cause, String error, boolean kept) {
        Map<String, Object> event = Sculk.entry("script_error", Map.of());
        event.put("script", name);
        event.put("callback", callback);
        event.put("cause", cause.json());
        event.put("error", error);
        if (kept) event.put("kept", true);
        engine.log(event);
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
        for (String name : runningNames()) stopInstance(name, Cause.LAUNCH);
        states.clear();
        if (console != null) console.close();
        console = null;
        host = new ScriptHost();
        return startAll();
    }

    /** Reads every script afresh and starts the enabled startup scripts ({@link Lifecycle#launch}). */
    public synchronized Map<String, Object> startAll() {
        List<String> loaded = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        seen.clear();
        settled.clear();
        for (Script s : scan()) {
            Disk d;
            try {
                d = observe(s.name());
            } catch (ScriptError e) {
                errors.add(Map.of("script", s.name(), "error", e.getMessage()));
                continue;
            }
            if (!(d instanceof Present p)) continue;
            seen.put(s.name(), p);
            apply(s.name(), Lifecycle.launch(p, now()), Cause.LAUNCH);
            RunState st = state(s.name());
            if (st instanceof Running) loaded.add(s.name());
            else if (st instanceof Failed f) errors.add(Map.of("script", s.name(), "error", f.error()));
            else if (st instanceof Stopped stopped && stopped.why() == StopReason.WRONG_DIST) skipped.add(Map.of("script", s.name(), "reason", p.skip()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", errors.isEmpty());
        result.put("loaded", loaded);
        result.put("skipped", skipped);
        result.put("errors", errors);
        return result;
    }

    private void audit(String name, String action, String to, String by) {
        Map<String, Object> e = Sculk.entry("script_file", Map.of());
        e.put("script", name);
        e.put("action", action);
        if (to != null) e.put("to", to);
        e.put("by", by);
        engine.log(e);
    }
}
