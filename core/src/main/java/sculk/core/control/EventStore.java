package sculk.core.control;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The most recent events, in memory, for the control server to query and
 * tail. Bounded by event count and by total frame bytes; the oldest events
 * are dropped first.
 */
public final class EventStore {
    private final int maxEvents;
    private final long maxFrameBytes;
    private final ArrayDeque<StoredEvent> events = new ArrayDeque<>();
    private final List<Consumer<StoredEvent>> subscribers = new CopyOnWriteArrayList<>();
    private long frameBytes;

    public EventStore(int maxEvents, long maxFrameBytes) {
        this.maxEvents = maxEvents;
        this.maxFrameBytes = maxFrameBytes;
    }

    public void add(StoredEvent event) {
        synchronized (events) {
            events.addLast(event);
            if (event.frame() != null) frameBytes += event.frame().length;
            while (events.size() > maxEvents || (frameBytes > maxFrameBytes && events.size() > 1)) {
                StoredEvent old = events.removeFirst();
                if (old.frame() != null) frameBytes -= old.frame().length;
            }
        }
        for (Consumer<StoredEvent> s : subscribers) {
            try {
                s.accept(event);
            } catch (RuntimeException ignored) {
                // A broken subscriber must not break logging.
            }
        }
    }

    /**
     * Events matching {@code filter}, oldest first. With {@code after}, the
     * first {@code limit} events whose id is greater; without it, the last
     * {@code limit} events.
     */
    public List<StoredEvent> query(Predicate<Map<String, Object>> filter, Long after, int limit) {
        List<StoredEvent> snapshot;
        synchronized (events) {
            snapshot = new ArrayList<>(events);
        }
        List<StoredEvent> out = new ArrayList<>();
        for (StoredEvent e : snapshot) {
            if (after != null && e.id() <= after) continue;
            if (!filter.test(e.fields())) continue;
            out.add(e);
            if (after != null && out.size() >= limit) break;
        }
        return after == null && out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
    }

    public StoredEvent get(long id) {
        synchronized (events) {
            for (StoredEvent e : events) {
                if (e.id() == id) return e;
            }
        }
        return null;
    }

    public int size() {
        synchronized (events) {
            return events.size();
        }
    }

    public void subscribe(Consumer<StoredEvent> subscriber) { subscribers.add(subscriber); }
    public void unsubscribe(Consumer<StoredEvent> subscriber) { subscribers.remove(subscriber); }
}
