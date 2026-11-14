package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sculk.core.command.SculkCommand;
import sculk.core.control.StoredEvent;

class SculkCommandTest {
    private final List<String> replies = new ArrayList<>();

    private CommandDispatcher<String> dispatcher(boolean allowed) {
        CommandDispatcher<String> d = new CommandDispatcher<>();
        d.register(SculkCommand.tree(s -> allowed,
                SculkCommand.Reply.<String>of((s, m) -> replies.add("ok " + m), (s, m) -> replies.add("fail " + m))));
        return d;
    }

    @Test
    void markRecordsAnEventWithTheWholeDescription() throws Exception {
        Sculk sculk = FakeGame.sculk();
        assertEquals(1, dispatcher(true).execute("sculk mark opened the  backpack", "player"));
        long id = sculk.lastEventId();
        assertEquals(List.of("ok marked #" + id + ": opened the  backpack"), replies);
        StoredEvent e = sculk.control().store().get(id);
        assertEquals("mark", e.fields().get("kind"));
        assertEquals("opened the  backpack", e.fields().get("text"));
        assertEquals("command", e.fields().get("by"));
    }

    @Test
    void markNeedsADescription() {
        assertThrows(CommandSyntaxException.class, () -> dispatcher(true).execute("sculk mark", "player"));
    }

    @Test
    void requiresGatesTheCommand() {
        assertThrows(CommandSyntaxException.class, () -> dispatcher(false).execute("sculk mark x", "player"));
        assertTrue(replies.isEmpty());
    }
}
