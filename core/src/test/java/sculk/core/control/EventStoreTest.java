package sculk.core.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sculk.core.log.EventLog;

class EventStoreTest {
    private static final long T0 = 1_700_000_000_000L;

    /** Events shaped like the engine's: packets with and without mod layers, script events, and odd ones. */
    private static List<StoredEvent> events(int n, long seed) {
        Random r = new Random(seed);
        List<StoredEvent> out = new ArrayList<>();
        String[] types = {"move", "chat", "custom_payload", "keep_alive"};
        String[] channels = {"mod1:sync", "mod2:open", "othermod:x", "minecraft:brand"};
        for (int i = 1; i <= n; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", (long) i);
            int shape = r.nextInt(20);
            if (shape < 14) {
                e.put("kind", "packet");
                e.put("t", T0 + i * 7L);
                e.put("injected", r.nextInt(10) == 0);
                e.put("action", r.nextInt(8) == 0 ? "drop" : "pass");
                e.put("conn", new LinkedHashMap<>(Map.of("id", 1 + r.nextInt(3), "side", r.nextBoolean() ? "client" : "server",
                        "remote", "/127.0.0.1:1")));
                int len = 1 + r.nextInt(120);
                byte[] frame = new byte[len];
                r.nextBytes(frame);
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("seq", (long) i);
                f.put("dir", r.nextBoolean() ? "inbound" : "outbound");
                f.put("len", len);
                f.put("hex", sculk.core.util.Bytes.hex(frame));
                e.put("frame", f);
                boolean mod = r.nextInt(3) == 0;
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("id", r.nextInt(100));
                p.put("state", r.nextInt(5) == 0 ? "configuration" : "play");
                p.put("type", mod ? "custom_payload" : types[r.nextInt(types.length)]);
                p.put("owner", "minecraft");
                e.put("packet", p);
                if (mod) {
                    String channel = channels[r.nextInt(channels.length)];
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("channel", channel);
                    m.put("owner", channel.substring(0, channel.indexOf(':')));
                    p.put("data", new LinkedHashMap<>(Map.of("slot", r.nextInt(10), "name", "n" + r.nextInt(5))));
                    e.put("mod", m);
                }
                out.add(new StoredEvent(i, e, EventLog.line(e), frame));
                continue;
            }
            e.put("t", T0 + i * 7L);
            if (shape < 17) {
                e.put("kind", "script");
                e.put("script", r.nextBoolean() ? "a.js" : "b c.js");
                e.put("data", new LinkedHashMap<>(Map.of("n", r.nextInt(20), "step", "s" + r.nextInt(3))));
            } else if (shape == 17) {
                e.put("kind", "script_file");
                e.put("script", "a.js");
                e.put("action", "save");
            } else if (shape == 18) {
                // A top-level key that a shorthand would otherwise stand for.
                e.put("kind", "weird");
                e.put("owner", "weird");
                e.put("dir", "sideways");
            } else {
                // Values of types the index does not mirror.
                e.put("kind", "odd");
                e.put("conn", new LinkedHashMap<>(Map.of("id", "not-a-number", "side", "client")));
                e.put("injected", 3);
            }
            out.add(new StoredEvent(i, e, EventLog.line(e), null));
        }
        return out;
    }

    private static final List<String> FILTERS = List.of(
            "", "kind == packet", "dir == inbound && owner != minecraft", "channel matches \"^mod[12]:\"", "len > 50",
            "side == client", "injected", "!injected && kind == packet", "action == drop", "script == a.js",
            "script == \"b c.js\"", "mod", "!mod", "owner == weird", "dir == sideways", "conn.id == 2", "conn.id == not-a-number",
            "packet.data.slot >= 5", "frame.hex contains ab", "data.n < 10 || type == move", "t >= " + (T0 + 7000),
            "id > 100 && id < 200", "packet.state == play", "mod contains slot", "frame", "kind == odd || injected == 3",
            "type == custom_payload && !(channel ~ other)");

    private static Map<String, Object> parse(String json) {
        return new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create()
                .fromJson(json, new TypeToken<Map<String, Object>>() {}.getType());
    }

