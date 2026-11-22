package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class RelayPoolTest {
    private static void await(BooleanSupplier cond, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 10_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timed out: " + what);
            Thread.sleep(20);
        }
    }

    private static final String TOPIC = "ab".repeat(32);
    private static final List<String> URLS = List.of("wss://r1", "wss://r2", "wss://r3", "wss://r4", "wss://r5");

    private static RelayPool pool(FakeRelays.Net net, List<String> urls, int want, List<JsonObject> into) {
        return new RelayPool(urls, want, net, () -> 1_000_000L, into::add);
    }

    @Test
    void deliversOnceThroughEveryOverlappingRelay() throws Exception {
        var net = new FakeRelays.Net();
        List<JsonObject> got = Collections.synchronizedList(new ArrayList<>());
        try (var game = pool(net, URLS, 5, got); var browser = pool(net, URLS, 3, new ArrayList<>())) {
            game.subscribe(TOPIC);
            await(() -> game.openRelays().size() == 5 && browser.openRelays().size() == 3, "relays open");
            await(() -> net.relays.values().stream().allMatch(r -> r.conns.stream().anyMatch(c -> !c.subs.isEmpty())), "subscribed");
            String event = new Nostr.Signer().event(TOPIC, "aGk=", 1_000_000L);
            browser.publish(event);
            await(() -> !got.isEmpty(), "event delivered");
            Thread.sleep(200);
            assertEquals(1, got.size(), "delivered by 3 relays, handed on once");
            assertEquals(3, net.relays.values().stream().filter(r -> !r.received.isEmpty()).count());
        }
    }

    @Test
    void otherTopicsAreIgnored() throws Exception {
        var net = new FakeRelays.Net();
        List<JsonObject> got = Collections.synchronizedList(new ArrayList<>());
        try (var game = pool(net, URLS, 2, got); var other = pool(net, URLS, 2, new ArrayList<>())) {
            game.subscribe(TOPIC);
            other.subscribe("cd".repeat(32));
            await(() -> game.openRelays().size() == 2 && other.openRelays().size() == 2, "open");
            Thread.sleep(100);
            other.publish(new Nostr.Signer().event("cd".repeat(32), "aGk=", 1_000_000L));
            Thread.sleep(300);
            assertTrue(got.isEmpty());
        }
    }

    @Test
    void aRelayThatRefusesForGoodIsReplacedByTheNext() throws Exception {
        var net = new FakeRelays.Net();
        net.relay("wss://r1").refuse = "pow: difficulty 20 required";
        try (var p = pool(net, URLS, 2, new ArrayList<>())) {
            await(() -> p.openRelays().size() == 2, "open");
            p.publish(new Nostr.Signer().event(TOPIC, "aGk=", 1_000_000L));
            await(() -> p.openRelays().equals(List.of("wss://r2", "wss://r3")), "r1 replaced by r3");
        }
    }

    @Test
    void aPassingRefusalKeepsTheRelay() throws Exception {
        var net = new FakeRelays.Net();
        net.relay("wss://r1").refuse = "rate-limited: slow down";
        try (var p = pool(net, URLS, 2, new ArrayList<>())) {
            await(() -> p.openRelays().size() == 2, "open");
            p.publish(new Nostr.Signer().event(TOPIC, "aGk=", 1_000_000L));
            Thread.sleep(300);
            assertEquals(List.of("wss://r1", "wss://r2"), p.openRelays());
        }
    }

    @Test
    void aRelayThatDropsIsRetriedAndResubscribed() throws Exception {
        var net = new FakeRelays.Net();
        List<JsonObject> got = Collections.synchronizedList(new ArrayList<>());
        try (var p = pool(net, URLS, 1, got); var sender = pool(net, URLS, 1, new ArrayList<>())) {
            p.subscribe(TOPIC);
            FakeRelays.Relay r1 = net.relay("wss://r1");
            await(() -> r1.conns.stream().anyMatch(c -> !c.subs.isEmpty()), "subscribed");
            r1.up = false;
            r1.bounce();
            await(() -> p.openRelays().isEmpty(), "noticed");
            r1.up = true;
            await(() -> r1.conns.stream().anyMatch(c -> !c.subs.isEmpty()), "reconnected and resubscribed");
            await(() -> sender.openRelays().size() == 1, "sender open");
            sender.publish(new Nostr.Signer().event(TOPIC, "aGk=", 1_000_000L));
            await(() -> !got.isEmpty(), "delivered after the reconnect");
        }
    }

    @Test
    void nonsenseFromARelayIsIgnored() throws Exception {
        var net = new FakeRelays.Net();
        try (var p = pool(net, URLS, 1, new ArrayList<>())) {
            p.subscribe(TOPIC);
            await(() -> p.openRelays().size() == 1, "open");
            var conn = net.relay("wss://r1").conns.get(0);
            for (String junk : new String[] {"", "{}", "[]", "[1,2]", "[\"EVENT\"]", "[\"EVENT\",\"x\",{}]", "[\"OK\"]"}) {
                conn.events.text(junk);
            }
            Thread.sleep(200);
            assertFalse(p.openRelays().isEmpty());
        }
    }
}
