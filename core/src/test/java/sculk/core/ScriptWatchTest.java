package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sculk.core.script.ScriptManager;

/** Changes made to script files outside sculk, found by polling. */
class ScriptWatchTest {
    private static final ScriptManager SCRIPTS = FakeGame.sculk().scripts();

    /** Two polls: the first sees a file change, the second finds it held still and applies it. */
    private static void settle() {
        SCRIPTS.pollOnce();
        SCRIPTS.pollOnce();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> status(String name) {
        return SCRIPTS.list().stream().filter(m -> name.equals(m.get("name"))).findFirst()
                .map(m -> (Map<String, Object>) m.get("status")).orElse(null);
    }

    private static Object global(String name, String variable) {
        return SCRIPTS.eval("var s = sculk.scope('" + name + "'); s ? s." + variable + " : null");
    }

    /** Waits briefly for an event log line holding every needle (the log is written in the background). */
    private static boolean logged(String... needles) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            try {
                for (String line : Files.readAllLines(FakeGame.EVENT_LOG)) {
                    if (java.util.Arrays.stream(needles).allMatch(line::contains)) return true;
                }
                Thread.sleep(20);
            } catch (IOException | InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    private static Path write(String name, String source) throws IOException {
        Path p = FakeGame.SCRIPT_DIR.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, source);
        return p;
    }

    @Test
    void aRunningScriptFollowsItsFile() throws IOException {
        String name = "watch-" + System.nanoTime() + ".js";
        Path file = write(name, "var v = 1;\n");
        SCRIPTS.pollOnce();
        assertNull(status(name).get("hash"), "not applied until it holds still for a poll");
        SCRIPTS.pollOnce();
        assertEquals("running", status(name).get("state"), "a new startup file starts");
        assertEquals(1, ((Number) global(name, "v")).intValue());

        Files.writeString(file, "var v = 22;\n");
        settle();
        assertEquals(22, ((Number) global(name, "v")).intValue(), "restarted with the new content");

        Files.writeString(file, "var v = ;\n");
        settle();
        assertEquals("running", status(name).get("state"));
        assertTrue(status(name).containsKey("rejected"), "a change that does not compile is refused");
        assertEquals(22, ((Number) global(name, "v")).intValue(), "the old version keeps running");

        Files.writeString(file, "var v = 333;\n");
        settle();
        assertFalse(status(name).containsKey("rejected"));
        assertEquals(333, ((Number) global(name, "v")).intValue());

        Files.delete(file);
        settle();
        assertNull(status(name));
        assertFalse(SCRIPTS.runningNames().contains(name));
    }

    @Test
    void stoppedScriptsStayStoppedAndDisablingStops() throws IOException {
        String name = "watch-stop-" + System.nanoTime() + ".js";
        Path file = write(name, "var v = 1;\n");
        settle();
        SCRIPTS.stop(name);
        Files.writeString(file, "var v = 2222;\n");
        settle();
        assertEquals("user", status(name).get("why"), "stopped by hand stays stopped");

        SCRIPTS.run(name);
        Files.move(file, file.resolveSibling(name + ScriptManager.DISABLED));
        settle();
        assertEquals("disabled", status(name).get("why"));
        Files.move(file.resolveSibling(name + ScriptManager.DISABLED), file);
        settle();
        assertEquals("running", status(name).get("state"), "enabling a startup script starts it");
        SCRIPTS.delete(name);
    }

    @Test
    void aFailedScriptStartsOnceItsFileIsFixed() throws IOException {
        String name = "watch-fail-" + System.nanoTime() + ".js";
        Path file = write(name, "throw new Error('not yet');\n");
        settle();
        assertEquals("failed", status(name).get("state"));
        Files.writeString(file, "var fixed = true;\n");
        settle();
        assertEquals("running", status(name).get("state"));
        SCRIPTS.delete(name);
    }

    @Test
    void aRenameOnDiskKeepsItRunning() throws IOException {
        String name = "watch-mv-" + System.nanoTime() + ".js", to = "watch-mv-" + System.nanoTime() + "-b.js";
        Path file = write(name, "// ==Sculk==\n// @run manual\n// ==/Sculk==\nvar v = 1;\n");
        settle();
        SCRIPTS.run(name);
        Files.move(file, file.resolveSibling(to));
        settle();
        assertNull(status(name));
        assertEquals("running", status(to).get("state"), "a running manual script keeps running under its new name");
        assertTrue(logged("\"kind\":\"script_file\"", "\"action\":\"rename\"", "\"script\":\"" + name + "\"", "\"by\":\"disk\""));
        SCRIPTS.delete(to);
    }

    @Test
    void savingThroughTheApiIsNotSeenTwice() throws IOException {
        String name = "watch-api-" + System.nanoTime() + ".js";
        SCRIPTS.save(name, "var v = 1;\n", "");
        Object since = status(name).get("since");
        SCRIPTS.save(name, "var v = 22;\n", null);
        Object restarted = status(name).get("since");
        settle();
        settle();
        assertEquals(restarted, status(name).get("since"), "the poll finds nothing new to restart");
        assertTrue(since != null && restarted != null);
        SCRIPTS.delete(name);
    }
}
