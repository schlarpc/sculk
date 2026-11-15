package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Map;
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

    private record Response(int status, String body, String head) {
        Response(int status, String body) { this(status, body, ""); }
        JsonObject json() { return GSON.fromJson(body, JsonObject.class); }
        /** A response header, or null. */
        String header(String name) {
            for (String line : head.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) return line.substring(colon + 1).trim();
            }
            return null;
        }
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
            return new Response(status, split < 0 ? "" : raw.substring(split + 4), split < 0 ? raw : raw.substring(0, split));
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
        assertEquals(401, request("GET", "/api/status", null).status());
        assertEquals(401, request("GET", "/api/status", null, "Authorization: Bearer nope").status());
        assertEquals(401, request("GET", "/api/status", null, "Authorization: Basic " + server().token()).status());
        assertEquals(401, request("POST", "/api/eval", "1", "Authorization: Bearer x" + server().token()).status());
    }

    @Test
    void refusesForeignHostHeader() throws IOException {
        // DNS rebinding: a page on evil.example resolves its own name to 127.0.0.1.
        assertEquals(403, request("GET", "/api/status", null, auth(), "Host: evil.example:" + server().port()).status());
        assertEquals(403, request("GET", "/api/status", null, auth(), "Host: 127.0.0.1:1").status());
        assertEquals(200, request("GET", "/api/status", null, auth(), "Host: localhost:" + server().port()).status());
    }

    @Test
    void listensOnBothLoopbackAddresses() throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(server().ipv6(), "no IPv6 loopback on this machine");
        InetAddress v6 = InetAddress.getByName("::1");
        try (Socket socket = new Socket(v6, server().port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /api/status HTTP/1.1\r\nHost: [::1]:" + server().port() + "\r\n" + auth()
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            String raw = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(raw.startsWith("HTTP/1.1 200"), raw);
        }
        assertEquals(403, request("GET", "/api/status", null, auth(), "Host: [::2]:" + server().port()).status());
        assertEquals(200, request("POST", "/api/eval", "1", auth(), "Origin: http://[::1]:" + server().port()).status());
    }

    /** Every API endpoint, so a new one that skips the checks fails here. */
    private static final List<String> ENDPOINTS = List.of(
            "GET /api/status", "GET /api/events", "GET /api/events/stats", "GET /api/events/page", "GET /api/events/position?id=1", "GET /api/frame?id=1", "POST /api/eval", "POST /api/reload", "POST /mcp",
            "GET /api/scripts", "GET /api/scripts/source?name=x.js", "POST /api/scripts/save?name=x.js", "POST /api/scripts/check",
            "POST /api/scripts/run?name=x.js", "POST /api/scripts/stop?name=x.js", "POST /api/scripts/delete?name=x.js",
            "POST /api/scripts/rename?name=x.js&to=y.js", "POST /api/scripts/enable?name=x.js&enabled=false");

    @Test
    void everyEndpointNeedsTokenHostAndOrigin() throws IOException {
        String port = String.valueOf(server().port());
        for (String endpoint : ENDPOINTS) {
            String[] mp = endpoint.split(" ", 2);
            String body = mp[0].equals("POST") ? "1" : null;
            assertEquals(401, request(mp[0], mp[1], body).status(), endpoint + " without a token");
            assertEquals(401, request(mp[0], mp[1], body, "Authorization: Bearer wrong").status(), endpoint + " with a wrong token");
            assertEquals(403, request(mp[0], mp[1], body, auth(), "Host: evil.example:" + port).status(), endpoint + " rebound");
            assertEquals(403, request(mp[0], mp[1], body, auth(), "Origin: http://evil.example").status(), endpoint + " cross-site");
            assertEquals(403, request(mp[0], mp[1], body, auth(), "Origin: null").status(), endpoint + " from an opaque origin");
            assertEquals(403, request(mp[0], mp[1], body, auth(), "Origin: http://localhost:1").status(), endpoint + " from another local port");
        }
        assertFalse(Files.exists(FakeGame.SCRIPT_DIR.resolve("x.js")), "nothing refused was written");
    }

    @Test
    void refusesCrossOriginRequests() throws IOException {
        assertEquals(403, request("POST", "/api/eval", "1", auth(), "Origin: http://evil.example").status());
        assertEquals(403, request("POST", "/api/eval", "1", auth(), "Origin: null").status());
        assertEquals(403, request("POST", "/api/eval", "1", auth(), "Origin: http://127.0.0.1:" + server().port() + ".evil.example").status());
        assertEquals(200, request("POST", "/api/eval", "1", auth(), "Origin: http://" + host()).status());
    }

    @Test
    void stateChangesNeedPost() throws IOException {
        assertEquals(405, get("/api/eval").status());
        assertEquals(405, get("/api/reload").status());
        assertEquals(405, post("/api/status", "").status());
        assertEquals(404, get("/nope").status());
    }

    // ---- endpoints ----

    @Test
    void status() throws IOException {
        JsonObject status = get("/api/status").json();
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
        Response all = get("/api/events?filter=" + enc(filter));
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

        List<JsonObject> last = lines(get("/api/events?limit=1&filter=" + enc(filter)).body());
        assertEquals(List.of(events.get(2).get("id")), last.stream().map(e -> e.get("id")).toList());

        long first = events.get(0).get("id").getAsLong();
        List<JsonObject> next = lines(get("/api/events?limit=1&after=" + first + "&filter=" + enc(filter)).body());
        assertEquals(events.get(1).get("id"), next.get(0).get("id"));

        assertEquals(400, get("/api/events?filter=" + enc("kind ==")).status());
        assertEquals(400, get("/api/events?limit=many").status());
    }

    @Test
    void eventLogLinesCarryTheSameIds() throws Exception {
        String marker = "ctl-ids-" + System.nanoTime();
        EmbeddedChannel ch = FakeGame.channel(Side.SERVER);
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|x")));
        JsonObject served = lines(get("/api/events?filter=" + enc("channel contains " + marker)).body()).get(0);
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
        JsonObject event = lines(get("/api/events?filter=" + enc("channel contains " + marker)).body()).get(0);
        assertTrue(event.getAsJsonObject("frame").get("hex").getAsString().length() < 2 * 10_000, "the log entry's hex is cut short");

        JsonObject frame = get("/api/frame?id=" + event.get("id").getAsLong()).json();
        byte[] expected = FakeGame.frame(1, "othermod:" + marker + "|" + body);
        assertEquals(expected.length, frame.get("len").getAsInt());
        assertEquals(Bytes.hex(expected), frame.get("hex").getAsString());

        long start = lines(get("/api/events?filter=kind==start").body()).get(0).get("id").getAsLong();
        assertEquals(404, get("/api/frame?id=" + start).status());
        assertEquals(404, get("/api/frame?id=999999999").status());
        assertEquals(400, get("/api/frame").status());
    }

    @Test
    void evalRunsInTheLiveScope() throws IOException {
        JsonObject r = post("/api/eval", "var replValue = 20; replValue + 22").json();
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        assertEquals(42, r.get("result").getAsInt());

        r = post("/api/eval", "replValue * 2").json();
        assertEquals(40, r.get("result").getAsInt());

        r = post("/api/eval", "({a: [1, 'two', null], b: {c: true}})").json();
        assertEquals("{\"a\":[1,\"two\",null],\"b\":{\"c\":true}}", r.get("result").toString());

        r = post("/api/eval", "sculk.connections()").json();
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        assertTrue(r.get("result").isJsonArray());

        r = post("/api/eval", "(function f() {})").json();
        assertEquals("[function]", r.get("result").getAsString());

        r = post("/api/eval", "undefined").json();
        assertTrue(r.get("ok").getAsBoolean());
        assertTrue(r.get("result").isJsonNull());

        r = post("/api/eval", "0/0").json();
        assertEquals("NaN", r.get("result").getAsString());
    }

    @Test
    void evalReportsErrors() throws IOException {
        JsonObject r = post("/api/eval", "(").json();
        assertFalse(r.get("ok").getAsBoolean());
        assertTrue(r.get("error").getAsString().contains("console"), r.toString());

        r = post("/api/eval", "throw new Error('kaput')").json();
        assertFalse(r.get("ok").getAsBoolean());
        assertTrue(r.get("error").getAsString().contains("kaput"), r.toString());

        r = post("/api/eval", "java.lang.Integer.parseInt('x')").json();
        assertFalse(r.get("ok").getAsBoolean());
    }

    @Test
    void evalIsLogged() throws IOException {
        String marker = "ctl-eval-" + System.nanoTime();
        post("/api/eval", "'" + marker + "'");
        assertEquals(1, lines(get("/api/events?filter=" + enc("kind == eval && source contains " + marker)).body()).size());
    }

    @Test
    void reloadDropsScriptStateAndListeners() throws Exception {
        String marker = "ctl-reload-" + System.nanoTime();
        post("/api/eval", """
                var leftover = 1;
                sculk.on('packet', e => { if (e.channel && e.channel.indexOf('%s') >= 0) sculk.emit({ heard: '%s' }); });
                """.formatted(marker, marker));
        EmbeddedChannel ch = FakeGame.channel(Side.SERVER);
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|a")));
        String heard = "kind == script && data.heard == " + marker;
        assertEquals(1, lines(get("/api/events?filter=" + enc(heard)).body()).size());
        String runs = "sculk.scope('" + FakeGame.STARTUP_SCRIPT.toString().replace("\\", "\\\\") + "').startupRuns";
        long runsBefore = post("/api/eval", runs).json().get("result").getAsLong();
        assertTrue(runsBefore >= 1);

        JsonObject r = post("/api/reload", "").json();
        assertTrue(r.get("ok").getAsBoolean(), r.toString());
        assertEquals(FakeGame.STARTUP_SCRIPT.toString(), r.getAsJsonArray("loaded").get(0).getAsString());

        assertEquals("undefined", post("/api/eval", "typeof leftover").json().get("result").getAsString());
        assertEquals(1, post("/api/eval", runs).json().get("result").getAsLong(), "startup scripts ran in a fresh scope");
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|b")));
        assertEquals(1, lines(get("/api/events?filter=" + enc(heard)).body()).size(), "the old listener is gone");
        assertEquals(1, lines(get("/api/events?filter=kind==script_reload&limit=1").body()).size());
    }

    @Test
    void streamSendsBacklogThenLiveEvents() throws Exception {
        String marker = "ctl-stream-" + System.nanoTime();
        EmbeddedChannel ch = FakeGame.channel(Side.SERVER);
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|old")));
        String filter = enc("channel contains " + marker);
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server().port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /api/events/stream?after=0&filter=" + filter + " HTTP/1.1\r\nHost: " + host()
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

    @Test
    void aTakenPortFallsThroughToTheNext() throws IOException {
        try (var taken = new java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int busy = taken.getLocalPort();
            var file = Files.createTempFile("sculk-control", ".json");
            ControlServer second = new ControlServer(Sculk.get(), server().store(), new int[] {busy, 0}, file, null);
            try {
                assertTrue(second.port() != busy && second.port() > 0, "bound " + second.port());
                assertTrue(Files.readString(file).contains("\"port\":" + second.port()), Files.readString(file));
            } finally {
                second.stop();
                Files.deleteIfExists(file);
            }
            assertThrows(java.net.BindException.class,
                    () -> new ControlServer(Sculk.get(), server().store(), busy, file, null), "an exact port that is taken fails");
        }
    }

    // ---- web UI ----

    @Test
    void webUiNeedsNoTokenButKeepsTheHostCheck() throws IOException {
        Response index = request("GET", "/", null);
        assertEquals(200, index.status());
        assertTrue(index.body().contains("sculk test ui"), index.body());
        assertEquals(200, request("GET", "/app.js", null).status());
        for (String page : List.of("/events", "/scripts", "/scripts/my%20script.js", "/scripts/a%2Fb.js")) {
            Response r = request("GET", page, null);
            assertEquals(200, r.status(), page);
            assertTrue(r.body().contains("sculk test ui"), page + ": " + r.body());
        }

        assertEquals(403, request("GET", "/", null, "Host: evil.example:" + server().port()).status());
        assertEquals(403, request("GET", "/", null, "Origin: http://evil.example").status());
        assertEquals(401, request("GET", "/api/status", null, "Origin: http://" + host()).status(), "the API still needs the token");
    }

    @Test
    void webUiServesOnlyItsOwnFiles() throws IOException {
        assertEquals(404, request("GET", "/nope.js", null).status());
        assertEquals(404, request("GET", "/../web/index.html", null).status());
        assertEquals(404, request("GET", "/%2e%2e%2fControlServer.class", null).status());
        assertEquals(404, request("GET", "/.hidden", null).status());
        assertEquals(404, request("GET", "/sub/index.html", null).status());
        assertEquals(401, request("POST", "/index.html", "", "Origin: http://" + host()).status());
    }

    @Test
    void apiPathsAreNotTheUi() throws IOException {
        // Without /api, an endpoint's old path is the UI's (or nothing), never the endpoint.
        assertEquals(404, request("POST", "/eval", "1", auth()).status());
        assertEquals(404, request("GET", "/status", null, auth()).status());
        assertEquals(404, request("GET", "/api/nope", null, auth()).status());
        assertEquals(404, request("POST", "/api/mcp", "{}", auth()).status());
        assertEquals(401, request("GET", "/api/nope", null).status(), "unknown API paths still need the token");
    }

    @Test
    void webUiHeaders() throws IOException {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server().port())) {
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: " + host() + "\r\nConnection: close\r\n\r\n")
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
        assertEquals(List.of("sculk_status", "sculk_events", "sculk_stats", "sculk_wait_events", "sculk_frame", "sculk_eval", "sculk_reload",
                "sculk_scripts", "sculk_script_read", "sculk_script_write", "sculk_script_edit", "sculk_script_control"), tools);
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
    void mcpProtocolVersionsAndBatches() throws IOException {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}";
        String note = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        // The header, when sent, must name a version we speak.
        Response bad = request("POST", "/mcp", ping, auth(), "MCP-Protocol-Version: 1999-01-01");
        assertEquals(400, bad.status());
        assertTrue(bad.body().contains("unsupported MCP-Protocol-Version"), bad.body());
        assertEquals(200, request("POST", "/mcp", ping, auth(), "MCP-Protocol-Version: 2025-06-18").status());

        // Batches: part of 2025-03-26 (what a client without the header speaks), gone from 2025-06-18.
        String batch = "[" + ping + "," + note + ",{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"nope\"}]";
        Response r = request("POST", "/mcp", batch, auth());
        assertEquals(200, r.status(), r.body());
        var responses = com.google.gson.JsonParser.parseString(r.body()).getAsJsonArray();
        assertEquals(2, responses.size(), "the notification gets no response");
        assertEquals(7, responses.get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals(-32601, responses.get(1).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
        assertEquals(202, request("POST", "/mcp", "[" + note + "]", auth()).status());
        Response refused = request("POST", "/mcp", batch, auth(), "MCP-Protocol-Version: 2025-06-18");
        assertEquals(-32600, refused.json().getAsJsonObject("error").get("code").getAsInt());
    }

    private static String initSession() throws IOException {
        Response init = request("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}", auth());
        String session = init.header("Mcp-Session-Id");
        assertNotNull(session, init.head());
        return session;
    }

    @Test
    void mcpSessions() throws IOException {
        String session = initSession();
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";
        assertEquals(200, request("POST", "/mcp", ping, auth(), "Mcp-Session-Id: " + session).status());
        assertEquals(404, request("POST", "/mcp", ping, auth(), "Mcp-Session-Id: nope").status());
        assertEquals(204, request("DELETE", "/mcp", null, auth(), "Mcp-Session-Id: " + session).status());
        assertEquals(404, request("POST", "/mcp", ping, auth(), "Mcp-Session-Id: " + session).status(), "ended");
        assertEquals(404, request("DELETE", "/mcp", null, auth(), "Mcp-Session-Id: " + session).status());
        assertEquals(400, request("DELETE", "/mcp", null, auth()).status());
        assertEquals(403, request("DELETE", "/mcp", null, auth(), "Origin: http://evil.example").status());
        assertEquals(200, request("POST", "/mcp", ping, auth()).status(), "sessions stay optional");
    }

    /** A wait that matches nothing, streamed with a progress token, then cancelled. */
    @Test
    void mcpLongWaitsStreamProgressAndCanBeCancelled() throws Exception {
        String session = initSession();
        String call = "{\"jsonrpc\":\"2.0\",\"id\":\"w1\",\"method\":\"tools/call\",\"params\":{\"name\":\"sculk_wait_events\","
                + "\"arguments\":{\"after\":0,\"filter\":\"kind == never_happens\",\"timeoutMs\":60000},\"_meta\":{\"progressToken\":\"tok\"}}}";
        byte[] bytes = call.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server().port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: " + host() + "\r\n" + auth() + "\r\nMcp-Session-Id: " + session
                    + "\r\nAccept: application/json, text/event-stream\r\nContent-Length: " + bytes.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();
            InputStream in = socket.getInputStream();
            String head = readUntil(in, "notifications/progress");
            assertTrue(head.contains("text/event-stream"), head);
            assertTrue(head.contains("\"progressToken\":\"tok\""), head);

            // A cancellation from another session does nothing; from this one, it ends the stream with no response.
            String cancel = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":\"w1\"}}";
            assertEquals(202, request("POST", "/mcp", cancel, auth()).status());
            long t0 = System.nanoTime();
            assertEquals(202, request("POST", "/mcp", cancel, auth(), "Mcp-Session-Id: " + session).status());
            String rest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue((System.nanoTime() - t0) / 1_000_000 < 3_000, "the wait stopped promptly");
            assertFalse(rest.contains("\"result\""), rest);
        }
    }

    @Test
    void mcpWaitsWithoutStreamingCanBeCancelledToo() throws Exception {
        String call = "{\"jsonrpc\":\"2.0\",\"id\":991,\"method\":\"tools/call\",\"params\":{\"name\":\"sculk_wait_events\","
                + "\"arguments\":{\"after\":0,\"filter\":\"kind == never_happens\",\"timeoutMs\":60000}}}";
        var pending = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return request("POST", "/mcp", call, auth());
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        Thread.sleep(500);
        assertEquals(202, request("POST", "/mcp",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":991}}", auth()).status());
        Response r = pending.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(-32800, r.json().getAsJsonObject("error").get("code").getAsInt(), r.body());
    }

    @Test
    void mcpShortWaitsStillAnswerAsJson() throws IOException {
        long last = FakeGame.sculk().lastEventId();
        Response r = request("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":"
                + "\"sculk_wait_events\",\"arguments\":{\"after\":" + (last - 1) + "}}}", auth(), "Accept: application/json, text/event-stream");
        assertTrue(r.header("Content-Type").startsWith("application/json"), r.head());
        assertTrue(r.json().has("result"), r.body());
    }

    @Test
    void mcpToolsCarryAnnotations() throws IOException {
        var tools = mcp("tools/list", "{}").getAsJsonObject("result").getAsJsonArray("tools");
        for (var t : tools) {
            JsonObject tool = t.getAsJsonObject();
            String name = tool.get("name").getAsString();
            assertTrue(tool.has("title"), name);
            JsonObject a = tool.getAsJsonObject("annotations");
            boolean readOnly = a.get("readOnlyHint").getAsBoolean();
            boolean shouldBe = Set.of("sculk_status", "sculk_events", "sculk_stats", "sculk_wait_events", "sculk_frame", "sculk_scripts",
                    "sculk_script_read").contains(name);
            assertEquals(shouldBe, readOnly, name);
            if (!readOnly) assertTrue(a.get("destructiveHint").getAsBoolean(), name);
        }
    }

    @Test
    void mcpSharesTheAccessChecks() throws IOException {
        String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}";
        assertEquals(401, request("POST", "/mcp", ping).status(), "no token and no session");
        assertEquals(401, request("POST", "/mcp", ping, "Authorization: Bearer wrong").status());
        for (String auth : new String[] {auth(), "X-None: 1"}) {
            assertEquals(403, request("POST", "/mcp", init, auth, "Origin: http://evil.example").status());
            assertEquals(403, request("POST", "/mcp", init, auth, "Host: evil.example:" + server().port()).status());
        }
    }

    // ---- MCP 2026-07-28: stateless, with the token ----

    private static final String MODERN = "2026-07-28";
    private static final String MODERN_META = "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"" + MODERN + "\","
            + "\"io.modelcontextprotocol/clientInfo\":{\"name\":\"modern-test\",\"version\":\"1\"},"
            + "\"io.modelcontextprotocol/clientCapabilities\":{}}";

    private static String modernBody(String method, String paramsWithoutMeta) {
        String params = paramsWithoutMeta.isEmpty() ? "{" + MODERN_META + "}" : "{" + paramsWithoutMeta + "," + MODERN_META + "}";
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}";
    }

    /** A modern request with every header it needs, plus {@code extra}. */
    private static Response modern(String method, String params, String name, String... extra) throws IOException {
        List<String> headers = new ArrayList<>(List.of(auth(), "MCP-Protocol-Version: " + MODERN, "Mcp-Method: " + method));
        if (name != null) headers.add("Mcp-Name: " + name);
        headers.addAll(List.of(extra));
        return request("POST", "/mcp", modernBody(method, params), headers.toArray(String[]::new));
    }

    private static void assertModernError(Response r, int status, int code) {
        assertEquals(status, r.status(), r.body());
        assertEquals(code, r.json().getAsJsonObject("error").get("code").getAsInt(), r.body());
    }

    @Test
    void mcpModernDiscoverAndTools() throws IOException {
        Response discover = modern("server/discover", "", null);
        assertEquals(200, discover.status(), discover.body());
        assertNull(discover.header("Mcp-Session-Id"), "no sessions in this era");
        JsonObject d = discover.json().getAsJsonObject("result");
        assertEquals("complete", d.get("resultType").getAsString());
        assertEquals(MODERN, d.getAsJsonArray("supportedVersions").get(0).getAsString());
        assertTrue(d.getAsJsonArray("supportedVersions").toString().contains("2025-11-25"), "legacy versions too");
        assertTrue(d.getAsJsonObject("capabilities").has("tools"));
        assertEquals("sculk", d.getAsJsonObject("_meta").getAsJsonObject("io.modelcontextprotocol/serverInfo").get("name").getAsString());

        JsonObject list = modern("tools/list", "", null).json().getAsJsonObject("result");
        assertEquals("complete", list.get("resultType").getAsString());
        assertTrue(list.get("ttlMs").getAsLong() > 0);
        assertEquals("private", list.get("cacheScope").getAsString());
        assertTrue(list.getAsJsonArray("tools").size() > 5);

        Response call = modern("tools/call", "\"name\":\"sculk_eval\",\"arguments\":{\"code\":\"6 * 7\"}", "sculk_eval",
                "Mcp-Session-Id: ignored-in-this-era");
        JsonObject result = call.json().getAsJsonObject("result");
        assertEquals("complete", result.get("resultType").getAsString());
        assertFalse(result.get("isError").getAsBoolean());
        assertEquals("42", toolText(result));
        assertNull(call.header("Mcp-Session-Id"));
        Response encoded = modern("tools/call", "\"name\":\"sculk_status\",\"arguments\":{}",
                "=?base64?" + java.util.Base64.getEncoder().encodeToString("sculk_status".getBytes(StandardCharsets.UTF_8)) + "?=");
        assertEquals(200, encoded.status(), "Mcp-Name may be base64-encoded: " + encoded.body());
    }

    @Test
    void mcpModernRejectsWhatTheSpecRejects() throws IOException {
        String call = "\"name\":\"sculk_status\",\"arguments\":{}";
        // Headers that disagree with the body, or are missing.
        assertModernError(modern("tools/call", call, "sculk_eval"), 400, -32020);
        assertModernError(modern("tools/call", call, null), 400, -32020);
        assertModernError(request("POST", "/mcp", modernBody("tools/list", ""), auth(), "MCP-Protocol-Version: " + MODERN), 400, -32020);
        assertModernError(request("POST", "/mcp", modernBody("tools/list", ""), auth(), "MCP-Protocol-Version: " + MODERN,
                "Mcp-Method: tools/call"), 400, -32020);
        assertModernError(request("POST", "/mcp", modernBody("tools/list", ""), auth(), "Mcp-Method: tools/list"), 400, -32020);
        // A version this server does not speak, named the modern way.
        String future = modernBody("tools/list", "").replace(MODERN, "2099-01-01");
        Response unsupported = request("POST", "/mcp", future, auth(), "MCP-Protocol-Version: 2099-01-01", "Mcp-Method: tools/list");
        assertModernError(unsupported, 400, -32022);
        JsonObject data = unsupported.json().getAsJsonObject("error").getAsJsonObject("data");
        assertEquals("2099-01-01", data.get("requested").getAsString());
        assertEquals(MODERN, data.getAsJsonArray("supported").get(0).getAsString());
        // Required _meta missing.
        String noCaps = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{\"_meta\":{"
                + "\"io.modelcontextprotocol/protocolVersion\":\"" + MODERN + "\"}}}";
        assertModernError(request("POST", "/mcp", noCaps, auth(), "MCP-Protocol-Version: " + MODERN, "Mcp-Method: tools/list"), 400, -32602);
        // Methods this era dropped or sculk does not serve.
        assertModernError(modern("ping", "", null), 404, -32601);
        assertModernError(modern("initialize", "", null), 404, -32601);
        // One message per POST.
        assertEquals(400, request("POST", "/mcp", "[" + modernBody("tools/list", "") + "]", auth(),
                "MCP-Protocol-Version: " + MODERN, "Mcp-Method: tools/list").status());
    }

    @Test
    void mcpModernWithoutTheTokenSendsTheClientBackToPairing() throws IOException {
        Response r = request("POST", "/mcp", modernBody("server/discover", ""), "MCP-Protocol-Version: " + MODERN,
                "Mcp-Method: server/discover");
        assertEquals(400, r.status(), r.body());
        int code = r.json().getAsJsonObject("error").get("code").getAsInt();
        assertFalse(code >= -32099 && code <= -32020, "no modern error code, so a dual-era client falls back: " + code);
        assertTrue(r.json().getAsJsonObject("error").get("message").getAsString().contains("initialize"));
        // ...and the fallback works: an initialize without the token waits for the player.
        String[] p = pair("fallback-" + System.nanoTime());
        server().mcpRevoke(Integer.parseInt(p[1]));
    }

    // ---- MCP pairing: clients without the token wait for the player ----

    static {
        // Tool calls from waiting clients are held for the player; most tests want the quick refusal.
        ControlServer.mcpApprovalWait(300);
    }

    private static final String TOOL_CALL = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"sculk_status\",\"arguments\":{}}}";

    /** Initializes without the token; returns {session id, pairing number}. */
    private static String[] pair(String client) throws IOException {
        Response init = request("POST", "/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"" + client + "\",\"version\":\"9\"}}}");
        assertEquals(200, init.status(), init.body());
        String instructions = init.json().getAsJsonObject("result").get("instructions").getAsString();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/sculk mcp accept (\\d+)").matcher(instructions);
        assertTrue(m.find(), instructions);
        return new String[] {init.header("Mcp-Session-Id"), m.group(1)};
    }

    private static Response inSession(String session, String body) throws IOException {
        return request("POST", "/mcp", body, "Mcp-Session-Id: " + session);
    }

    @Test
    void mcpPairingWaitsForThePlayer() throws IOException {
        String[] p = pair("pair-test");
        String session = p[0];
        int number = Integer.parseInt(p[1]);

        assertEquals(200, inSession(session, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").status(), "tools list while waiting");
        JsonObject waiting = inSession(session, TOOL_CALL).json().getAsJsonObject("result");
        assertTrue(waiting.get("isError").getAsBoolean());
        assertTrue(toolText(waiting).contains("/sculk mcp accept " + number), toolText(waiting));
        JsonObject batch = inSession(session, "[" + TOOL_CALL + "]").json();
        assertEquals(-32600, batch.getAsJsonObject("error").get("code").getAsInt(), "no tool calls smuggled in a batch");

        assertTrue(server().mcpClients().stream().anyMatch(c -> c.get("number").equals(number)
                && "pair-test 9".equals(c.get("client")) && Boolean.FALSE.equals(c.get("approved"))));
        assertEquals(1, lines(get("/api/events?filter=" + enc("kind == mcp_client && action == requested && client.number == " + number)).body()).size());

        server().mcpAccept(number);
        JsonObject approved = inSession(session, TOOL_CALL).json().getAsJsonObject("result");
        assertFalse(approved.get("isError").getAsBoolean(), approved.toString());
        assertTrue(toolText(approved).contains("\"platform\""));

        server().mcpRevoke(number);
        assertEquals(404, inSession(session, TOOL_CALL).status(), "revoked: the client must pair again");
        assertThrows(IllegalArgumentException.class, () -> server().mcpRevoke(number));
    }

    @Test
    void aWaitingClientsToolCallRunsOnceThePlayerApproves() throws Exception {
        String[] p = pair("held-" + System.nanoTime());
        long saved = ControlServer.mcpApprovalWait(20_000);
        try {
            var held = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return inSession(p[0], TOOL_CALL);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            Thread.sleep(300);
            assertFalse(held.isDone(), "the call waits for the player");
            server().mcpAccept(Integer.parseInt(p[1]));
            JsonObject result = held.get(5, java.util.concurrent.TimeUnit.SECONDS).json().getAsJsonObject("result");
            assertFalse(result.get("isError").getAsBoolean(), result.toString());
            assertTrue(toolText(result).contains("\"platform\""));
        } finally {
            ControlServer.mcpApprovalWait(saved);
            server().mcpRevoke(Integer.parseInt(p[1]));
        }
    }

    @Test
    void aHeldCallEndsWhenTheClientIsRevoked() throws Exception {
        String[] p = pair("held-revoked-" + System.nanoTime());
        long saved = ControlServer.mcpApprovalWait(20_000);
        try {
            var held = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return inSession(p[0], TOOL_CALL);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
            Thread.sleep(300);
            server().mcpRevoke(Integer.parseInt(p[1]));
            JsonObject result = held.get(5, java.util.concurrent.TimeUnit.SECONDS).json().getAsJsonObject("result");
            assertTrue(result.get("isError").getAsBoolean());
            assertTrue(toolText(result).contains("turned away"), toolText(result));
        } finally {
            ControlServer.mcpApprovalWait(saved);
        }
    }

    @Test
    void mcpAcceptWithoutANumberNeedsExactlyOneWaiting() throws IOException {
        for (Map<String, Object> c : server().mcpClients()) {
            if (Boolean.FALSE.equals(c.get("approved"))) server().mcpRevoke((Integer) c.get("number"));
        }
        assertThrows(IllegalArgumentException.class, () -> server().mcpAccept(null), "none waiting");
        String[] a = pair("only");
        String[] b = pair("other");
        assertThrows(IllegalArgumentException.class, () -> server().mcpAccept(null), "two waiting");
        server().mcpRevoke(Integer.parseInt(b[1]));
        assertEquals(Integer.parseInt(a[1]), server().mcpAccept(null).get("number"));
        server().mcpRevoke(Integer.parseInt(a[1]));
    }

    @Test
    void aClientsReconnectBurstSharesOneNumber() throws IOException {
        String name = "burst-" + System.nanoTime();
        String[] a = pair(name);
        String[] b = pair(name);
        assertEquals(a[1], b[1], "a second session from the same client waits under the same number");
        assertEquals(2, server().mcpClients().stream().filter(c -> c.get("number").equals(Integer.parseInt(a[1])))
                .findFirst().orElseThrow().get("sessions"));
        server().mcpAccept(Integer.parseInt(a[1]));
        for (String session : new String[] {a[0], b[0]}) {
            assertFalse(inSession(session, TOOL_CALL).json().getAsJsonObject("result").get("isError").getAsBoolean());
        }
        String[] later = pair(name);
        assertFalse(a[1].equals(later[1]), "a session after approval does not inherit it");
        assertTrue(inSession(later[0], TOOL_CALL).json().getAsJsonObject("result").get("isError").getAsBoolean());
        server().mcpRevoke(Integer.parseInt(a[1]));
        assertEquals(404, inSession(b[0], TOOL_CALL).status(), "revoking a number ends all its sessions");
        server().mcpRevoke(Integer.parseInt(later[1]));
    }

    @Test
    void waitingClientsCannotPushOutApprovedOnes() throws IOException {
        String[] kept = pair("kept");
        server().mcpAccept(Integer.parseInt(kept[1]));
        String[] first = pair("flood");
        for (int i = 0; i < 8; i++) pair("flood");
        assertEquals(404, inSession(first[0], TOOL_CALL).status(), "the oldest waiting client gave way");
        assertFalse(inSession(kept[0], TOOL_CALL).json().getAsJsonObject("result").get("isError").getAsBoolean());
        server().mcpRevoke(Integer.parseInt(kept[1]));
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

    // ---- installed scripts ----

    private static JsonObject scriptInfo(String name) throws IOException {
        for (var e : get("/api/scripts").json().getAsJsonArray("scripts")) {
            if (e.getAsJsonObject().get("name").getAsString().equals(name)) return e.getAsJsonObject();
        }
        return null;
    }

    private static JsonObject status(JsonObject info) {
        return info.getAsJsonObject("status");
    }

    private static int heard(String marker) throws IOException {
        return lines(get("/api/events?filter=" + enc("kind == script && data.heard == " + marker)).body()).size();
    }

    @Test
    void installedScriptLifecycle() throws Exception {
        String marker = "ctl-script-" + System.nanoTime();
        String name = marker + ".js";
        String source = """
                // ==Sculk==
                // @name        Listener
                // @description hears %s
                // ==/Sculk==
                var mine = 41;
                sculk.on('packet', e => { if (e.channel && e.channel.indexOf('%s') >= 0) sculk.emit({ heard: '%s' }); });
                sculk.log('started ' + sculk.name);
                mine + 1
                """.formatted(marker, marker, marker);

        JsonObject saved = post("/api/scripts/save?name=" + enc(name) + "&base=", source).json();
        assertTrue(saved.get("created").getAsBoolean(), saved.toString());
        assertEquals("Listener", saved.get("title").getAsString());
        assertEquals("startup", saved.get("run").getAsString());
        assertEquals("running", status(saved).get("state").getAsString(), "a new startup script starts");
        assertTrue(saved.getAsJsonObject("restart").get("ok").getAsBoolean(), saved.toString());
        assertTrue(Files.isRegularFile(FakeGame.SCRIPT_DIR.resolve(name)));
        assertEquals(409, post("/api/scripts/save?name=" + enc(name) + "&base=", source).status(), "base '' means new");

        JsonObject run = post("/api/scripts/run?name=" + enc(name), "").json();
        assertTrue(run.get("ok").getAsBoolean(), run.toString());
        assertEquals(42, run.get("result").getAsInt());
        assertEquals("running", status(run).get("state").getAsString());
        assertEquals(1, status(run).get("listeners").getAsInt());
        assertEquals(2, lines(get("/api/events?filter=" + enc("kind == script_log && script == " + name)).body()).size(), "created, then run");

        // Its globals are its own; the console reaches them only through sculk.scope.
        assertEquals("undefined", post("/api/eval", "typeof mine").json().get("result").getAsString());
        assertEquals(41, post("/api/eval", "sculk.scope('" + name + "').mine").json().get("result").getAsInt());

        EmbeddedChannel ch = FakeGame.channel(Side.SERVER);
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|a")));
        assertEquals(1, heard(marker));

        // Saving a running script restarts it, from the hash it was edited from.
        String hash = saved.get("hash").getAsString();
        assertEquals(409, post("/api/scripts/save?name=" + enc(name) + "&base=nothash", source).status());
        JsonObject resaved = post("/api/scripts/save?name=" + enc(name) + "&base=" + hash, source + "\n").json();
        assertTrue(resaved.getAsJsonObject("restart").get("ok").getAsBoolean(), resaved.toString());
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|b")));
        assertEquals(2, heard(marker), "one listener after the restart, not two");

        // Disabling stops it and renames the file.
        JsonObject off = post("/api/scripts/enable?name=" + enc(name) + "&enabled=false", "").json();
        assertFalse(off.get("enabled").getAsBoolean());
        assertEquals("disabled", status(off).get("why").getAsString());
        assertTrue(Files.isRegularFile(FakeGame.SCRIPT_DIR.resolve(name + ".disabled")));
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:" + marker + "|c")));
        assertEquals(2, heard(marker));
        assertFalse(post("/api/reload", "").json().getAsJsonArray("loaded").toString().contains(name), "reload skips it");

        // Enabling a startup script starts it.
        JsonObject on = post("/api/scripts/enable?name=" + enc(name) + "&enabled=true", "").json();
        assertTrue(on.get("enabled").getAsBoolean());
        assertEquals("running", status(on).get("state").getAsString());

        // Renaming keeps it running under the new name.
        String renamed = marker + "-b.js";
        JsonObject moved = post("/api/scripts/rename?name=" + enc(name) + "&to=" + enc(renamed), "").json();
        assertEquals(renamed, moved.get("name").getAsString());
        assertEquals("running", status(moved).get("state").getAsString());
        assertEquals(404, get("/api/scripts/source?name=" + enc(name)).status());
        assertEquals(source + "\n", get("/api/scripts/source?name=" + enc(renamed)).json().get("source").getAsString());

        assertTrue(post("/api/scripts/stop?name=" + enc(renamed), "").json().get("ok").getAsBoolean());
        assertEquals(409, post("/api/scripts/stop?name=" + enc(renamed), "").status());
        assertTrue(post("/api/scripts/delete?name=" + enc(renamed), "").json().get("ok").getAsBoolean());
        assertEquals(null, scriptInfo(renamed));
        assertFalse(Files.exists(FakeGame.SCRIPT_DIR.resolve(renamed)));
        assertEquals(6, lines(get("/api/events?filter=" + enc("kind == script_file && script matches \"^" + marker + "\"")).body()).size(),
                "create, save, disable, enable, rename, delete");
    }

    @Test
    void manualScriptsRunOnlyWhenAsked() throws Exception {
        String name = "ctl-manual-" + System.nanoTime() + ".js";
        String source = "// ==Sculk==\n// @run manual\n// ==/Sculk==\nsculk.connections().length + 100\n";
        assertEquals(200, post("/api/scripts/save?name=" + enc(name), source).status());
        JsonObject reload = post("/api/reload", "").json();
        assertFalse(reload.getAsJsonArray("loaded").toString().contains(name));
        assertEquals("not-started", status(scriptInfo(name)).get("why").getAsString(), "a new manual script waits");
        JsonObject run = post("/api/scripts/run?name=" + enc(name), "").json();
        assertTrue(run.get("ok").getAsBoolean());
        assertTrue(run.get("result").getAsInt() >= 100);
        assertEquals(0, status(run).get("listeners").getAsInt(), "a one-shot leaves nothing registered");
        post("/api/scripts/delete?name=" + enc(name), "");
    }

    @Test
    void scriptsForTheOtherDistAreSkipped() throws Exception {
        String name = "ctl-dist-" + System.nanoTime() + ".js";
        assertEquals(200, post("/api/scripts/save?name=" + enc(name), "// ==Sculk==\n// @dist server\n// ==/Sculk==\n1\n").status());
        assertEquals("only for a dedicated server", scriptInfo(name).get("skip").getAsString());
        JsonObject reload = post("/api/reload", "").json();
        assertTrue(reload.getAsJsonArray("skipped").toString().contains(name), reload.toString());
        post("/api/scripts/delete?name=" + enc(name), "");
    }

    @Test
    void failingScriptsAreStoppedAndReported() throws Exception {
        String name = "ctl-fail-" + System.nanoTime() + ".js";
        JsonObject saved = post("/api/scripts/save?name=" + enc(name), "sculk.on('packet', e => {});\nthrow new Error('nope');\n").json();
        assertFalse(saved.getAsJsonObject("restart").get("ok").getAsBoolean(), "a new startup script starts, and fails");
        JsonObject run = post("/api/scripts/run?name=" + enc(name), "").json();
        assertFalse(run.get("ok").getAsBoolean());
        assertTrue(run.get("error").getAsString().contains("nope"), run.toString());
        assertTrue(run.get("error").getAsString().contains(name + ":2"), run.toString());
        assertEquals("failed", status(run).get("state").getAsString());
        assertTrue(status(run).get("error").getAsString().contains("nope"));
        assertEquals(0, FakeGame.sculk().scripts().runningNames().stream().filter(name::equals).count(), "no half-started listeners");
        assertEquals(2, lines(get("/api/events?filter=" + enc("kind == script_error && script == " + name)).body()).size());
        post("/api/scripts/delete?name=" + enc(name), "");
    }

    @Test
    void scriptNamesAndMissingScripts() throws IOException {
        assertEquals(400, post("/api/scripts/save?name=" + enc("../evil.js"), "1").status());
        assertEquals(400, post("/api/scripts/save?name=" + enc("no-extension"), "1").status());
        assertEquals(400, post("/api/scripts/save?name=" + enc(".hidden.js"), "1").status());
        assertEquals(400, post("/api/scripts/save", "1").status());
        for (String bad : List.of("../x.js", "..\\x.js", "a/b.js", "/tmp/x.js", "C:x.js", "x.js:stream", "x.js\0.js",
                "x\n.js", "CON.js", "nul.js", "..js", "x..js", "", " x.js")) {
            assertEquals(400, post("/api/scripts/save?name=" + enc(bad), "1").status(), bad);
        }
        // Encoded traversal is decoded first, then refused.
        assertEquals(400, post("/api/scripts/save?name=%2e%2e%2fx.js", "1").status());
        assertFalse(Files.exists(FakeGame.SCRIPT_DIR.getParent().resolve("x.js")));
        assertEquals(404, get("/api/scripts/source?name=nope.js").status());
        assertEquals(404, post("/api/scripts/run?name=nope.js", "").status());
        // sculk.scripts files are listed but managed elsewhere.
        String startup = FakeGame.STARTUP_SCRIPT.toString();
        assertEquals("property", scriptInfo(startup).get("origin").getAsString());
        assertEquals(400, post("/api/scripts/delete?name=" + enc(startup), "").status());
    }

    @Test
    void renameRefusesTraversalAndSymlinksAreWrittenThrough() throws Exception {
        String name = "ctl-paths-" + System.nanoTime() + ".js";
        assertEquals(200, post("/api/scripts/save?name=" + enc(name), "1").status());
        for (String bad : List.of("../x.js", "a/b.js", "/tmp/x.js", "C:x.js", "CON.js")) {
            assertEquals(400, post("/api/scripts/rename?name=" + enc(name) + "&to=" + enc(bad), "").status(), bad);
        }
        assertTrue(Files.exists(FakeGame.SCRIPT_DIR.resolve(name)));
        post("/api/scripts/delete?name=" + enc(name), "");

        // A script symlinked to a file elsewhere: saving updates that file and keeps the link.
        java.nio.file.Path outside = Files.createTempFile("sculk-linked", ".js");
        String linked = "ctl-link-" + System.nanoTime() + ".js";
        java.nio.file.Path link = FakeGame.SCRIPT_DIR.resolve(linked);
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // No symlinks here (Windows without the privilege).
        }
        assertEquals(200, post("/api/scripts/save?name=" + enc(linked), "2").status());
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("2", Files.readString(outside));
        post("/api/scripts/delete?name=" + enc(linked), "");
        assertTrue(Files.exists(outside), "deleting removes the link, not its target");
        Files.delete(outside);
    }

    @Test
    void checkFindsSyntaxErrors() throws IOException {
        JsonObject bad = post("/api/scripts/check", "let a = 1;\nlet b = ;\n").json();
        assertFalse(bad.get("ok").getAsBoolean());
        assertEquals(2, bad.getAsJsonArray("problems").get(0).getAsJsonObject().get("line").getAsInt());
        assertTrue(post("/api/scripts/check", "sculk.on('packet', e => e.drop());").json().get("ok").getAsBoolean());
    }

    @Test
    void pagesStreamTheirResultWhenAskedTo() throws IOException {
        Response plain = request("GET", "/api/events/page?filter=" + enc("kind == packet") + "&from=0&count=3", null, auth());
        JsonObject want = plain.json();
        Response streamed = request("GET", "/api/events/page?filter=" + enc("kind == packet") + "&from=0&count=3", null, auth(),
                "Accept: text/event-stream");
        assertTrue(streamed.header("Content-Type").startsWith("text/event-stream"), streamed.head());
        String result = null;
        for (String block : streamed.body().split("\n\n")) {
            if (block.contains("event: result")) result = block.substring(block.indexOf("data: ") + 6);
        }
        assertNotNull(result, streamed.body());
        // The same answer, give or take events that arrived in between.
        assertEquals(want.get("start"), GSON.fromJson(result, JsonObject.class).get("start"));
        assertEquals(want.getAsJsonArray("events").get(0), GSON.fromJson(result, JsonObject.class).getAsJsonArray("events").get(0));
        // A bad filter is still a plain 400.
        assertEquals(400, request("GET", "/api/events/page?filter=" + enc("kind =="), null, auth(), "Accept: text/event-stream").status());
        assertEquals(400, request("GET", "/api/events/position?id=1&filter=" + enc("(("), null, auth(), "Accept: text/event-stream").status());
        Response pos = request("GET", "/api/events/position?id=1&filter=", null, auth(), "Accept: text/event-stream");
        assertTrue(pos.body().contains("event: result"), pos.body());
    }

    @Test
    void mcpManagesScripts() throws IOException {
        String name = "mcp-" + System.nanoTime() + ".js";
        JsonObject w = callTool("sculk_script_write", GSON.toJson(java.util.Map.of(
                "name", name, "source", "var n = 1;\nsculk.on('connection', c => {});\nn\n", "run", true)));
        assertFalse(w.get("isError").getAsBoolean(), toolText(w));
        assertTrue(toolText(w).contains("\"state\":\"running\""), toolText(w));

        JsonObject read = callTool("sculk_script_read", "{\"name\":\"" + name + "\"}");
        assertTrue(toolText(read).contains("1\tvar n = 1;"), toolText(read));

        JsonObject edit = callTool("sculk_script_edit", GSON.toJson(java.util.Map.of("name", name, "old", "n = 1", "new", "n = 2")));
        assertFalse(edit.get("isError").getAsBoolean(), toolText(edit));
        assertEquals(2, post("/api/eval", "sculk.scope('" + name + "').n").json().get("result").getAsInt(), "restarted with the edit");
        assertTrue(callTool("sculk_script_edit", GSON.toJson(java.util.Map.of("name", name, "old", "absent", "new", "x")))
                .get("isError").getAsBoolean());
        assertTrue(callTool("sculk_script_edit", GSON.toJson(java.util.Map.of("name", name, "old", "n", "new", "m")))
                .get("isError").getAsBoolean(), "ambiguous");

        JsonObject broken = callTool("sculk_script_edit", GSON.toJson(java.util.Map.of("name", name, "old", "n = 2;", "new", "n = ;")));
        assertTrue(broken.get("isError").getAsBoolean(), "syntax errors are errors");
        assertTrue(toolText(broken).contains("problems"), toolText(broken));
        assertTrue(toolText(broken).contains("kept"), toolText(broken));
        assertEquals(2, post("/api/eval", "sculk.scope('" + name + "').n").json().get("result").getAsInt(), "the old version keeps running");

        assertTrue(toolText(callTool("sculk_scripts", "{}")).contains(name));
        JsonObject off = callTool("sculk_script_control", "{\"name\":\"" + name + "\",\"action\":\"disable\"}");
        assertTrue(toolText(off).contains("\"enabled\":false"), toolText(off));
        assertFalse(callTool("sculk_script_control", "{\"name\":\"" + name + "\",\"action\":\"delete\"}").get("isError").getAsBoolean());
        assertTrue(callTool("sculk_script_read", "{\"name\":\"" + name + "\"}").get("isError").getAsBoolean());
    }

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