    private static List<Long> expected(List<StoredEvent> all, String filter) {
        Predicate<Map<String, Object>> p = EventFilter.compile(filter);
        List<Long> out = new ArrayList<>();
        for (StoredEvent e : all) {
            if (p.test(parse(e.json()))) out.add(e.id());
        }
        return out;
    }

    private static List<Long> ids(List<StoredEvent> events) {
        return events.stream().map(StoredEvent::id).toList();
    }

    @Test
    void indexAndDiskAnswerEveryFilterExactlyAsTheWholeEventsWould(@TempDir Path tmp) throws Exception {
        List<StoredEvent> all = events(3000, 1);
        // A tiny hot cache, so nearly everything is read back from disk.
        try (EventStore store = new EventStore(64, 1 << 20, tmp.resolve("cap"), 1L << 30, 64 << 10)) {
            for (StoredEvent e : all) store.add(e);
            assertTrue(store.awaitWritten(3000, 10_000));
            for (String f : FILTERS) {
                List<Long> want = expected(all, f);
                assertEquals(want, ids(store.query(f, 0L, null, Integer.MAX_VALUE, EventStore.Progress.NONE)), f);
                // The last few, and paging backwards from the middle.
                List<Long> tail = want.subList(Math.max(0, want.size() - 7), want.size());
                assertEquals(tail, ids(store.query(f, null, null, 7, EventStore.Progress.NONE)), "last 7 of " + f);
                List<Long> before1500 = want.stream().filter(id -> id < 1500).toList();
                assertEquals(before1500.subList(Math.max(0, before1500.size() - 5), before1500.size()),
                        ids(store.query(f, null, 1500L, 5, EventStore.Progress.NONE)), "5 before 1500 of " + f);
            }
        }
    }

    @Test
    void eventsReadBackFromDiskAreTheEventsWritten(@TempDir Path tmp) throws Exception {
        List<StoredEvent> all = events(500, 2);
        try (EventStore store = new EventStore(16, 1 << 20, tmp.resolve("cap"), 1L << 30, 16 << 10)) {
            for (StoredEvent e : all) store.add(e);
            assertTrue(store.awaitWritten(500, 10_000));
            for (StoredEvent e : all) {
                StoredEvent back = store.get(e.id());
                assertNotNull(back, "event " + e.id());
                assertEquals(e.json(), back.json());
                assertArrayEquals(e.frame(), back.frame());
                if (back != e) assertEquals(parse(e.json()), back.fields()); // else still in memory, as added
            }
            assertNull(store.get(0));
            assertNull(store.get(501));
        }
    }

    @Test
    void blocksAreCompressedAndReadBackWhole(@TempDir Path tmp) throws Exception {
        List<StoredEvent> all = events(20_000, 3);
        // A ring big enough that blocks fill by size, as in a game: many events to a block.
        try (EventStore store = new EventStore(4096, 1 << 20, tmp.resolve("cap"), 1L << 30, 8 << 20)) {
            for (StoredEvent e : all) store.add(e);
            assertTrue(store.awaitWritten(20_000, 10_000));
            Map<String, Object> d = store.describe();
            long disk = ((Number) d.get("diskBytes")).longValue(), raw = ((Number) d.get("diskBytesUncompressed")).longValue();
            assertTrue(disk * 2 < raw, "compressed (the test frames are random bytes, so not much): " + disk + " of " + raw);
            for (int id : new int[] {1, 2, 9_999, 10_000, 19_999, 20_000, 777}) {
                StoredEvent back = store.get(id);
                assertNotNull(back, "event " + id);
                assertEquals(all.get(id - 1).json(), back.json());
                assertArrayEquals(all.get(id - 1).frame(), back.frame());
            }
        }
    }

