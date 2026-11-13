package sculk.core.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class EventStoreTest {
    private static StoredEvent event(long id, String kind, int frameBytes) {
        return new StoredEvent(id, Map.of("id", id, "kind", kind), "{}", frameBytes < 0 ? null : new byte[frameBytes]);
    }

    private static List<Long> ids(List<StoredEvent> events) {
        return events.stream().map(StoredEvent::id).toList();
    }

    @Test
    void dropsOldestByCount() {
        EventStore store = new EventStore(3, Long.MAX_VALUE);
        for (long i = 1; i <= 5; i++) store.add(event(i, "packet", -1));
        assertEquals(3, store.size());
        assertNull(store.get(2));
        assertNotNull(store.get(3));
    }

    @Test
    void dropsOldestByFrameBytesButKeepsTheNewest() {
        EventStore store = new EventStore(100, 100);
        store.add(event(1, "packet", 60));
        store.add(event(2, "packet", 30));
        store.add(event(3, "packet", 30));
        assertEquals(List.of(2L, 3L), ids(store.query(e -> true, null, 100)));
        store.add(event(4, "packet", 500));
        assertEquals(List.of(4L), ids(store.query(e -> true, null, 100)));
    }

    @Test
    void queryWithoutAfterReturnsTheLastMatches() {
        EventStore store = new EventStore(100, Long.MAX_VALUE);
        for (long i = 1; i <= 10; i++) store.add(event(i, i % 2 == 0 ? "packet" : "script", -1));
        assertEquals(List.of(6L, 8L, 10L), ids(store.query(e -> "packet".equals(e.get("kind")), null, 3)));
    }

    @Test
    void queryWithAfterReturnsTheNextMatches() {
        EventStore store = new EventStore(100, Long.MAX_VALUE);
        for (long i = 1; i <= 10; i++) store.add(event(i, "packet", -1));
        assertEquals(List.of(5L, 6L), ids(store.query(e -> true, 4L, 2)));
        assertEquals(List.of(), ids(store.query(e -> true, 10L, 2)));
    }

    @Test
    void subscribersSeeNewEventsUntilUnsubscribed() {
        EventStore store = new EventStore(100, Long.MAX_VALUE);
        List<Long> seen = new CopyOnWriteArrayList<>();
        Consumer<StoredEvent> s = e -> seen.add(e.id());
        Consumer<StoredEvent> broken = e -> { throw new RuntimeException("boom"); };
        store.subscribe(broken);
        store.subscribe(s);
        store.add(event(1, "packet", -1));
        store.unsubscribe(s);
        store.add(event(2, "packet", -1));
        assertEquals(List.of(1L), seen);
        assertEquals(2, store.size());
    }
}
