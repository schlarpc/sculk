package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RelayListTest {
    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicReference<String> served = new AtomicReference<>();
    private URI source;

    private static List<String> relays(String host) {
        return IntStream.range(0, 25).mapToObj(i -> "wss://r" + i + "." + host).toList();
    }

    private static String json(List<String> relays) {
        return "{\"source\":\"test\",\"relays\":[" + String.join(",", relays.stream().map(r -> "\"" + r + "\"").toList()) + "]}";
    }

    @BeforeEach
    void serve() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/relays.json", ex -> {
            String body = served.get();
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, b.length);
                ex.getResponseBody().write(b);
            }
            ex.close();
        });
        server.start();
        source = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/relays.json");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theBakedListIsInTheBuild() {
        assertTrue(RelayList.baked().size() >= RelayList.MIN_RELAYS);
    }

    @Test
    void fetchesUsesAndCaches() throws IOException {
        Path cache = dir.resolve("cache/relays.json");
        var list = new RelayList(cache, source, relays("baked.example"));
        assertEquals(relays("baked.example"), list.current());
        served.set(json(relays("fetched.example")));
        list.refresh();
        assertEquals(relays("fetched.example"), list.current());
        // A restart uses the cached copy before fetching again.
        assertEquals(relays("fetched.example"), new RelayList(cache, source, relays("baked.example")).current());
    }

    @Test
    void aBadListKeepsTheCurrentOne() throws IOException {
        Path cache = dir.resolve("relays.json");
        var list = new RelayList(cache, source, relays("baked.example"));
        for (String bad : new String[] {
            "not json",
            "{}",
            json(relays("x.example").subList(0, 5)), // too few
            json(List.of(relays("x.example").toArray(String[]::new)).stream()
                    .map(r -> r.replace("wss://", "ws://")).toList()), // not wss
            json(java.util.stream.Stream.concat(relays("x.example").stream(), relays("x.example").stream().limit(1)).toList()),
        }) {
            served.set(bad);
            assertThrows(IOException.class, list::refresh, bad);
            assertEquals(relays("baked.example"), list.current());
        }
        served.set(null);
        assertThrows(IOException.class, list::refresh);
        assertTrue(Files.notExists(cache));
    }

    @Test
    void aDamagedCacheFallsBackToTheBakedList() throws IOException {
        Path cache = dir.resolve("relays.json");
        Files.writeString(cache, "{\"relays\": [\"oops\"]}");
        assertEquals(relays("baked.example"), new RelayList(cache, source, relays("baked.example")).current());
    }
}
