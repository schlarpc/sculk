package sculk.core.control;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;
import sculk.core.Sculk;

/**
 * Every event of the session, for the control server to query and tail,
 * like a capture file that is still being written.
 *
 * <ul>
 *   <li><b>Spool.</b> Each event, with its whole frame, is appended to
 *       segment files in a capture directory by a writer thread. When the
 *       directory passes its size cap, the oldest segment goes, and its
 *       events with it. The directory is temporary: it is deleted when the
 *       game exits (and, if the game died, by the next launch).</li>
 *   <li><b>Index.</b> In memory, about 47 bytes per event: time, kind,
 *       connection, direction, length, packet type, owners, channel, script
 *       and where the event sits on disk. A filter that only reads those
 *       fields runs on the index alone; anything else ({@code mod.data.x},
 *       {@code frame.hex contains ...}) reads the events back from disk.</li>
 *   <li><b>Hot cache.</b> The most recent events stay in memory whole, so
 *       live tails and recent queries never touch the disk, and events the
 *       writer has not reached yet are always readable.</li>
 * </ul>
 *
 * Without a capture directory ({@code sculk.captureBytes=0}) it is the hot
 * cache alone: a ring of the most recent events, as before.
 *
 * <p>Event ids are consecutive (the engine numbers every entry), so the
 * index is addressed by id.
 */
public final class EventStore implements AutoCloseable {
    /** Scans report progress, and check for cancellation, this often. */
    private static final int TICK = 8192;
    /** Match lists are extended this many ids at a time; see {@link #matches}. Smaller in tests. */
    long matchChunk = 1 << 18;
    private static final long SEGMENT_BYTES = 64L << 20;
    private final long segmentBytes;
    private static final Gson READ = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private static final Type MAP = new TypeToken<Map<String, Object>>() {}.getType();

    /** Progress of a long scan, and a way to stop it. */
    public interface Progress {
        Progress NONE = new Progress() {};

        default void tick(long done, long total) {}

        default boolean cancelled() {
            return false;
        }
    }

