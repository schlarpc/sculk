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
    private final List<sculk.core.platform.ChatLine> lines = new ArrayList<>();
    private final List<String> opened = new ArrayList<>();

    private CommandDispatcher<String> dispatcher(boolean allowed, boolean client) {
        CommandDispatcher<String> d = new CommandDispatcher<>();
        d.register(SculkCommand.tree(s -> allowed, (s, line, error) -> {
            replies.add((error ? "fail " : "ok ") + line.plain());
            lines.add(line);
        }, client ? opened::add : null));
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
        assertEquals(List.of("ok marked #" + id + ": opened the  backpack [show]"), replies);
        var show = lines.get(0).parts().get(lines.get(0).parts().size() - 1);
        assertEquals("/sculk ui " + id, show.command(), "show opens the web UI at the mark");
        dispatcher(true, false).execute("sculk mark on a server", "console");
        assertEquals("ok marked #" + sculk.lastEventId() + ": on a server", replies.get(1), "no browser, no show button");
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

    // ---- mcp ----

    /** Initializes an MCP session without the token, as a client that wants to pair would. */
    private static void pairingClient(String name) throws Exception {
        var control = FakeGame.sculk().control();
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(control.url() + "/mcp"))
                .header("Content-Type", "application/json")
                .timeout(java.time.Duration.ofSeconds(10))
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"" + name + "\"}}}"))
                .build();
        var response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
    }

    private static int waitingNumber(String client) {
        return FakeGame.sculk().control().mcpClients().stream()
                .filter(c -> client.equals(c.get("client")) && Boolean.FALSE.equals(c.get("approved")))
                .map(c -> (Integer) c.get("number")).findFirst().orElseThrow();
    }

    @Test
    void mcpClientsAreListedAcceptedAndRevokedByNumber() throws Exception {
        String client = "cmd-client-" + System.nanoTime();
        pairingClient(client);
        int n = waitingNumber(client);

        run("sculk mcp list");
        assertTrue(replies.contains("ok   #" + n + " " + client + ": waiting"), replies.toString());
        assertTrue(suggest("sculk mcp accept ").contains(String.valueOf(n)));

        assertEquals(1, run("sculk mcp accept " + n));
        assertEquals("ok approved MCP client #" + n + " " + client + " until the game exits", replies.get(replies.size() - 1));
        assertFalse(suggest("sculk mcp accept ").contains(String.valueOf(n)), "accept offers only waiting clients");
        assertTrue(suggest("sculk mcp revoke ").contains(String.valueOf(n)));

        assertEquals(1, run("sculk mcp revoke " + n));
        assertEquals("ok revoked MCP client #" + n + " " + client, replies.get(replies.size() - 1));
        assertEquals(0, run("sculk mcp revoke " + n));
        assertEquals("fail no MCP client #" + n, replies.get(replies.size() - 1));
    }

    @Test
    void mcpAcceptWithoutANumberTakesTheOnlyWaitingClient() throws Exception {
        for (var c : FakeGame.sculk().control().mcpClients()) {
            if (Boolean.FALSE.equals(c.get("approved"))) FakeGame.sculk().control().mcpRevoke((Integer) c.get("number"));
        }
        assertEquals(0, run("sculk mcp accept"));
        assertEquals("fail no MCP client is waiting", replies.get(0));
        String client = "cmd-only-" + System.nanoTime();
        pairingClient(client);
        int n = waitingNumber(client);
        assertEquals(1, run("sculk mcp accept"));
        FakeGame.sculk().control().mcpRevoke(n);
    }

    @Test
    void aWaitingMcpClientIsAnnouncedInChat() throws Exception {
        String client = "cmd-chat-" + System.nanoTime();
        pairingClient(client);
        int n = waitingNumber(client);
        var line = FakeGame.FakePlatform.CHAT_LINES.stream()
                .filter(l -> l.plain().startsWith("MCP client #" + n + " (" + client + ") wants access: /sculk mcp accept " + n))
                .findFirst().orElseThrow(() -> new AssertionError(FakeGame.FakePlatform.CHAT.toString()));
        var button = line.parts().stream().filter(sculk.core.platform.ChatLine.Part::button).findFirst().orElseThrow();
        assertEquals("accept", button.text());
        assertEquals("/sculk mcp accept " + n, button.command());
        // Clicking it runs that command, as the player would.
        assertEquals(1, run(button.command().substring(1)));
        assertTrue(FakeGame.sculk().control().mcpClients().stream()
                .anyMatch(c -> c.get("number").equals(n) && Boolean.TRUE.equals(c.get("approved"))));
        FakeGame.sculk().control().mcpRevoke(n);
    }

    @Test
    void scriptsChatWithThePlayerOnly() {
        String text = "chat-" + System.nanoTime();
        FakeGame.sculk().scripts().eval("sculk.chat('" + text + "')");
        assertTrue(FakeGame.FakePlatform.CHAT.contains(text));
        Sculk sculk = FakeGame.sculk();
        var e = sculk.control().store().get(sculk.lastEventId()).fields();
        assertEquals("chat", e.get("kind"));
        assertEquals(text, e.get("text"));
        assertEquals("console", e.get("script"));
    }

    @Test
    void mcpTokenIsPrinted() throws Exception {
        assertEquals(1, run("sculk mcp token"));
        assertTrue(replies.get(0).contains(FakeGame.sculk().control().token()), replies.toString());
    }

    // ---- ui ----

    @Test
    void uiOpensTheSignedInPageWithoutPuttingTheTokenInChat() throws Exception {
        String token = FakeGame.sculk().control().token();
        assertEquals(1, run("sculk ui"));
        assertEquals(List.of(FakeGame.sculk().control().url() + "/#token=" + token), opened);
        assertFalse(String.join("\n", replies).contains(token));
    }

    @Test
    void uiOpensAtAnEvent() throws Exception {
        assertEquals(1, run("sculk ui 42"));
        assertEquals(List.of(FakeGame.sculk().control().uiUrl() + "&event=42"), opened);
    }

    @Test
    void uiOnAServerSaysHowToReachIt() throws Exception {
        var control = FakeGame.sculk().control();
        assertEquals(1, dispatcher(true, false).execute("sculk ui 42", "console"));
        assertTrue(opened.isEmpty());
        String text = String.join("\n", replies);
        assertTrue(text.contains(control.url() + "/#event=42"), text);
        assertTrue(text.contains("ssh -L " + control.port() + ":127.0.0.1:" + control.port()), text);
        assertTrue(text.contains("/sculk mcp token"), text);
        assertFalse(text.contains(control.token()), text);
    }
}
