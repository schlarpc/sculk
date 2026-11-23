package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
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
            for (String junk : new String[] {
                "", "{}", "[]", "[1,2]", "[\"EVENT\"]", "[\"EVENT\",\"x\",{}]", "[\"OK\"]",
                // the right shape with the wrong types
                "[[\"EVENT\"]]", "[\"EVENT\",[],{\"tags\":[[\"x\",5]]}]", "[\"EVENT\",{},{\"tags\":[[{},[]]]}]",
                "[\"EVENT\",[],{\"id\":{},\"tags\":[[\"x\",\"" + TOPIC + "\"]]}]",
                "[\"OK\",\"x\",[],{}]", "[\"OK\",\"x\",false,[\"pow:\"]]", "[\"OK\",\"x\",\"false\",\"pow: no\"]",
                "[\"CLOSED\",\"x\",{}]", "[\"CLOSED\",\"x\",[\"blocked:\"]]",
            }) {
                conn.events.text(junk);
            }
            Thread.sleep(200);
            assertEquals(List.of("wss://r1"), p.openRelays());
        }
    }

    @Test
    void anEventCountsOnlyUnderItsOwnId() throws Exception {
        var net = new FakeRelays.Net();
        List<JsonObject> got = Collections.synchronizedList(new ArrayList<>());
        try (var p = pool(net, URLS, 2, got)) {
            p.subscribe(TOPIC);
            await(() -> Stream.of("wss://r1", "wss://r2").allMatch(
                    u -> net.relay(u).conns.stream().anyMatch(c -> !c.subs.isEmpty())), "subscribed");
            String event = new Nostr.Signer().event(TOPIC, "aGk=", 1_000_000L);
            // r1 gets in first with other content under the real event's id;
            // r2 is honest. Were r1 believed, r2's copy would be a duplicate.
            var liar = net.relay("wss://r1").conns.get(0);
            liar.deliver(event.replace("\"aGk=\"", "\"bGllcw==\""));
            liar.deliver(event.replace("\"created_at\":1000000,", "\"created_at\":1000001,"));
            liar.deliver(event.replace("\"created_at\":1000000,", "\"created_at\":1e6,")); // not as it was hashed
            net.relay("wss://r2").conns.get(0).deliver(event);
            await(() -> !got.isEmpty(), "the real event delivered");
            liar.deliver(event); // and r1 may yet tell the truth, once is enough
            Thread.sleep(200);
            assertEquals(1, got.size());
            assertEquals("aGk=", got.get(0).get("content").getAsString());
        }
    }

    @Test
    void aMessageWithoutEndCostsTheRelayItsConnection() throws Exception {
        try (var relay = new FakeRelays.Endless();
                var p = new RelayPool(List.of(relay.url()), 1, RelayPool.webSockets(), () -> 1_000_000L, e -> {})) {
            // Cut off, and then treated as any relay that dropped: tried
            // again after the backoff, and cut off again.
            await(() -> relay.cut.get() >= 2 || relay.swallowed, "cut off twice");
            assertFalse(relay.swallowed, "the pool took " + FakeRelays.Endless.GIVE_UP + " bytes of one message");
        }
    }

    @Test
    void whatARelaySaysIsLoggedShortAndOnOneLine() {
        assertEquals("pow: 20", RelayPool.clean("pow: 20"));
        assertEquals("a?b??c?", RelayPool.clean("a\nb\r\u001bc\u2028"));
        assertEquals(201, RelayPool.clean("x".repeat(100_000)).length());
    }
}