    /** Thrown out of a scan whose {@link Progress} was cancelled. */
    public static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("cancelled", null, false, false);
        }
    }

    // ---- the hot cache: a ring of the newest events, by id ----

    private final StoredEvent[] ring;
    private final int mask;
    private final int maxHot;
    private final long maxHotFrameBytes;
    /** Oldest id in the ring; guarded by {@code ring}. */
    private long hotFirst = 1;
    private long hotFrameBytes;

    /** Newest id stored; 0 before any. */
    private volatile long lastId;
    /** Oldest id still held anywhere (disk or memory). */
    private volatile long firstId = 1;

    private final Index index = new Index();
    private final Spool spool;
    private final List<Consumer<StoredEvent>> subscribers = new CopyOnWriteArrayList<>();
    /** Events read back from disk; for tests. */
    final java.util.concurrent.atomic.AtomicLong diskReads = new java.util.concurrent.atomic.AtomicLong();

    /** A store with no disk: the hot cache alone. */
    public EventStore(int maxEvents, long maxFrameBytes) {
        this(maxEvents, maxFrameBytes, null, 0);
    }

    /**
     * @param maxHotEvents   events held whole in memory
     * @param maxHotFrameBytes bytes of frames held in memory
     * @param dir            the capture directory (created; deleted on exit), or null for memory only
     * @param maxDiskBytes   size cap of the capture directory
     */
    public EventStore(int maxHotEvents, long maxHotFrameBytes, Path dir, long maxDiskBytes) {
        this(maxHotEvents, maxHotFrameBytes, dir, maxDiskBytes, SEGMENT_BYTES);
    }

    /** With a segment size, for tests: the disk cap is at least two segments. */
    EventStore(int maxHotEvents, long maxHotFrameBytes, Path dir, long maxDiskBytes, long segmentBytes) {
        this.segmentBytes = segmentBytes;
        int capacity = Integer.highestOneBit(Math.max(16, maxHotEvents - 1)) << 1;
        this.ring = new StoredEvent[capacity];
        this.mask = capacity - 1;
        this.maxHot = Math.max(1, maxHotEvents);
        this.maxHotFrameBytes = maxHotFrameBytes;
        Spool s = null;
        if (dir != null && maxDiskBytes > 0) {
            try {
                s = new Spool(dir, Math.max(maxDiskBytes, 2 * segmentBytes));
            } catch (IOException e) {
                Sculk.LOG.error("cannot open the capture directory {}; keeping recent events in memory only", dir, e);
            }
        }
        this.spool = s;
    }

    // ---- adding ----

    /** Adds the next event. Ids must arrive in order, one apart; the engine's log lock serialises calls. */
    public void add(StoredEvent event) {
        long id = event.id();
        index.put(id, event.fields());
        synchronized (ring) {
            if (lastId == 0) {
                hotFirst = id;
                if (firstId < id) firstId = id;
            }
            ring[(int) (id & mask)] = event;
            if (event.frame() != null) hotFrameBytes += event.frame().length;
            lastId = id;
            while (hotFirst < id && (id - hotFirst + 1 > maxHot || hotFrameBytes > maxHotFrameBytes || id - hotFirst + 1 > ring.length)) {
                // Keep events the writer has not reached, unless the ring itself is full.
                if (spool != null && hotFirst > spool.writtenUpTo && id - hotFirst + 1 <= ring.length) break;
                evictOldestHot();
            }
            if (spool == null) firstId = hotFirst;
        }
        if (spool != null) spool.enqueue(event);
        for (Consumer<StoredEvent> s : subscribers) {
            try {
                s.accept(event);
            } catch (RuntimeException ignored) {
                // A broken subscriber must not break logging.
            }
        }
    }

    private void evictOldestHot() {
        int slot = (int) (hotFirst & mask);
        StoredEvent old = ring[slot];
        ring[slot] = null;
        if (old != null && old.frame() != null) hotFrameBytes -= old.frame().length;
        hotFirst++;
        if (spool == null) index.trim(hotFirst);
    }

    // ---- reading ----

    public long firstId() {
        long last = lastId;
        return last == 0 ? 0 : Math.min(firstId, last);
    }

    public long lastId() {
        return lastId;
    }

    /** Events held. */
    public int size() {
        long last = lastId;
        return last == 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, last - firstId + 1);
    }

    /** The event with this id, from memory or disk; null if it is gone (or not written yet and pushed out of memory). */
    public StoredEvent get(long id) {
        if (id < firstId || id > lastId) return null;
        synchronized (ring) {
            if (id >= hotFirst) {
                StoredEvent e = ring[(int) (id & mask)];
                if (e != null && e.id() == id) return e;
            }
        }
        if (spool == null) return null;
        diskReads.incrementAndGet();
        return spool.read(id);
    }

    /** What the store holds: ids, memory and disk use. */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("firstId", firstId());
        out.put("lastId", lastId);
        out.put("events", size());
        synchronized (ring) {
            out.put("memoryEvents", lastId == 0 ? 0 : lastId - hotFirst + 1);
        }
        out.put("indexBytes", index.bytes());
        if (spool != null) {
            out.put("dir", spool.dir.toString());
            out.put("diskBytes", spool.totalBytes);
            out.put("diskCap", spool.maxBytes);
            out.put("pendingWrites", spool.queue.size());
        }
        return out;
    }

    /**
     * Matching events, oldest first. Searches ids in ({@code after},
     * {@code before}). With {@code after}, the first {@code limit} matches
     * after it; without, the last {@code limit} matches (before {@code before}).
     */
    public List<StoredEvent> query(String filter, Long after, Long before, int limit, Progress progress) {
        EventFilter.Compiled f = EventFilter.analyze(filter);
        List<StoredEvent> out = new ArrayList<>();
        if (limit <= 0) return out;
        long lo = Math.max(firstId(), after == null ? 1 : after + 1);
        long hi = Math.min(lastId, before == null ? Long.MAX_VALUE : before - 1);
        boolean forward = after != null;
        scan(f, lo, hi, forward, progress, (id, full) -> {
            StoredEvent e = full != null ? full : get(id);
            if (e != null) out.add(e);
            return out.size() < limit;
        });
        if (!forward) Collections.reverse(out);
        return out;
    }

    public List<StoredEvent> query(String filter, Long after, int limit) {
        return query(filter, after, null, limit, Progress.NONE);
    }

    /** Called for each match; returns false to stop. {@code full} is the event when the scan had to load it, else null. */
    private interface Sink {
        boolean accept(long id, StoredEvent full);
    }

    /**
     * Runs {@code f} over ids {@code lo..hi} (inclusive), forward or newest
     * first, on the index where it can and on whole events where it must.
     */
    private void scan(EventFilter.Compiled f, long lo, long hi, boolean forward, Progress progress, Sink sink) {
        if (lo > hi) return;
        boolean indexed = Index.canAnswer(f.refs());
        long total = hi - lo + 1, done = 0;
        for (long id = forward ? lo : hi; forward ? id <= hi : id >= lo; id += forward ? 1 : -1) {
            if (++done % TICK == 0) {
                if (progress.cancelled()) throw new Cancelled();
                progress.tick(done, total);
            }
            if (id < firstId) {
                // Dropped from the disk while we scanned.
                if (forward) {
                    id = firstId - 1;
                    continue;
                }
                break;
            }
            Map<String, Object> view = indexed || f.matchesAll() ? index.view(id) : null;
            if (f.matchesAll() && view != null) {
                if (!sink.accept(id, null)) return;
                continue;
            }
            StoredEvent full = null;
            if (view == null) {
                full = get(id);
                if (full == null) continue;
                view = full.fields();
            }
            if (f.predicate().test(view) && !sink.accept(id, full)) return;
        }
        progress.tick(total, total);
    }

    /**
     * Counts, frame bytes and first and last appearance of the events matching
     * {@code filter} in ids ({@code after}, {@code before}), grouped by the
     * value of {@code by} (a field path, shorthands allowed), biggest groups
     * first. Without {@code by}, one group of everything.
     */
    public Map<String, Object> stats(String filter, String by, Long after, Long before, int limit, Progress progress) {
        EventFilter.Compiled f = EventFilter.analyze(filter);
        boolean grouped = by != null && !by.isBlank();
        if (grouped) EventFilter.analyze(by); // a syntax check for the path
        boolean byIndexed = !grouped || Index.canAnswer(List.of(new EventFilter.Ref(by, false)));
        long lo = Math.max(firstId(), after == null ? 1 : after + 1);
        long hi = Math.min(lastId, before == null ? Long.MAX_VALUE : before - 1);
        Map<String, long[]> groups = new HashMap<>(); // count, bytes, firstId, firstT, lastId, lastT
        long[] totals = {0, 0};
        scan(f, lo, hi, true, progress, (id, full) -> {
            Map<String, Object> fields;
            if (full != null) {
                fields = full.fields();
            } else if (byIndexed) {
                fields = index.view(id);
                if (fields == null) {
                    StoredEvent e = get(id);
                    if (e == null) return true;
                    fields = e.fields();
                }
            } else {
                StoredEvent e = get(id);
                if (e == null) return true;
                fields = e.fields();
            }
            String key = grouped ? keyOf(EventFilter.value(fields, by)) : "all";
            long bytes = index.frameLen(id);
            long t = index.time(id);
            long[] g = groups.computeIfAbsent(key, k -> new long[] {0, 0, id, t, id, t});
            g[0]++;
            if (bytes > 0) g[1] += bytes;
            g[4] = id;
            g[5] = t;
            totals[0]++;
            if (bytes > 0) totals[1] += bytes;
            return true;
        });
        List<Map.Entry<String, long[]>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, long[]> en : sorted.subList(0, Math.min(sorted.size(), Math.max(1, limit)))) {
            long[] g = en.getValue();
            Map<String, Object> row = new LinkedHashMap<>();
            if (grouped) row.put("value", en.getKey());
            row.put("count", g[0]);
            row.put("bytes", g[1]);
            row.put("firstId", g[2]);
            row.put("firstT", g[3]);
            row.put("lastId", g[4]);
            row.put("lastT", g[5]);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scanned", Map.of("firstId", lo, "lastId", Math.max(lo - 1, hi)));
        out.put("matched", totals[0]);
        out.put("bytes", totals[1]);
        if (grouped) {
            out.put("by", by);
            out.put("distinct", groups.size());
        }
        out.put("groups", rows);
        return out;
    }

    private static String keyOf(Object v) {
        if (v == null) return "(none)";
        if (v instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf(d.longValue());
        return v instanceof Map<?, ?> || v instanceof List<?> ? sculk.core.log.EventLog.toJson(v) : String.valueOf(v);
    }

    // ---- positions: the n-th match of a filter, for a table that scrolls through the whole capture ----

    /**
     * The ids matching one filter, in order, extended as events arrive and
     * trimmed as old ones are dropped. {@link #removed} counts the matches
     * dropped from the front, so a row keeps its number for as long as it is
     * held. (Matches dropped before the list first reached them are not
     * counted: numbers are stable, not counts from the start of the session.)
     */
    private static final class Matches {
        final EventFilter.Compiled filter;
        int[] ids = new int[1024];
        int size;
        long removed;
        long scannedTo;

        Matches(EventFilter.Compiled filter) {
            this.filter = filter;
        }

        void add(long id) {
            if (size == ids.length) ids = java.util.Arrays.copyOf(ids, ids.length * 2);
            ids[size++] = (int) id;
        }

        /** Index of the first id at or after {@code id}. */
        int lowerBound(long id) {
            int lo = 0, hi = size;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (ids[mid] < id) lo = mid + 1;
                else hi = mid;
            }
            return lo;
        }
    }

    private final Map<String, Matches> matchCache = Collections.synchronizedMap(new LinkedHashMap<>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Matches> eldest) {
            return size() > 4;
        }
    });

    /** The match list for a filter, brought up to date. */
    private Matches matches(String filter, Progress progress) {
        String key = filter == null ? "" : filter.trim();
        Matches m = matchCache.get(key);
        if (m == null) {
            m = new Matches(EventFilter.analyze(key));
            matchCache.put(key, m);
        }
        synchronized (m) {
            long first = firstId(), last = lastId;
            if (m.filter.matchesAll()) {
                // Every id: no list needed.
                m.removed = last == 0 ? 0 : first - 1;
                m.size = last == 0 ? 0 : (int) (last - first + 1);
                m.scannedTo = last;
                return m;
            }
            int gone = m.lowerBound(first);
            if (gone > 0) {
                System.arraycopy(m.ids, gone, m.ids, 0, m.size - gone);
                m.size -= gone;
                m.removed += gone;
            }
            // In chunks, so a scan that is cancelled (the caller went away) keeps what it finished:
            // a chunk is either wholly in the list, with scannedTo past it, or not at all.
            long from = Math.max(m.scannedTo + 1, first);
            long grand = Math.max(0, last - from + 1), base = 0;
            Matches target = m;
            while (from <= last) {
                if (progress.cancelled()) throw new Cancelled();
                long to = Math.min(last, from + matchChunk - 1);
                int sizeBefore = m.size;
                long offset = base;
                Progress chunk = new Progress() {
                    @Override
                    public void tick(long done, long total) {
                        progress.tick(offset + done, grand);
                    }

                    @Override
                    public boolean cancelled() {
                        return progress.cancelled();
                    }
                };
                try {
                    scan(m.filter, from, to, true, chunk, (id, full) -> {
                        target.add(id);
                        return true;
                    });
                } catch (Cancelled c) {
                    m.size = sizeBefore;
                    throw c;
                }
                m.scannedTo = to;
                base += to - from + 1;
                from = to + 1;
            }
            return m;
        }
    }

    /**
     * Matches number {@code from} to {@code from + count - 1} of a filter,
     * counting from the start of the capture. {@code start} is the first
     * position still held, {@code total} one past the last.
     */
    public Map<String, Object> page(String filter, long from, int count, Progress progress) {
        Matches m = matches(filter, progress);
        List<StoredEvent> events = new ArrayList<>();
        long start, total;
        synchronized (m) {
            start = m.removed;
            total = m.removed + m.size;
            for (long i = Math.max(from, start); i < Math.min(from + count, total); i++) {
                long id = m.filter.matchesAll() ? i + 1 : m.ids[(int) (i - m.removed)];
                StoredEvent e = get(id);
                if (e != null) events.add(e);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("start", start);
        out.put("total", total);
        out.put("from", Math.max(from, start));
        out.put("events", events);
        return out;
    }

    /** The position of the first match at or after {@code id}, and the range of positions held. */
    public Map<String, Object> position(String filter, long id, Progress progress) {
        Matches m = matches(filter, progress);
        synchronized (m) {
            long index = m.filter.matchesAll()
                    ? Math.max(m.removed, Math.min(id - 1, m.removed + m.size))
                    : m.removed + m.lowerBound(id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("index", index);
            out.put("start", m.removed);
            out.put("total", m.removed + m.size);
            return out;
        }
    }

    /** Waits until the writer has put {@code id} on disk; for tests. False on timeout or without a disk. */
    boolean awaitWritten(long id, long timeoutMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (spool != null && spool.writtenUpTo < id) {
            if (System.currentTimeMillis() > end) return false;
            Thread.sleep(5);
        }
        return spool != null;
    }

    public void subscribe(Consumer<StoredEvent> subscriber) { subscribers.add(subscriber); }
    public void unsubscribe(Consumer<StoredEvent> subscriber) { subscribers.remove(subscriber); }

    @Override
    public void close() {
        if (spool != null) spool.close();
    }

    /** Events below {@code id} are gone from the disk: forget them. */
    private void dropBefore(long id) {
        if (id <= firstId) return;
        firstId = id;
        index.trim(id);
    }

    // ---- the index ----

    /**
     * Per-event columns, in chunks of {@link #SIZE} ids. Categorical fields
     * are interned strings; numbers are stored as numbers. An event whose
     * shape the index cannot mirror exactly (an unexpected type, a top-level
     * key that shadows a shorthand) is flagged, and filters read it whole.
     */
    private static final class Index {
        static final int SHIFT = 14, SIZE = 1 << SHIFT, MASK = SIZE - 1;
        static final int ABSENT = -1;
        /** An interned value that is not there; codes are 16-bit, so 65535 distinct values per store. */
        static final char NO_CODE = 0xFFFF;
        /** Times are stored as milliseconds after this, in an int: 24 days of capture. */
        private volatile long epoch = Long.MIN_VALUE;

        /** Fields mirrored as interned values (strings, booleans). */
        static final String[][] CATEGORICAL = {
            {"kind"}, {"injected"}, {"action"}, {"script"},
            {"conn", "side"}, {"frame", "dir"}, {"packet", "type"}, {"packet", "owner"}, {"packet", "state"},
            {"mod", "channel"}, {"mod", "owner"},
        };
        static final String[] LAYERS = {"conn", "frame", "packet", "mod"};
        static final Set<String> LEAVES;
        static {
            Set<String> leaves = new java.util.HashSet<>(Set.of("id", "t", "conn.id", "frame.len"));
            for (String[] p : CATEGORICAL) leaves.add(String.join(".", p));
            LEAVES = Set.copyOf(leaves);
        }

        /** True if every field the filter reads is in the index, so it can run on {@link #view}. */
        static boolean canAnswer(List<EventFilter.Ref> refs) {
            for (EventFilter.Ref r : refs) {
                String p = r.path();
                if (LEAVES.contains(p) || EventFilter.SHORTHANDS.containsKey(p)) continue;
                if (r.presence() && Set.of(LAYERS).contains(p)) continue;
                return false;
            }
            return true;
        }

        private static final class Chunk {
            final int[] t = new int[SIZE];
            final int[] connId = new int[SIZE];
            final int[] frameLen = new int[SIZE];
            /** Bits 0-3: layers present (conn, frame, packet, mod); bit 4: conn.id present; bit 7: read it whole. */
            final byte[] flags = new byte[SIZE];
            final char[][] cat = new char[CATEGORICAL.length][SIZE];
        }

        private final ConcurrentHashMap<Long, Chunk> chunks = new ConcurrentHashMap<>();
        private final Map<Object, Integer> codes = new HashMap<>();
        private volatile Object[] values = new Object[256];
        private int nextCode;

        long bytes() {
            return (long) chunks.size() * SIZE * (4 + 4 + 4 + 1 + 2L * CATEGORICAL.length);
        }

        /** The value's code, or {@link #NO_CODE} when the table is full. */
        private char code(Object v) {
            synchronized (codes) {
                Integer c = codes.get(v);
                if (c != null) return (char) (int) c;
                if (nextCode >= NO_CODE) return NO_CODE;
                if (nextCode == values.length) values = java.util.Arrays.copyOf(values, values.length * 2);
                values[nextCode] = v;
                codes.put(v, nextCode);
                return (char) nextCode++;
            }
        }

        void put(long id, Map<String, Object> e) {
            Chunk c = chunks.computeIfAbsent(id >>> SHIFT, k -> new Chunk());
            int i = (int) (id & MASK);
            boolean whole = false;
            for (String k : EventFilter.SHORTHANDS.keySet()) {
                if (e.containsKey(k)) whole = true;
            }
            int flags = 0;
            for (int l = 0; l < LAYERS.length; l++) {
                Object layer = e.get(LAYERS[l]);
                if (layer == null) continue;
                if (layer instanceof Map<?, ?>) flags |= 1 << l;
                else whole = true;
            }
            Object t = e.get("t");
            if (t instanceof Number n) {
                if (epoch == Long.MIN_VALUE) epoch = n.longValue();
                long rel = n.longValue() - epoch;
                if (rel >= Integer.MIN_VALUE && rel <= Integer.MAX_VALUE) c.t[i] = (int) rel;
                else whole = true;
            } else {
                whole = true;
            }
            Object connId = at(e, "conn", "id");
            if (connId instanceof Number n && n.doubleValue() == n.intValue()) {
                c.connId[i] = n.intValue();
                flags |= 1 << 4;
            } else if (connId != null) {
                whole = true;
            }
            Object len = at(e, "frame", "len");
            if (len instanceof Number n && n.doubleValue() == n.intValue()) c.frameLen[i] = n.intValue();
            else {
                c.frameLen[i] = ABSENT;
                if (len != null) whole = true;
            }
            for (int k = 0; k < CATEGORICAL.length; k++) {
                String[] p = CATEGORICAL[k];
                Object v = p.length == 1 ? e.get(p[0]) : at(e, p[0], p[1]);
                char code = NO_CODE;
                if (v instanceof String || v instanceof Boolean) {
                    code = code(v);
                    if (code == NO_CODE) whole = true;
                } else if (v != null) {
                    whole = true;
                }
                c.cat[k][i] = code;
            }
            if (whole) flags |= 1 << 7;
            c.flags[i] = (byte) flags;
        }

        private static Object at(Map<String, Object> e, String layer, String key) {
            return e.get(layer) instanceof Map<?, ?> m ? m.get(key) : null;
        }

        long time(long id) {
            Chunk c = chunks.get(id >>> SHIFT);
            return c == null ? 0 : epoch + c.t[(int) (id & MASK)];
        }

        int frameLen(long id) {
            Chunk c = chunks.get(id >>> SHIFT);
            return c == null ? ABSENT : c.frameLen[(int) (id & MASK)];
        }

        /** The event's indexed fields, shaped like the event; null if it must be read whole. */
        Map<String, Object> view(long id) {
            Chunk c = chunks.get(id >>> SHIFT);
            if (c == null) return null;
            int i = (int) (id & MASK);
            int flags = c.flags[i];
            if ((flags & (1 << 7)) != 0) return null;
            Map<String, Object> m = new HashMap<>();
            m.put("id", id);
            m.put("t", epoch + c.t[i]);
            Map<String, Object>[] layers = layerMaps(flags);
            for (int l = 0; l < LAYERS.length; l++) {
                if (layers[l] != null) m.put(LAYERS[l], layers[l]);
            }
            if ((flags & (1 << 4)) != 0 && layers[0] != null) layers[0].put("id", c.connId[i]);
            if (c.frameLen[i] != ABSENT && layers[1] != null) layers[1].put("len", c.frameLen[i]);
            Object[] vals = values;
            for (int k = 0; k < CATEGORICAL.length; k++) {
                char code = c.cat[k][i];
                if (code == NO_CODE) continue;
                String[] p = CATEGORICAL[k];
                if (p.length == 1) {
                    m.put(p[0], vals[code]);
                } else {
                    Map<String, Object> layer = layers[layerIndex(p[0])];
                    if (layer != null) layer.put(p[1], vals[code]);
                }
            }
            return m;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object>[] layerMaps(int flags) {
            Map<String, Object>[] out = new Map[LAYERS.length];
            for (int l = 0; l < LAYERS.length; l++) {
                if ((flags & (1 << l)) != 0) out[l] = new HashMap<>(4);
            }
            return out;
        }

        private static int layerIndex(String layer) {
            for (int l = 0; l < LAYERS.length; l++) {
                if (LAYERS[l].equals(layer)) return l;
            }
            throw new IllegalArgumentException(layer);
        }

        /** Drops chunks wholly below {@code firstId}. */
        void trim(long firstId) {
            long keep = firstId >>> SHIFT;
            chunks.keySet().removeIf(k -> k < keep);
        }
    }

    // ---- the spool: segment files on disk ----

    /**
     * Appends events to segment files of about 64 MiB, on a writer thread:
     * {@code [int json length][int frame length][json][frame]}. Each event's
     * place is kept in {@link #positions}.
     */
    private final class Spool {
        final Path dir;
        final long maxBytes;
        final LinkedBlockingQueue<StoredEvent> queue = new LinkedBlockingQueue<>(1 << 20);
        /** Segment number to its channel. */
        final Map<Integer, FileChannel> channels = new ConcurrentHashMap<>();
        /** (segment, first id) of each live segment, oldest first; guarded by itself. */
        final List<long[]> segments = new ArrayList<>();
        /** Per id: segment << 40 | offset; length in {@link #lengths}. */
        final ConcurrentHashMap<Long, long[]> positions = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, int[]> lengths = new ConcurrentHashMap<>();
        volatile long writtenUpTo;
        volatile long totalBytes;
        private int segment = -1;
        private long segmentFill;
        private final Thread writer;
        private volatile boolean closed;

        Spool(Path dir, long maxBytes) throws IOException {
            this.dir = dir.toAbsolutePath();
            this.maxBytes = maxBytes;
            Files.createDirectories(this.dir);
            Files.writeString(this.dir.resolve("pid"), String.valueOf(ProcessHandle.current().pid()));
            writer = new Thread(this::run, "sculk-capture-writer");
            writer.setDaemon(true);
            writer.start();
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "sculk-capture-cleanup"));
            Sculk.LOG.info("capturing events to {} (at most {} MiB, deleted on exit)", this.dir, maxBytes >> 20);
        }

        void enqueue(StoredEvent e) {
            if (!queue.offer(e)) Sculk.LOG.warn("capture writer is behind; event {} is kept in memory only", e.id());
        }

        private void run() {
            List<StoredEvent> batch = new ArrayList<>();
            while (!closed) {
                try {
                    batch.add(queue.take());
                    queue.drainTo(batch, 4095);
                    write(batch);
                } catch (InterruptedException e) {
                    return;
                } catch (IOException | RuntimeException e) {
                    if (!closed) Sculk.LOG.error("capture writer failed; events from {} on are in memory only", batch.isEmpty() ? "?" : batch.get(0).id(), e);
                    return;
                } finally {
                    batch.clear();
                }
            }
        }

        private void write(List<StoredEvent> batch) throws IOException {
            int i = 0;
            while (i < batch.size()) {
                if (segment < 0 || segmentFill >= EventStore.this.segmentBytes) rotate(batch.get(i).id());
                FileChannel ch = channels.get(segment);
                // As many records as fit this segment, in one write.
                List<byte[]> parts = new ArrayList<>();
                long start = segmentFill, size = 0;
                int j = i;
                for (; j < batch.size() && (j == i || start + size < EventStore.this.segmentBytes); j++) {
                    StoredEvent e = batch.get(j);
                    byte[] json = e.json().getBytes(StandardCharsets.UTF_8);
                    byte[] frame = e.frame() == null ? new byte[0] : e.frame();
                    parts.add(json);
                    parts.add(frame);
                    size += 8 + json.length + frame.length;
                }
                ByteBuffer buf = ByteBuffer.allocate((int) size);
                long offset = start;
                for (int k = i; k < j; k++) {
                    byte[] json = parts.get(2 * (k - i)), frame = parts.get(2 * (k - i) + 1);
                    buf.putInt(json.length).putInt(frame.length).put(json).put(frame);
                    setPosition(batch.get(k).id(), ((long) segment << 40) | offset, 8 + json.length + frame.length);
                    offset += 8 + json.length + frame.length;
                }
                buf.flip();
                while (buf.hasRemaining()) ch.write(buf, start + buf.position());
                segmentFill += size;
                totalBytes += size;
                enforceCap();
                writtenUpTo = batch.get(j - 1).id();
                i = j;
            }
        }

        private void setPosition(long id, long pos, int len) {
            long chunk = id >>> Index.SHIFT;
            int i = (int) (id & Index.MASK);
            positions.computeIfAbsent(chunk, k -> {
                long[] a = new long[Index.SIZE];
                java.util.Arrays.fill(a, -1);
                return a;
            })[i] = pos;
            lengths.computeIfAbsent(chunk, k -> new int[Index.SIZE])[i] = len;
        }

        private void rotate(long firstIdInSegment) throws IOException {
            segment++;
            segmentFill = 0;
            FileChannel ch = FileChannel.open(dir.resolve(String.format("segment-%06d.bin", segment)),
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
            channels.put(segment, ch);
            synchronized (segments) {
                segments.add(new long[] {segment, firstIdInSegment});
            }
        }

        /** Deletes the oldest segments while the capture is over its cap. */
        private void enforceCap() throws IOException {
            while (totalBytes > maxBytes) {
                long[] oldest, next;
                synchronized (segments) {
                    if (segments.size() < 2) return;
                    oldest = segments.remove(0);
                    next = segments.get(0);
                }
                dropBefore(next[1]);
                long keep = next[1] >>> Index.SHIFT;
                positions.keySet().removeIf(k -> k < keep);
                lengths.keySet().removeIf(k -> k < keep);
                FileChannel ch = channels.remove((int) oldest[0]);
                Path file = dir.resolve(String.format("segment-%06d.bin", (int) oldest[0]));
                long size = ch.size();
                ch.close();
                Files.deleteIfExists(file);
                totalBytes -= size;
            }
        }

        /** Reads an event back; null if it is not on disk (yet, or any more). */
        StoredEvent read(long id) {
            long[] pos = positions.get(id >>> Index.SHIFT);
            int[] len = lengths.get(id >>> Index.SHIFT);
            if (pos == null || len == null) return null;
            int i = (int) (id & Index.MASK);
            long p = pos[i];
            int n = len[i];
            if (p < 0 || n <= 0) return null;
            FileChannel ch = channels.get((int) (p >>> 40));
            if (ch == null) return null;
            ByteBuffer buf = ByteBuffer.allocate(n);
            try {
                long offset = p & ((1L << 40) - 1);
                while (buf.hasRemaining()) {
                    if (ch.read(buf, offset + buf.position()) < 0) return null;
                }
            } catch (IOException e) {
                return null; // Deleted under us: the event is gone.
            }
            buf.flip();
            int jsonLen = buf.getInt(), frameLen = buf.getInt();
            byte[] json = new byte[jsonLen];
            buf.get(json);
            byte[] frame = null;
            if (frameLen > 0) {
                frame = new byte[frameLen];
                buf.get(frame);
            }
            String line = new String(json, StandardCharsets.UTF_8);
            Map<String, Object> fields = READ.fromJson(line, MAP);
            return new StoredEvent(id, fields, line, frame);
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            writer.interrupt();
            for (FileChannel ch : channels.values()) {
                try {
                    ch.close();
                } catch (IOException ignored) {
                    // Deleting anyway.
                }
            }
            channels.clear();
            deleteCapture(dir);
        }
    }

    /** Deletes a capture directory: its segments, pid file and the directory. Nothing else. */
    static void deleteCapture(Path dir) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (name.equals("pid") || name.matches("segment-\\d+\\.bin")) Files.deleteIfExists(f);
            }
            Files.deleteIfExists(dir);
        } catch (IOException ignored) {
            // Best effort; the next launch tries again.
        }
    }

    /**
     * A new capture directory under {@code parent}, after deleting the ones
     * left by games that are no longer running.
     */
    public static Path newCaptureDir(Path parent) throws IOException {
        Files.createDirectories(parent);
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(parent, "sculk-capture-*")) {
            for (Path d : dirs) {
                Path pidFile = d.resolve("pid");
                try {
                    long pid = Long.parseLong(Files.readString(pidFile).trim());
                    if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) continue;
                } catch (IOException | NumberFormatException e) {
                    if (Files.exists(pidFile)) continue; // Unreadable: leave it be.
                }
                deleteCapture(d);
            }
        }
        return Files.createTempDirectory(parent, "sculk-capture-");
    }
}
