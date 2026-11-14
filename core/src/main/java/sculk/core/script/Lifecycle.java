package sculk.core.script;

import java.nio.file.Path;
import java.util.Locale;

/**
 * What happens to a script when its file changes, or when someone runs,
 * stops or reloads it. Every function here is pure: it looks at the
 * script's state and what is on disk and returns a {@link Plan}, which
 * {@link ScriptManager} carries out. That keeps one set of rules for every
 * way a script can change, and lets the rules be tested without Rhino or a
 * file system.
 *
 * <p>The rules:
 * <ul>
 *   <li>A running script runs the file on disk. Whoever changed the file (the
 *       web UI, sculkctl, MCP, an editor, git), the script restarts with it.</li>
 *   <li>Changes are compared by content, not by event: a file that comes back
 *       to the source already running changes nothing.</li>
 *   <li>A change that does not compile never stops a running script: the old
 *       version keeps running and the rejected one is reported.</li>
 *   <li>Wanting a script to run is kept apart from it running. A script that
 *       failed to start is still wanted, so fixing its file starts it; one
 *       stopped by hand stays stopped however its file changes.</li>
 *   <li>A file that becomes runnable (created, enabled, or its {@code @dist}
 *       now matches) starts if it is a startup script, the same as enabling it.</li>
 * </ul>
 */
public final class Lifecycle {
    private Lifecycle() {}

    // ---- state ----

    /** Why a script is not running. */
    public enum StopReason {
        /** Not started since launch or the last reload: a manual script, or one never asked for. */
        NOT_STARTED,
        /** Stopped by hand. */
        USER,
        DISABLED,
        DELETED,
        /** Its {@code @dist} is the other side. */
        WRONG_DIST;

        public String json() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /** One script's state: stopped (not wanted), running, or failed (wanted, but its source did not start). */
    public sealed interface RunState permits Stopped, Running, Failed {}

    public record Stopped(StopReason why) implements RunState {}

    /**
     * Running the source with {@code hash}. {@code rejected}, if not null, is
     * a newer file that did not compile, so this older version kept running.
     * {@code api} is the live instance; the planner only carries it along.
     */
    public record Running(String hash, ScriptApi api, long since, Rejected rejected) implements RunState {
        Running withRejected(Rejected r) {
            return new Running(hash, api, since, r);
        }
    }

    /** Wanted, but the source with {@code hash} failed to start. A different file retries it. */
    public record Failed(String hash, String error, long at) implements RunState {}

    public record Rejected(String hash, String error) {}

    public static final Stopped NOT_STARTED = new Stopped(StopReason.NOT_STARTED);

    static boolean wanted(RunState s) {
        return !(s instanceof Stopped);
    }

    // ---- the file ----

    /** A script's file as last read: absent, or present with what the rules need. */
    public sealed interface Disk permits Absent, Present {}

    public record Absent() implements Disk {}

    public static final Absent ABSENT = new Absent();

    /**
     * @param autostart   starts at launch, on reload and when it becomes runnable ({@code @run startup}, or from {@code sculk.scripts})
     * @param skip        why it may not run in this process ({@code @dist}), or null
     * @param syntaxError the first syntax error, or null if it compiles
     */
    public record Present(Path path, boolean external, boolean enabled, String source, String hash,
                          boolean autostart, String skip, String syntaxError) implements Disk {
        public boolean runnable() {
            return enabled && skip == null;
        }

        StopReason blocked() {
            return !enabled ? StopReason.DISABLED : StopReason.WRONG_DIST;
        }
    }

    static boolean runnable(Disk d) {
        return d instanceof Present p && p.runnable();
    }

    // ---- plans ----

    /** What to do: keep the instance as it is (with a new state), start from a file, or stop. */
    public sealed interface Plan permits Stay, Start, Stop {}

    /** Leave any live instance alone and record {@code next}. Never turns a running script into a stopped one. */
    public record Stay(RunState next) implements Plan {}

    /** Stop any live instance and run {@code from}: {@link Running} if it starts, {@link Failed} if it throws. */
    public record Start(Present from) implements Plan {}

    /** Stop the live instance, if any: {@link Stopped}. */
    public record Stop(StopReason why) implements Plan {}

    private static Plan startOrFail(Present p, long now) {
        return p.syntaxError() != null ? new Stay(new Failed(p.hash(), p.syntaxError(), now)) : new Start(p);
    }

    /** The file went from {@code before} to {@code after}, by anyone. */
    public static Plan fileChanged(RunState s, Disk before, Disk after, long now) {
        if (!(after instanceof Present p)) {
            return wanted(s) ? new Stop(StopReason.DELETED) : new Stay(new Stopped(StopReason.DELETED));
        }
        if (runnable(before) && !p.runnable() && wanted(s)) return new Stop(p.blocked());
        if (s instanceof Running r) {
            if (p.hash().equals(r.hash())) return new Stay(r.withRejected(null));
            if (p.syntaxError() != null) return new Stay(r.withRejected(new Rejected(p.hash(), p.syntaxError())));
            return new Start(p);
        }
        if (s instanceof Failed f) {
            return p.hash().equals(f.hash()) ? new Stay(f) : startOrFail(p, now);
        }
        if (!p.runnable()) return new Stay(new Stopped(p.blocked()));
        if (!runnable(before)) return p.autostart() ? startOrFail(p, now) : new Stay(NOT_STARTED);
        return new Stay(s);
    }

    /**
     * The file was renamed, and is now {@code after} under its new name; {@code s}
     * is the state under the old name. The new name carries on what the old one
     * wanted: a wanted script restarts under it, a stopped one stays stopped.
     */
    public static Plan renamed(RunState s, Present after, long now) {
        if (!after.runnable()) return new Stay(new Stopped(after.blocked()));
        return wanted(s) ? startOrFail(after, now) : new Stay(s);
    }

    /** Someone asked to run (or restart) it. An explicit run always runs, enabled or not. */
    public static Plan run(RunState s, Present p, long now) {
        if (p.syntaxError() != null && s instanceof Running r) {
            return new Stay(r.withRejected(new Rejected(p.hash(), p.syntaxError())));
        }
        return startOrFail(p, now);
    }

    /** Someone asked to stop it. */
    public static Plan stop(RunState s) {
        return wanted(s) ? new Stop(StopReason.USER) : new Stay(s);
    }

    /** Launch or reload, with every script already stopped. */
    public static Plan launch(Present p, long now) {
        if (!p.enabled()) return new Stay(new Stopped(StopReason.DISABLED));
        if (p.skip() != null) return new Stay(new Stopped(StopReason.WRONG_DIST));
        return p.autostart() ? startOrFail(p, now) : new Stay(NOT_STARTED);
    }
}
