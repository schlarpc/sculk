package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sculk.core.control.ControlServer;
import sculk.core.platform.Side;
import sculk.core.util.Bytes;

/**
 * The control server, over real loopback HTTP. Requests are written by hand
 * so that the tests can send the headers an attacker would (Host, Origin),
 * which Java's HTTP clients refuse to set.
 */
class ControlServerTest {
    private static final Gson GSON = new Gson();

    private record Response(int status, String body) {
        JsonObject json() { return GSON.fromJson(body, JsonObject.class); }
    }

    private static ControlServer server() {
        ControlServer server = FakeGame.sculk().control();
        assertNotNull(server, "control server should be running in tests");
        return server;
    }

    private static String host() {
        return "127.0.0.1:" + server().port();
    }

    private static Response request(String method, String path, String body, String... headers) throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server().port())) {
            socket.setSoTimeout(10_000);
            byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            StringBuilder req = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
            boolean hostGiven = false;
            for (String h : headers) {
                req.append(h).append("\r\n");
                hostGiven |= h.regionMatches(true, 0, "Host:", 0, 5);
            }
            if (!hostGiven) req.append("Host: ").append(host()).append("\r\n");
            req.append("Content-Length: ").append(bytes.length).append("\r\nConnection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
            String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = Integer.parseInt(raw.substring(9, 12));
            int split = raw.indexOf("\r\n\r\n");
            return new Response(status, split < 0 ? "" : raw.substring(split + 4));
        }
    }

    private static String auth() {
        return "Authorization: Bearer " + server().token();
    }

    private static Response get(String path) throws IOException {
        return request("GET", path, null, auth());
    }

    private static Response post(String path, String body) throws IOException {
        return request("POST", path, body, auth());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---- access control ----

    @Test
    void controlFileHoldsPortAndTokenForTheOwnerOnly() throws IOException {
        JsonObject info = GSON.fromJson(Files.readString(FakeGame.CONTROL_FILE), JsonObject.class);
        assertEquals(server().port(), info.get("port").getAsInt());
        assertEquals(server().token(), info.get("token").getAsString());
        assertEquals("http://127.0.0.1:" + server().port(), info.get("url").getAsString());
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(FakeGame.CONTROL_FILE);
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms);
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system.
        }
    }

    @Test
    void refusesMissingOrWrongToken() throws IOException {
        assertEquals(401, request("GET", "/status", null).status());
        assertEquals(401, request("GET", "/status", null, "Authorization: Bearer nope").status());
        assertEquals(401, request("GET", "/status", null, "Authorization: Basic " + server().token()).status());
        assertEquals(401, request("POST", "/eval", "1", "Authorization: Bearer x" + server().token()).status());
    }

    @Test
    void refusesForeignHostHeader() throws IOException {
        // DNS rebinding: a page on evil.example resolves its own name to 127.0.0.1.
        assertEquals(403, request("GET", "/status", null, auth(), "Host: evil.example:" + server().port()).status());
        assertEquals(403, request("GET", "/status", null, auth(), "Host: 127.0.0.1:1").status());
        assertEquals(200, request("GET", "/status", null, auth(), "Host: localhost:" + server().port()).status());
    }

    @Test
    void listensOnBothLoopbackAddresses() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(server().ipv6(), "no IPv6 loopback on this machine");
        InetAddress v6 = InetAddress.getByName("::1");
        try (Socket socket = new Socket(v6, server().port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /status HTTP/1.1\r\nHost: [::1]:" + server().port() + "\r\n" + auth()
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(raw.startsWith("HTTP/1.1 200"), raw);
        }
        assertEquals(403, request("GET", "/status", null, auth(), "Host: [::2]:" + server().port()).status());
        assertEquals(200, request("POST", "/eval", "1", auth(), "Origin: http://[::1]:" + server().port()).status());
    }

    @Test
    void refusesCrossOriginRequests() throws IOException {
        assertEquals(403, request("POST", "/eval", "1", auth(), "Origin: http://evil.example").status());
        assertEquals(403, request("POST", "/eval", "1", auth(), "Origin: null").status());
        assertEquals(403, request("POST", "/eval", "1", auth(), "Origin: http://127.0.0.1:" + server().port() + ".evil.example").status());
        assertEquals(200, request("POST", "/eval", "1", auth(), "Origin: http://" + host()).status());
    }

    @Test
    void stateChangesNeedPost() throws IOException {
        assertEquals(405, get("/eval").status());
        assertEquals(405, get("/reload").status());
        assertEquals(405, post("/status", "").status());
        assertEquals(404, get("/nope").status());
    }

    // ---- endpoints ----

    @Test
    void status() throws IOException {
        JsonObject status = get("/status").json();
        assertEquals("fake 0", status.get("platform").getAsString().substring(0, 6));
        assertEquals(FakeGame.STARTUP_SCRIPT.toString(), status.getAsJsonArray("scripts").get(0).getAsString());
        assertTrue(status.get("lastEventId").getAsLong() > 0);
        assertTrue(status.has("connections"));
    }

    @Test
    void eventsCanBeFilteredAndPaged() throws IOException {
        String marker = "ctl-events-" + System.nanoTime();
        long before = FakeGame.sculk().lastEventId();
        EmbeddedChannel ch = FakeGame.channel(Side.SERVER);
        for (int i = 0; i < 3; i++) ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|" + i)));

        String filter = "kind == packet && channel contains " + marker;
        Response all = get("/events?filter=" + enc(filter));
        assertEquals(200, all.status());
        List<JsonObject> events = lines(all.body());
        assertEquals(3, events.size(), all.body());
        for (JsonObject e : events) {
            assertTrue(e.get("id").getAsLong() > before);
            assertEquals("inbound", e.getAsJsonObject("frame").get("dir").getAsString());
            assertEquals("othermod", e.getAsJsonObject("mod").get("owner").getAsString());
            assertEquals("minecraft", e.getAsJsonObject("packet").get("owner").getAsString());
            assertEquals("server", e.getAsJsonObject("conn").get("side").getAsString());
        }

        List<JsonObject> last = lines(get("/events?limit=1&filter=" + enc(filter)).body());
        assertEquals(List.of(events.get(2).get("id")), last.stream().map(e -> e.get("id")).toList());

        long first = events.get(0).get("id").getAsLong();
        List<JsonObject> next = lines(get("/events?limit=1&after=" + first + "&filter=" + enc(filter)).body());
        assertEquals(events.get(1).get("id"), next.get(0).get("id"));

        assertEquals(400, get("/events?filter=" + enc("kind ==")).status());
        assertEquals(400, get("/events?limit=many").status());
    }

    @Test
    void eventLogLinesCarryTheSameIds() throws Exception {
        String marker = "ctl-ids-" + System.nanoTime();
        EmbeddedChannel ch = FakeGame.channel(Side.SERVER);
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|x")));
        JsonObject served = lines(get("/events?filter=" + enc("channel contains " + marker)).body()).get(0);
        String expected = "{\"id\":" + served.get("id").getAsLong() + ",\"kind\":\"packet\"";
        long deadline = System.currentTimeMillis() + 5000;
        String log = "";
        while (System.currentTimeMillis() < deadline && !(log.contains(marker) && log.contains(expected))) {
            Thread.sleep(20);
            log = Files.readString(FakeGame.EVENT_LOG);
        }
        assertTrue(log.contains(expected), "event log should hold " + expected);
    }

    @Test
    void frameReturnsTheWholeFrame() throws IOException {
        String marker = "ctl-frame-" + System.nanoTime();
        String body = "x".repeat(10_000);
        EmbeddedChannel ch = FakeGame.channel(Side.SERVER);
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|" + body)));
        JsonObject event = lines(get("/events?filter=" + enc("channel contains " + marker)).body()).get(0);
        assertTrue(event.getAsJsonObject("frame").get("hex").getAsString().length() < 2 * 10_000, "the log entry's hex is cut short");

        JsonObject frame = get("/frame?id=" + event.get("id").getAsLong()).json();
        byte[] expected = FakeGame.frame(1, "othermod:" + marker + "|" + body);
        assertEquals(expected.length, frame.get("len").getAsInt());
        assertEquals(Bytes.hex(expected), frame.get("hex").getAsString());

        long start = lines(get("/events?filter=kind==start").body()).get(0).get("id").getAsLong();
        assertEquals(404, get("/frame?id=" + start).status());
        assertEquals(404, get("/frame?id=999999999").status());
        assertEquals(400, get("/frame").status());
    }

    @Test
    void evalRunsInTheLiveScope() throws IOException {
        JsonObject r = post("/eval", "var replValue = 20; replValue + 22").json();
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        assertEquals(42, r.get("result").getAsInt());

        r = post("/eval", "replValue * 2").json();
        assertEquals(40, r.get("result").getAsInt());

        r = post("/eval", "({a: [1, 'two', null], b: {c: true}})").json();
        assertEquals("{\"a\":[1,\"two\",null],\"b\":{\"c\":true}}", r.get("result").toString());

        r = post("/eval", "sculk.connections()").json();
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        assertTrue(r.get("result").isJsonArray());

        r = post("/eval", "(function f() {})").json();
        assertEquals("[function]", r.get("result").getAsString());

        r = post("/eval", "undefined").json();
        assertTrue(r.get("ok").getAsBoolean());
        assertTrue(r.get("result").isJsonNull());

        r = post("/eval", "0/0").json();
        assertEquals("NaN", r.get("result").getAsString());
    }

    @Test
    void evalReportsErrors() throws IOException {
        JsonObject r = post("/eval", "(").json();
        assertFalse(r.get("ok").getAsBoolean());
        assertTrue(r.get("error").getAsString().contains("repl"), r.toString());

        r = post("/eval", "throw new Error('kaput')").json();
        assertFalse(r.get("ok").getAsBoolean());
        assertTrue(r.get("error").getAsString().contains("kaput"), r.toString());

        r = post("/eval", "java.lang.Integer.parseInt('x')").json();
        assertFalse(r.get("ok").getAsBoolean());
    }

    @Test
    void evalIsLogged() throws IOException {
        String marker = "ctl-eval-" + System.nanoTime();
        post("/eval", "'" + marker + "'");
        assertEquals(1, lines(get("/events?filter=" + enc("kind == eval && source contains " + marker)).body()).size());
    }

    @Test
    void reloadDropsScriptStateAndListeners() throws Exception {
        String marker = "ctl-reload-" + System.nanoTime();
        post("/eval", """
                var leftover = 1;
                sculk.on('packet', e => { if (e.channel && e.channel.indexOf('%s') >= 0) sculk.emit({ heard: '%s' }); });
                """.formatted(marker, marker));
        EmbeddedChannel ch = FakeGame.channel(Side.SERVER);
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|a")));
        String heard = "kind == script && data.heard == " + marker;
        assertEquals(1, lines(get("/events?filter=" + enc(heard)).body()).size());
        long runsBefore = post("/eval", "startupRuns").json().get("result").getAsLong();
        assertTrue(runsBefore >= 1);

        JsonObject r = post("/reload", "").json();
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        assertEquals(FakeGame.STARTUP_SCRIPT.toString(), r.getAsJsonArray("loaded").get(0).getAsString());

        assertEquals("undefined", post("/eval", "typeof leftover").json().get("result").getAsString());
        assertEquals(1, post("/eval", "startupRuns").json().get("result").getAsLong(), "startup scripts ran in a fresh scope");
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|b")));
        assertEquals(1, lines(get("/events?filter=" + enc(heard)).body()).size(), "the old listener is gone");
        assertEquals(1, lines(get("/events?filter=kind==script_reload&limit=1").body()).size());
    }

    @Test
    void streamSendsBacklogThenLiveEvents() throws Exception {
        String marker = "ctl-stream-" + System.nanoTime();
        EmbeddedChannel ch = FakeGame.channel(Side.SERVER);
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|old")));
        String filter = enc("channel contains " + marker);
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server().port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /events/stream?after=0&filter=" + filter + " HTTP/1.1\r\nHost: " + host()
                    + "\r\n" + auth() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            InputStream in = socket.getInputStream();
            String text = readUntil(in, "\"data\":\"old\"");
            assertTrue(text.startsWith("HTTP/1.1 200"), text);
            assertTrue(text.contains("text/event-stream"), text);

            ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|new")));
            String more = readUntil(in, "\"data\":\"new\"");
            assertTrue(more.contains("\ndata: {\"id\":"), more);
            assertFalse(more.contains("\"data\":\"old\""), "backlog events are not sent twice");
        }
    }

    // ---- web UI ----

    @Test
    void webUiNeedsNoTokenButKeepsTheHostCheck() throws IOException {
        Response root = request("GET", "/", null);
        assertEquals(302, root.status());

        Response index = request("GET", "/ui/", null);
        assertEquals(200, index.status());
        assertTrue(index.body().contains("sculk test ui"), index.body());
        assertEquals(200, request("GET", "/ui/app.js", null).status());

        assertEquals(403, request("GET", "/ui/", null, "Host: evil.example:" + server().port()).status());
        assertEquals(403, request("GET", "/ui/", null, "Origin: http://evil.example").status());
        assertEquals(401, request("GET", "/status", null, "Origin: http://" + host()).status(), "the API still needs the token");
    }

    @Test
    void webUiServesOnlyItsOwnFiles() throws IOException {
        assertEquals(404, request("GET", "/ui/nope.js", null).status());
        assertEquals(404, request("GET", "/ui/../web/index.html", null).status());
        assertEquals(404, request("GET", "/ui/%2e%2e%2fControlServer.class", null).status());
        assertEquals(404, request("GET", "/ui/.hidden", null).status());
        assertEquals(404, request("GET", "/ui/sub/index.html", null).status());
        assertEquals(401, request("POST", "/ui/index.html", "", "Origin: http://" + host()).status());
    }

    @Test
    void webUiHeaders() throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server().port())) {
            socket.getOutputStream().write(("GET /ui/ HTTP/1.1\r\nHost: " + host() + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertTrue(raw.contains("content-type: text/html"), raw);
            assertTrue(raw.contains("content-security-policy: default-src 'none'; script-src 'self'"), raw);
            assertTrue(raw.contains("x-frame-options: deny"), raw);
        }
    }

    // ---- MCP ----

    private static JsonObject mcp(String method, String params) throws IOException {
        Response r = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}");
        assertEquals(200, r.status(), r.body());
        return r.json();
    }

    private static JsonObject callTool(String name, String arguments) throws IOException {
        JsonObject r = mcp("tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + arguments + "}");
        assertTrue(r.has("result"), r.toString());
        return r.getAsJsonObject("result");
    }

    private static String toolText(JsonObject result) {
        return result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    @Test
    void mcpHandshake() throws IOException {
        JsonObject init = mcp("initialize",
                "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}");
        JsonObject result = init.getAsJsonObject("result");
        assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
        assertEquals("sculk", result.getAsJsonObject("serverInfo").get("name").getAsString());
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));

        String unknownVersion = mcp("initialize", "{\"protocolVersion\":\"1999-01-01\"}")
                .getAsJsonObject("result").get("protocolVersion").getAsString();
        assertEquals("2025-11-25", unknownVersion);

        Response note = post("/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, note.status());
        assertEquals("", note.body());

        assertEquals("{}", mcp("ping", "{}").getAsJsonObject("result").toString());

        List<String> tools = new ArrayList<>();
        mcp("tools/list", "{}").getAsJsonObject("result").getAsJsonArray("tools")
                .forEach(t -> tools.add(t.getAsJsonObject().get("name").getAsString()));
        assertEquals(List.of("sculk_status", "sculk_events", "sculk_wait_events", "sculk_frame", "sculk_eval", "sculk_reload"), tools);
    }

    @Test
    void mcpProtocolErrors() throws IOException {
        assertEquals(-32601, mcp("nope", "{}").getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32700, post("/mcp", "{oops").json().getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32600, post("/mcp", "[]").json().getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32602, mcp("tools/call", "{\"name\":\"sculk_nope\"}").getAsJsonObject("error").get("code").getAsInt());
        assertEquals(405, get("/mcp").status());
    }

    @Test
    void mcpSharesTheAccessChecks() throws IOException {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        assertEquals(401, request("POST", "/mcp", init).status());
        assertEquals(403, request("POST", "/mcp", init, auth(), "Origin: http://evil.example").status());
        assertEquals(403, request("POST", "/mcp", init, auth(), "Host: evil.example:" + server().port()).status());
    }

    @Test
    void mcpTools() throws IOException {
        JsonObject status = GSON.fromJson(toolText(callTool("sculk_status", "{}")), JsonObject.class);
        assertTrue(status.get("lastEventId").getAsLong() > 0);

        String marker = "ctl-mcp-" + System.nanoTime();
        EmbeddedChannel ch = FakeGame.channel(Side.SERVER);
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|x")));
        JsonObject events = callTool("sculk_events", "{\"filter\":\"channel contains '" + marker + "'\"}");
        assertFalse(events.get("isError").getAsBoolean());
        List<JsonObject> found = lines(toolText(events));
        assertEquals(1, found.size());
        long id = found.get(0).get("id").getAsLong();

        String hex = toolText(callTool("sculk_frame", "{\"id\":" + id + "}"));
        assertEquals(Bytes.hex(FakeGame.frame(1, "othermod:" + marker + "|x")), hex);

        JsonObject bad = callTool("sculk_events", "{\"filter\":\"kind ==\"}");
        assertTrue(bad.get("isError").getAsBoolean());
        assertTrue(toolText(bad).contains("expected a value"), toolText(bad));

        JsonObject eval = callTool("sculk_eval", "{\"code\":\"var mcpValue = 6; mcpValue * 7\"}");
        assertFalse(eval.get("isError").getAsBoolean());
        assertEquals("42", toolText(eval));
        JsonObject evalError = callTool("sculk_eval", "{\"code\":\"throw new Error('mcp kaput')\"}");
        assertTrue(evalError.get("isError").getAsBoolean());
        assertTrue(toolText(evalError).contains("mcp kaput"));
        assertTrue(callTool("sculk_eval", "{}").get("isError").getAsBoolean());
    }

    @Test
    void mcpWaitEvents() throws Exception {
        String marker = "ctl-wait-" + System.nanoTime();
        long after = FakeGame.sculk().lastEventId();
        JsonObject none = callTool("sculk_wait_events",
                "{\"after\":" + after + ",\"timeoutMs\":100,\"filter\":\"channel contains '" + marker + "'\"}");
        assertEquals("(no matching events)", toolText(none));

        EmbeddedChannel ch = FakeGame.channel(Side.SERVER);
        Thread later = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                return;
            }
            ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|late")));
        });
        later.start();
        long start = System.nanoTime();
        JsonObject waited = callTool("sculk_wait_events",
                "{\"after\":" + after + ",\"timeoutMs\":10000,\"filter\":\"channel contains '" + marker + "'\"}");
        long millis = (System.nanoTime() - start) / 1_000_000;
        later.join();
        List<JsonObject> found = lines(toolText(waited));
        assertEquals(1, found.size(), toolText(waited));
        assertEquals("late", found.get(0).getAsJsonObject("mod").get("data").getAsString());
        assertTrue(millis < 5000, "returned when the event arrived, not at the timeout: " + millis + "ms");
    }

    // ---- helpers ----

    private static List<JsonObject> lines(String body) {
        List<JsonObject> out = new ArrayList<>();
        for (String line : body.split("\n")) {
            if (!line.isBlank()) out.add(GSON.fromJson(line, JsonObject.class));
        }
        return out;
    }

    private static String readUntil(InputStream in, String needle) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        while (true) {
            int n;
            try {
                n = in.read(chunk);
            } catch (SocketTimeoutException e) {
                throw new AssertionError("timed out waiting for " + needle + " in " + buf.toString(StandardCharsets.UTF_8));
            }
            if (n < 0) throw new AssertionError("stream ended before " + needle + ": " + buf.toString(StandardCharsets.UTF_8));
            buf.write(chunk, 0, n);
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains(needle)) return s;
        }
    }
}
