package sculk.core.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static sculk.core.script.Lifecycle.ABSENT;
import static sculk.core.script.Lifecycle.NOT_STARTED;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import sculk.core.script.Lifecycle.Failed;
import sculk.core.script.Lifecycle.Plan;
import sculk.core.script.Lifecycle.Present;
import sculk.core.script.Lifecycle.Rejected;
import sculk.core.script.Lifecycle.Running;
import sculk.core.script.Lifecycle.Start;
import sculk.core.script.Lifecycle.Stay;
import sculk.core.script.Lifecycle.Stop;
import sculk.core.script.Lifecycle.StopReason;
import sculk.core.script.Lifecycle.Stopped;

class LifecycleTest {
    private static final long NOW = 1000;

    /** An enabled startup script with this content. */
    private static Present file(String hash) {
        return new Present(Path.of("x.js"), false, true, "src " + hash, hash, true, null, null);
    }

    private static Present manual(String hash) {
        return new Present(Path.of("x.js"), false, true, "src " + hash, hash, false, null, null);
    }

    private static Present disabled(Present p) {
        return new Present(p.path(), p.external(), false, p.source(), p.hash(), p.autostart(), p.skip(), p.syntaxError());
    }

    private static Present wrongDist(Present p) {
        return new Present(p.path(), p.external(), p.enabled(), p.source(), p.hash(), p.autostart(), "only for a dedicated server", p.syntaxError());
    }

    private static Present broken(String hash) {
        return new Present(Path.of("x.js"), false, true, "src " + hash, hash, true, null, "missing ; (x.js:3)");
    }

    private static Running running(String hash) {
        return new Running(hash, null, 0, null);
    }

    private static Plan file(Lifecycle.RunState s, Lifecycle.Disk before, Lifecycle.Disk after) {
        return Lifecycle.fileChanged(s, before, after, NOW);
    }

    // ---- a running script follows its file ----

    @Test
    void runningScriptRestartsWithNewContent() {
        Present b = file("b");
        assertEquals(new Start(b), file(running("a"), file("a"), b));
    }

    @Test
    void sameContentChangesNothing() {
        assertEquals(new Stay(running("a")), file(running("a"), file("a"), file("a")));
    }

    @Test
    void brokenChangeKeepsTheOldVersionRunning() {
        Running r = running("a");
        Plan p = file(r, file("a"), broken("b"));
        assertEquals(new Stay(r.withRejected(new Rejected("b", "missing ; (x.js:3)"))), p);
    }

    @Test
    void revertingABrokenChangeClearsTheRejection() {
        Running r = running("a").withRejected(new Rejected("b", "err"));
        assertEquals(new Stay(running("a")), file(r, broken("b"), file("a")));
    }

    @Test
    void fixingABrokenChangeRestarts() {
        Running r = running("a").withRejected(new Rejected("b", "err"));
        assertEquals(new Start(file("c")), file(r, broken("b"), file("c")));
    }

    @Test
    void deletingDisablingOrMovingToTheOtherDistStops() {
        assertEquals(new Stop(StopReason.DELETED), file(running("a"), file("a"), ABSENT));
        assertEquals(new Stop(StopReason.DISABLED), file(running("a"), file("a"), disabled(file("a"))));
        assertEquals(new Stop(StopReason.WRONG_DIST), file(running("a"), file("a"), wrongDist(file("b"))));
    }

    @Test
    void anExplicitlyRunDisabledScriptStillFollowsItsFile() {
        Present before = disabled(file("a")), after = disabled(file("b"));
        assertEquals(new Start(after), file(running("a"), before, after));
    }

    // ---- failed: wanted, retried on change ----

    @Test
    void failedScriptRetriesWhenItsFileChanges() {
        Failed f = new Failed("a", "boom", 0);
        assertEquals(new Start(file("b")), file(f, file("a"), file("b")));
        assertEquals(new Stay(f), file(f, file("a"), file("a")));
        assertEquals(new Stay(new Failed("c", "missing ; (x.js:3)", NOW)), file(f, file("a"), broken("c")));
        assertEquals(new Stop(StopReason.DELETED), file(f, file("a"), ABSENT));
    }