    @Test
    void aFailedWriterLeavesAStoreOfBoundedMemoryAndNoFiles(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("cap");
        List<StoredEvent> first = events(20_000, 4);
        int total = 200_000;
        try (EventStore store = new EventStore(32, 1 << 20, dir, 1L << 30, 16 << 10)) {
            for (int i = 0; i < 500; i++) store.add(first.get(i));
            assertTrue(store.awaitWritten(500, 10_000));
            // The next segment cannot be made: the writer fails, as on a full disk.
            EventStore.deleteCapture(dir);
            for (int i = 500; i < 20_000; i++) store.add(first.get(i));
            long end = System.currentTimeMillis() + 10_000;
            while (!Boolean.TRUE.equals(store.describe().get("captureFailed")) && System.currentTimeMillis() < end) Thread.sleep(5);
            assertEquals(true, store.describe().get("captureFailed"));
            for (long id = 20_001; id <= total; id++) {
                store.add(new StoredEvent(id, Map.of("id", id, "kind", "mark"), "{\"id\":" + id + ",\"kind\":\"mark\"}", null));
            }
            assertEquals(total, store.lastId());
            assertTrue(store.size() <= 64, "the hot ring alone: " + store.size());
            long indexBytes = ((Number) store.describe().get("indexBytes")).longValue();
            assertTrue(indexBytes < 1 << 20, "index trimmed with the ring: " + indexBytes);
            assertNotNull(store.get(total));
            assertEquals(0, (int) store.describe().get("pendingWrites"));
            assertFalse(Files.exists(dir), "capture files deleted");
        }
    }

    @Test
    void theDiskCapDropsTheOldestSegments(@TempDir Path tmp) throws Exception {
        List<StoredEvent> all = events(4000, 3);
        Path dir = tmp.resolve("cap");
        try (EventStore store = new EventStore(32, 1 << 20, dir, 64 << 10, 16 << 10)) {
            for (StoredEvent e : all) store.add(e);
            assertTrue(store.awaitWritten(4000, 10_000));
            long first = store.firstId();
            assertTrue(first > 1, "old events dropped");
            assertEquals(4000, store.lastId());
            assertNull(store.get(1));
            assertNotNull(store.get(first));
            long diskBytes = (long) store.describe().get("diskBytes");
            assertTrue(diskBytes <= (64 << 10) + (16 << 10) + 4096, "within the cap and a segment: " + diskBytes);
            try (Stream<Path> files = Files.list(dir)) {
                assertTrue(files.count() <= 7, "few segment files");
            }
            List<Long> want = expected(all, "kind == packet").stream().filter(id -> id >= first).toList();
            assertEquals(want, ids(store.query("kind == packet", 0L, null, Integer.MAX_VALUE, EventStore.Progress.NONE)));
        }
        assertFalse(Files.exists(dir), "closing deletes the capture");
    }

    @Test
    void withoutADiskItIsARingOfRecentEvents() {
        List<StoredEvent> all = events(1000, 4);
        EventStore store = new EventStore(100, 1 << 20);
        for (StoredEvent e : all) store.add(e);
        assertEquals(100, store.size());
        assertEquals(901, store.firstId());
        assertNull(store.get(900));
        assertNotNull(store.get(901));
        List<Long> want = expected(all, "mod").stream().filter(id -> id >= 901).toList();
        assertEquals(want, ids(store.query("mod", 0L, null, 1000, EventStore.Progress.NONE)));
    }

