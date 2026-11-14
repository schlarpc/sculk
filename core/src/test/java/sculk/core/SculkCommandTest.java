package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import sculk.core.command.SculkCommand;
import sculk.core.control.StoredEvent;

class SculkCommandTest {
    private static final String MANUAL = "// ==Sculk==\n// @run manual\n// ==/Sculk==\n";

    private final List<String> replies = new ArrayList<>();
    private final List<String> opened = new ArrayList<>();

    private CommandDispatcher<String> dispatcher(boolean allowed, boolean client) {
        CommandDispatcher<String> d = new CommandDispatcher<>();
        d.register(SculkCommand.tree(s -> allowed,
                SculkCommand.Reply.<String>of((s, m) -> replies.add("ok " + m), (s, m) -> replies.add("fail " + m)),
                client ? opened::add : null));
        return d;
    }

    private int run(String command) throws CommandSyntaxException {
        return dispatcher(true, true).execute(command, "player");
    }

    private List<String> suggest(String command) throws Exception {
        CommandDispatcher<String> d = dispatcher(true, true);
        return d.getCompletionSuggestions(d.parse(command, "player")).get().getList().stream().map(Suggestion::getText).toList();
    }

    private static String script(String prefix, String body) throws Exception {
        String name = prefix + " " + System.nanoTime() + ".js";
        Path p = FakeGame.SCRIPT_DIR.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, MANUAL + body);
        return name;
    }

    // ---- mark ----

    @Test
    void markRecordsAnEventWithTheWholeDescription() throws Exception {
        Sculk sculk = FakeGame.sculk();
        assertEquals(1, run("sculk mark opened the  backpack"));
        long id = sculk.lastEventId();
        assertEquals(List.of("ok marked #" + id + ": opened the  backpack"), replies);
        StoredEvent e = sculk.control().store().get(id);
        assertEquals("mark", e.fields().get("kind"));
        assertEquals("opened the  backpack", e.fields().get("text"));
        assertEquals("command", e.fields().get("by"));
    }

    @Test
    void markNeedsADescription() {
        assertThrows(CommandSyntaxException.class, () -> run("sculk mark"));
    }

    @Test
    void requiresGatesTheCommand() {
        assertThrows(CommandSyntaxException.class, () -> dispatcher(false, true).execute("sculk mark x", "player"));
        assertTrue(replies.isEmpty());
    }

    // ---- scripts ----

    @Test
    void scriptsRunAndStopByNameWithSpaces() throws Exception {
        String name = script("cmd run", "6 * 7");
        assertEquals(1, run("sculk script run " + name));
        assertEquals("ok started " + name + " → 42", replies.get(0));
        assertTrue(FakeGame.sculk().scripts().runningNames().contains(name));

        run("sculk script list");
        assertTrue(replies.contains("ok   " + name + ": running"), replies.toString());

        assertEquals(1, run("sculk script stop " + name));
        assertEquals("ok stopped " + name, replies.get(replies.size() - 1));
        assertFalse(FakeGame.sculk().scripts().runningNames().contains(name));
    }

    @Test
    void scriptRefusalsAreFailures() throws Exception {
        assertEquals(0, run("sculk script run no such script.js"));
        assertTrue(replies.get(0).startsWith("fail "), replies.toString());

        String broken = script("cmd broken", "this is not javascript (");
        assertEquals(0, run("sculk script run " + broken));
        assertTrue(replies.get(1).startsWith("fail " + broken + ": "), replies.toString());

        String idle = script("cmd idle", "1");
        assertEquals(0, run("sculk script stop " + idle));
        assertEquals("fail " + idle + " is not running", replies.get(2));
    }

    @Test
    void scriptNamesComplete() throws Exception {
        String name = script("cmd complete", "1");
        assertTrue(suggest("sculk script run cmd comp").contains(name));
        assertFalse(suggest("sculk script stop cmd comp").contains(name), "only running scripts stop");
        run("sculk script run " + name);
        assertTrue(suggest("sculk script stop CMD comp").contains(name));
        run("sculk script stop " + name);
    }

    @Test
    void reloadAnswersWithWhatStarted() throws Exception {
        assertEquals(1, run("sculk script reload"));
        assertTrue(replies.get(0).startsWith("ok reloaded: "), replies.toString());
    }

    // ---- ui ----

    @Test
    void uiOpensTheSignedInPageWithoutPuttingTheTokenInChat() throws Exception {
        String token = FakeGame.sculk().control().token();
        assertEquals(1, run("sculk ui"));
        assertEquals(List.of(FakeGame.sculk().control().url() + "/ui/#token=" + token), opened);
        assertFalse(String.join("\n", replies).contains(token));
    }

    @Test
    void uiIsOnlyForGameClients() {
        assertThrows(CommandSyntaxException.class, () -> dispatcher(true, false).execute("sculk ui", "console"));
    }
}