    // ---- stopped: only becoming runnable starts it ----

    @Test
    void stoppedScriptStaysStoppedWhenEdited() {
        Stopped byHand = new Stopped(StopReason.USER);
        assertEquals(new Stay(byHand), file(byHand, file("a"), file("b")));
        assertEquals(new Stay(NOT_STARTED), file(NOT_STARTED, manual("a"), manual("b")));
    }

    @Test
    void newStartupFileStarts() {
        assertEquals(new Start(file("a")), file(NOT_STARTED, ABSENT, file("a")));
        assertEquals(new Stay(NOT_STARTED), file(NOT_STARTED, ABSENT, manual("a")));
        assertEquals(new Stay(new Failed("a", "missing ; (x.js:3)", NOW)), file(NOT_STARTED, ABSENT, broken("a")));
    }

    @Test
    void enablingIsBecomingRunnable() {
        Stopped off = new Stopped(StopReason.DISABLED);
        assertEquals(new Start(file("a")), file(off, disabled(file("a")), file("a")));
        assertEquals(new Stay(NOT_STARTED), file(off, disabled(manual("a")), manual("a")));
        assertEquals(new Stay(off), file(NOT_STARTED, file("a"), disabled(file("a"))));
        assertEquals(new Start(file("a")), file(new Stopped(StopReason.WRONG_DIST), wrongDist(file("a")), file("a")));
    }

    // ---- explicit actions ----

    @Test
    void runAlwaysRunsUnlessItWouldReplaceAWorkingVersionWithABrokenOne() {
        assertEquals(new Start(file("a")), Lifecycle.run(NOT_STARTED, file("a"), NOW));
        assertEquals(new Start(disabled(file("a"))), Lifecycle.run(NOT_STARTED, disabled(file("a")), NOW));
        assertEquals(new Start(file("a")), Lifecycle.run(running("a"), file("a"), NOW));
        assertEquals(new Stay(new Failed("b", "missing ; (x.js:3)", NOW)), Lifecycle.run(NOT_STARTED, broken("b"), NOW));
        assertEquals(new Stay(running("a").withRejected(new Rejected("b", "missing ; (x.js:3)"))), Lifecycle.run(running("a"), broken("b"), NOW));
    }

    @Test
    void stopStopsRunningAndFailedScripts() {
        assertEquals(new Stop(StopReason.USER), Lifecycle.stop(running("a")));
        assertEquals(new Stop(StopReason.USER), Lifecycle.stop(new Failed("a", "e", 0)));
        assertEquals(new Stay(NOT_STARTED), Lifecycle.stop(NOT_STARTED));
    }

    @Test
    void launchStartsRunnableStartupScripts() {
        assertEquals(new Start(file("a")), Lifecycle.launch(file("a"), NOW));
        assertEquals(new Stay(NOT_STARTED), Lifecycle.launch(manual("a"), NOW));
        assertEquals(new Stay(new Stopped(StopReason.DISABLED)), Lifecycle.launch(disabled(file("a")), NOW));
        assertEquals(new Stay(new Stopped(StopReason.WRONG_DIST)), Lifecycle.launch(wrongDist(file("a")), NOW));
        assertEquals(new Stay(new Failed("a", "missing ; (x.js:3)", NOW)), Lifecycle.launch(broken("a"), NOW));
    }

    @Test
    void renameCarriesOnWhatTheOldNameWanted() {
        assertEquals(new Start(manual("a")), Lifecycle.renamed(running("a"), manual("a"), NOW));
        assertEquals(new Stay(new Stopped(StopReason.USER)), Lifecycle.renamed(new Stopped(StopReason.USER), file("a"), NOW));
        assertEquals(new Stay(new Stopped(StopReason.DISABLED)), Lifecycle.renamed(running("a"), disabled(file("a")), NOW));
    }
}