    @Test
    void statsGroupCountAndBound(@TempDir Path tmp) throws Exception {
        List<StoredEvent> all = events(3000, 5);
        try (EventStore store = new EventStore(64, 1 << 20, tmp.resolve("cap"), 1L << 30, 64 << 10)) {
            for (StoredEvent e : all) store.add(e);
            assertTrue(store.awaitWritten(3000, 10_000));
            for (String by : List.of("channel", "packet.data.name")) {
                Map<String, Object> stats = store.stats("mod", by, null, null, 100, EventStore.Progress.NONE);
                Map<String, long[]> want = new LinkedHashMap<>();
                for (StoredEvent e : all) {
                    Map<String, Object> f = parse(e.json());
                    if (!EventFilter.compile("mod").test(f)) continue;
                    String key = String.valueOf(EventFilter.value(f, by));
                    long[] g = want.computeIfAbsent(key, k -> new long[] {0, 0, e.id(), 0});
                    g[0]++;
                    g[1] += ((Number) ((Map<?, ?>) f.get("frame")).get("len")).longValue();
                    g[3] = e.id();
                }
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> groups = (List<Map<String, Object>>) stats.get("groups");
                assertEquals(want.size(), groups.size(), by);
                for (Map<String, Object> g : groups) {
                    long[] w = want.get((String) g.get("value"));
                    assertNotNull(w, by + " " + g);
                    assertEquals(w[0], g.get("count"), by + " " + g);
                    assertEquals(w[1], g.get("bytes"), by + " " + g);
                    assertEquals(w[2], g.get("firstId"), by + " " + g);
                    assertEquals(w[3], g.get("lastId"), by + " " + g);
                }
            }
            Map<String, Object> everything = store.stats("", null, null, null, 10, EventStore.Progress.NONE);
            assertEquals(3000L, everything.get("matched"));

            // Indexed filters and groupings never touch the disk; others must.
            long before = store.diskReads.get();
            store.stats("kind == packet && dir == inbound && channel ~ mod", "owner", null, null, 10, EventStore.Progress.NONE);
            store.stats("script == a.js || side == client", "conn.id", null, null, 10, EventStore.Progress.NONE);
            // Except the events whose shape the index cannot mirror (the weird and odd ones), read whole each time.
            // (Those still in memory are not read back from disk.)
            long hotFirst = store.lastId() - ((Number) store.describe().get("memoryEvents")).longValue() + 1;
            long unindexable = all.stream().filter(e -> e.id() < hotFirst)
                    .filter(e -> Stream.of("weird", "odd").anyMatch(k -> k.equals(e.fields().get("kind")))).count();
            assertEquals(before + 2 * unindexable, store.diskReads.get(), "the index answered the rest");
            store.stats("packet.data.slot >= 5", null, null, null, 10, EventStore.Progress.NONE);
            assertTrue(store.diskReads.get() > before, "deep fields read events back");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Long> pageIds(Map<String, Object> page) {
        return ((List<StoredEvent>) page.get("events")).stream().map(StoredEvent::id).toList();
    }

    @Test
    void pagesAndPositionsStayPutAsTheCaptureGrowsAndShrinks(@TempDir Path tmp) throws Exception {
        List<StoredEvent> all = events(6000, 7);
        try (EventStore store = new EventStore(32, 1 << 20, tmp.resolve("cap"), 256 << 10, 32 << 10)) {
            for (StoredEvent e : all.subList(0, 300)) store.add(e);
            assertTrue(store.awaitWritten(300, 10_000));
            for (String f : List.of("", "mod", "frame.hex contains ab")) {
                List<Long> want = expected(all.subList(0, 300), f);
                Map<String, Object> page = store.page(f, 3, 10, EventStore.Progress.NONE);
                assertEquals(0L, page.get("start"), f);
                assertEquals((long) want.size(), page.get("total"), f);
                assertEquals(want.subList(3, Math.min(13, want.size())), pageIds(page), f);
                long id = want.get(5);
                assertEquals(5L, store.position(f, id, EventStore.Progress.NONE).get("index"), f);
                assertEquals(5L, store.position(f, id - (f.isEmpty() ? 0 : 1) , EventStore.Progress.NONE).get("index"), f + " (first at or after)");
            }

            // More events: positions keep their numbers; old ones fall off the front as segments go.
            Map<String, Object> before = store.page("mod", 0, 1, EventStore.Progress.NONE);
            long firstModId = pageIds(before).get(0);
            for (StoredEvent e : all.subList(300, 6000)) store.add(e);
            assertTrue(store.awaitWritten(6000, 10_000));
            assertTrue(store.firstId() > firstModId, "the first mod event was dropped");
            long held = store.firstId();
            List<Long> want = expected(all, "mod").stream().filter(id -> id >= held).toList();
            Map<String, Object> gone = store.page("mod", 0, 5, EventStore.Progress.NONE);
            assertEquals(List.of(), pageIds(gone), "rows 0-4 are no longer held");
            long start = (long) gone.get("start");
            assertTrue(start > 0, "dropped matches still count, so held rows keep their numbers");
            Map<String, Object> page = store.page("mod", start, 5, EventStore.Progress.NONE);
            assertEquals((long) want.size(), (long) page.get("total") - start, "the matches held");
            assertEquals(start, page.get("from"));
            assertEquals(want.subList(0, 5), pageIds(page));
            assertEquals(start + 3, store.position("mod", want.get(3), EventStore.Progress.NONE).get("index"));

            // Rows seen keep their positions when more arrive.
            long rowOfTenth = (long) store.position("mod", want.get(10), EventStore.Progress.NONE).get("index");
            for (StoredEvent e : events(1, 8)) {
                Map<String, Object> f = new LinkedHashMap<>(e.fields());
                f.put("id", 6001L);
                store.add(new StoredEvent(6001, f, EventLog.line(f), e.frame()));
            }
            assertEquals(rowOfTenth, store.position("mod", want.get(10), EventStore.Progress.NONE).get("index"));
            Map<String, Object> everything = store.page("", store.firstId() - 1, 3, EventStore.Progress.NONE);
            assertEquals(store.firstId() - 1, everything.get("start"));
            assertEquals(List.of(store.firstId(), store.firstId() + 1, store.firstId() + 2), pageIds(everything));
            assertEquals(6001L, everything.get("total"));
        }
    }

    @Test
    void aCancelledMatchScanKeepsOnlyWholeChunks() {
        List<StoredEvent> all = events(40_000, 9);
        EventStore store = new EventStore(50_000, 1L << 30);
        store.matchChunk = 5_000;
        for (StoredEvent e : all) store.add(e);
        String filter = "frame.hex contains ab"; // not indexed: the slow kind of scan
        long[] seen = {0};
        EventStore.Progress quitting = new EventStore.Progress() {
            @Override
            public void tick(long done, long total) {
                seen[0] = done;
            }

            @Override
            public boolean cancelled() {
                return seen[0] > 17_000; // partway into the fourth chunk
            }
        };
        assertThrows(EventStore.Cancelled.class, () -> store.page(filter, 0, 10, quitting));
        // Picking up where it stopped: no match counted twice, none missed.
        List<Long> want = expected(all, filter);
        Map<String, Object> page = store.page(filter, 0, Integer.MAX_VALUE, EventStore.Progress.NONE);
        assertEquals((long) want.size(), page.get("total"));
        assertEquals(want, pageIds(page));
    }

    /** A scan made slow: 100 ms per chunk of 5000, so others have to wait for it. */
    private static EventStore.Progress slow(java.util.function.BooleanSupplier cancel) {
        return new EventStore.Progress() {
            @Override
            public void tick(long done, long total) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public boolean cancelled() {
                return cancel.getAsBoolean();
            }
        };
    }

    private static EventStore slowStore(List<StoredEvent> all) {
        EventStore store = new EventStore(50_000, 1L << 30);
        store.matchChunk = 5_000;
        for (StoredEvent e : all) store.add(e);
        return store;
    }

    @Test
    void aSecondCallerForTheSameFilterReportsTheFirstOnesProgressWhileItWaits() throws Exception {
        List<StoredEvent> all = events(40_000, 10);
        EventStore store = slowStore(all);
        String filter = "frame.hex contains ab";
        var builder = java.util.concurrent.CompletableFuture.supplyAsync(() -> store.page(filter, 0, 5, slow(() -> false)));
        Thread.sleep(150);
        List<long[]> ticks = new java.util.concurrent.CopyOnWriteArrayList<>();
        Map<String, Object> waited = store.page(filter, 0, 5, new EventStore.Progress() {
            @Override
            public void tick(long done, long total) {
                ticks.add(new long[] {done, total});
            }
        });
        assertTrue(ticks.size() >= 2, "ticks while waiting: " + ticks.size());
        assertTrue(ticks.stream().allMatch(t -> t[1] == 40_000 && t[0] > 0 && t[0] <= 40_000), "the builder's progress");
        List<Long> want = expected(all, filter);
        assertEquals((long) want.size(), waited.get("total"));
        assertEquals(want.subList(0, 5), pageIds(waited));
        assertEquals(want.subList(0, 5), pageIds(builder.get(5, java.util.concurrent.TimeUnit.SECONDS)));
    }

    @Test
    void aWaitingCallerThatGivesUpLeavesTheBuilderToFinish() throws Exception {
        List<StoredEvent> all = events(40_000, 11);
        EventStore store = slowStore(all);
        String filter = "frame.hex contains cd";
        var builder = java.util.concurrent.CompletableFuture.supplyAsync(() -> store.page(filter, 0, 5, slow(() -> false)));
        Thread.sleep(150);
        long until = System.currentTimeMillis() + 250;
        long t0 = System.nanoTime();
        assertThrows(EventStore.Cancelled.class, () -> store.page(filter, 0, 5, new EventStore.Progress() {
            @Override
            public boolean cancelled() {
                return System.currentTimeMillis() > until;
            }
        }));
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 600, "gave up promptly, not after the scan");
        assertEquals((long) expected(all, filter).size(), builder.get(5, java.util.concurrent.TimeUnit.SECONDS).get("total"));
    }

    @Test
    void whenTheBuilderGivesUpTheWaiterCarriesOnFromItsFinishedChunks() throws Exception {
        List<StoredEvent> all = events(40_000, 12);
        EventStore store = slowStore(all);
        String filter = "frame.hex contains ef";
        long until = System.currentTimeMillis() + 350; // three chunks or so
        var builder = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> store.page(filter, 0, 5, slow(() -> System.currentTimeMillis() > until)));
        Thread.sleep(100);
        Map<String, Object> waited = store.page(filter, 0, Integer.MAX_VALUE, EventStore.Progress.NONE);
        var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> builder.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof EventStore.Cancelled, String.valueOf(failure.getCause()));
        List<Long> want = expected(all, filter);
        assertEquals((long) want.size(), waited.get("total"), "nothing counted twice or missed");
        assertEquals(want, pageIds(waited));
    }

    @Test
    void scansReportProgressAndStopWhenCancelled() {
        EventStore store = new EventStore(50_000, 1L << 30);
        for (StoredEvent e : events(40_000, 6)) store.add(e);
        long[] ticks = {0};
        EventStore.Progress counting = new EventStore.Progress() {
            @Override
            public void tick(long done, long total) {
                ticks[0]++;
                assertEquals(40_000, total);
            }
        };
        store.query("frame.hex contains zz", 0L, null, 10, counting);
        assertTrue(ticks[0] >= 4, "ticks: " + ticks[0]);
        EventStore.Progress cancelled = new EventStore.Progress() {
            @Override
            public boolean cancelled() {
                return true;
            }
        };
        assertThrows(EventStore.Cancelled.class, () -> store.query("frame.hex contains zz", 0L, null, 10, cancelled));
        assertThrows(EventStore.Cancelled.class, () -> store.stats("", "kind", null, null, 10, cancelled));
    }

    @Test
    void staleCapturesAreCleanedUpButLiveOnesAndStrangersAreNot(@TempDir Path tmp) throws Exception {
        Path dead = Files.createDirectory(tmp.resolve("sculk-capture-dead"));
        Files.writeString(dead.resolve("pid"), "2147483646");
        Files.write(dead.resolve("segment-000000.bin"), new byte[10]);
        Path deadWithStranger = Files.createDirectory(tmp.resolve("sculk-capture-mixed"));
        Files.writeString(deadWithStranger.resolve("pid"), "2147483646");
        Files.writeString(deadWithStranger.resolve("notes.txt"), "mine");
        Path live = Files.createDirectory(tmp.resolve("sculk-capture-live"));
        Files.writeString(live.resolve("pid"), String.valueOf(ProcessHandle.current().pid()));
        Path unrelated = Files.createDirectory(tmp.resolve("other"));

        Path fresh = EventStore.newCaptureDir(tmp);
        assertFalse(Files.exists(dead));
        assertTrue(Files.exists(deadWithStranger.resolve("notes.txt")), "only our files are deleted");
        assertFalse(Files.exists(deadWithStranger.resolve("pid")));
        assertTrue(Files.exists(live.resolve("pid")));
        assertTrue(Files.exists(unrelated));
        assertTrue(Files.isDirectory(fresh) && fresh.getFileName().toString().startsWith("sculk-capture-"));
    }
}
