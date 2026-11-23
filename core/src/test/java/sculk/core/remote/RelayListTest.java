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

    private static List<String> with(List<String> relays, String more) {
        return java.util.stream.Stream.concat(relays.stream(), java.util.stream.Stream.of(more)).toList();
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
            json(IntStream.range(0, RelayList.MAX_RELAYS + 1).mapToObj(i -> "wss://r" + i + ".x.example").toList()), // too many
            // addresses, as a URL parser reads them, not names
            json(with(relays("x.example"), "wss://192.168.1.1")),
            json(with(relays("x.example"), "wss://10.0.0.1:8080/")),
            json(with(relays("x.example"), "wss://relay.example.7")),
            json(with(relays("x.example"), "wss://127.0.0.0x1")),
            "{\"relays\":[],\"pad\":\"" + "x".repeat(RelayList.MAX_BYTES) + "\"}", // too big to read at all
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
    void aFullListOfNamesIsTaken() {
        List<String> most = IntStream.range(0, RelayList.MAX_RELAYS).mapToObj(i -> "wss://r" + i + ".x.example").toList();
        assertEquals(most, RelayList.parse(json(most)));
        // Digits are fine anywhere but in the last label, alone.
        List<String> named = with(relays("x.example"), "wss://1.2.3.4.example");
        named = with(named, "wss://relay.0x.example:443/");
        named = with(named, "wss://relay.4chan1");
        assertEquals(named, RelayList.parse(json(named)));
    }

    @Test
    void aBodyWithoutEndIsNotReadToTheEnd() throws IOException {
        // A good list, then padding: within the limit it is taken, beyond it
        // refused, though the JSON is as good.
        String list = json(relays("fetched.example"));
        var list1 = new RelayList(dir.resolve("relays.json"), source, relays("baked.example"));
        served.set(list + " ".repeat(RelayList.MAX_BYTES - list.length()));
        list1.refresh();
        assertEquals(relays("fetched.example"), list1.current());
        var list2 = new RelayList(dir.resolve("other.json"), source, relays("baked.example"));
        served.set(list + " ".repeat(RelayList.MAX_BYTES - list.length() + 1));
        assertThrows(IOException.class, list2::refresh);
        assertEquals(relays("baked.example"), list2.current());
    }

    @Test
    void aDamagedCacheFallsBackToTheBakedList() throws IOException {
        Path cache = dir.resolve("relays.json");
        Files.writeString(cache, "{\"relays\": [\"oops\"]}");
        assertEquals(relays("baked.example"), new RelayList(cache, source, relays("baked.example")).current());
    }
}
