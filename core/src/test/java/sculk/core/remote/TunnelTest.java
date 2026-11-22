package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TunnelTest {
    private HttpServer server;
    private ExecutorService readers;
    private final BlockingQueue<byte[]> out = new LinkedBlockingQueue<>();
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> seenCookie = new AtomicReference<>();
    private final CountDownLatch streamClosed = new CountDownLatch(1);
    private Tunnel tunnel;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/hello", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            seenCookie.set(ex.getRequestHeaders().getFirst("Cookie"));
            byte[] b = "{\"hi\":true}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.getResponseHeaders().set("Set-Cookie", "no=thanks");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/api/echo", ex -> {
            byte[] b = ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(201, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/api/stream", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream o = ex.getResponseBody();
            try {
                for (int i = 0; i < 600; i++) {
                    o.write(("data: " + i + "\n\n").getBytes(StandardCharsets.UTF_8));
                    o.flush();
                    Thread.sleep(50);
                }
            } catch (IOException | InterruptedException e) {
                streamClosed.countDown();
            } finally {
                ex.close();
            }
        });
        server.start();
        readers = Executors.newCachedThreadPool();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        tunnel = new Tunnel(out::add, () -> new Tunnel.Upstream() {
            @Override
            public URI base() {
                return base;
            }

            @Override
            public String token() {
                return "TOKEN";
            }
        }, readers);
    }

    @AfterEach
    void stop() {
        tunnel.close();
        server.stop(0);
        readers.shutdownNow();
    }

    private void request(int id, String method, String path, String body, String headersJson) {
        String head = "{\"method\":\"" + method + "\",\"path\":\"" + path + "\""
                + (headersJson == null ? "" : ",\"headers\":" + headersJson) + "}";
        tunnel.receive(Tunnel.message(id, Tunnel.REQUEST, head.getBytes(StandardCharsets.UTF_8)));
        if (body != null) tunnel.receive(Tunnel.message(id, Tunnel.REQUEST_BODY, body.getBytes(StandardCharsets.UTF_8)));
        tunnel.receive(Tunnel.message(id, Tunnel.REQUEST_END, new byte[0]));
    }

    private record Msg(int stream, int type, byte[] data) {
        String text() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private Msg next() throws InterruptedException {
        byte[] m = out.poll(10, TimeUnit.SECONDS);
        if (m == null) throw new AssertionError("no message");
        ByteBuffer b = ByteBuffer.wrap(m);
        return new Msg(b.getInt(), b.get() & 0xff, Arrays.copyOfRange(m, 5, m.length));
    }

    /** Status, then the whole body, of one stream's answer. */
    private String[] answer(int stream) throws InterruptedException {
        Msg head = next();
        assertEquals(stream, head.stream());
        assertEquals(Tunnel.RESPONSE, head.type(), head.text());
        var body = new ByteArrayOutputStream();
        for (Msg m = next(); m.type() != Tunnel.RESPONSE_END; m = next()) {
            assertEquals(Tunnel.RESPONSE_BODY, m.type());
            body.writeBytes(m.data());
        }
        return new String[] {head.text(), body.toString(StandardCharsets.UTF_8)};
    }

    @Test
    void aRequestGoesThroughWithTheToken() throws Exception {
        request(1, "GET", "/api/hello", null, "{\"Cookie\":\"secret\",\"Accept\":\"application/json\"}");
        String[] a = answer(1);
        JsonObject head = JsonParser.parseString(a[0]).getAsJsonObject();
        assertEquals(200, head.get("status").getAsInt());
        assertEquals("application/json", head.getAsJsonObject("headers").get("content-type").getAsString());
        assertTrue(!head.getAsJsonObject("headers").has("set-cookie"), "only safe headers come back");
        assertEquals("{\"hi\":true}", a[1]);
        assertEquals("Bearer TOKEN", seenAuth.get());
        assertNull(seenCookie.get(), "the browser's other headers stay behind");
    }

    @Test
    void aBodyGoesThrough() throws Exception {
        String big = "x".repeat(50_000);
        request(3, "POST", "/api/echo", big, null);
        String[] a = answer(3);
        assertEquals(201, JsonParser.parseString(a[0]).getAsJsonObject().get("status").getAsInt());
        assertEquals(big, a[1]);
    }

    @Test
    void aStreamArrivesAsItIsWrittenAndCancelStopsIt() throws Exception {
        request(5, "GET", "/api/stream", null, null);
        Msg head = next();
        assertEquals(Tunnel.RESPONSE, head.type());
        StringBuilder got = new StringBuilder();
        while (!got.toString().contains("data: 3")) got.append(next().text());
        tunnel.receive(Tunnel.message(5, Tunnel.CANCEL, new byte[0]));
        assertTrue(streamClosed.await(10, TimeUnit.SECONDS), "the server saw the stream end");
    }

    @Test
    void badPathsAreRefused() throws Exception {
        for (String path : new String[] {"http://evil/", "//evil/x", "relative", "/a b"}) {
            out.clear();
            request(7, "GET", path, null, null);
            Msg m = next();
            assertEquals(Tunnel.ERROR, m.type(), path);
        }
    }

    @Test
    void withTheControlServerOffTheBrowserIsTold() throws Exception {
        tunnel.close();
        tunnel = new Tunnel(out::add, () -> null, readers);
        request(9, "GET", "/api/hello", null, null);
        Msg m = next();
        assertEquals(Tunnel.ERROR, m.type());
        assertTrue(m.text().contains("control server is off"), m.text());
    }
}
