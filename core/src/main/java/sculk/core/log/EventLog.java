package sculk.core.log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import sculk.core.Sculk;

/**
 * Append-only JSON Lines record of everything sculk sees. One object per line,
 * always with {@code kind} and {@code t} (epoch millis). Writes happen on a
 * background thread and are flushed per line, so tests can tail the file.
 */
public final class EventLog implements AutoCloseable {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private final BufferedWriter writer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sculk-eventlog");
        t.setDaemon(true);
        return t;
    });

    public EventLog(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        writer = Files.newBufferedWriter(
                path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    public static Map<String, Object> entry(String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("t", System.currentTimeMillis());
        return m;
    }

    /** Serialises {@code value} as compact JSON, the way the event log writes it. */
    public static String toJson(Object value) {
        return GSON.toJson(value);
    }

    /**
     * Serialises an entry as one JSON line. An entry that cannot be serialised
     * becomes a {@code log_error} entry rather than an exception.
     */
    public static String line(Map<String, Object> entry) {
        try {
            return GSON.toJson(entry);
        } catch (RuntimeException e) {
            Map<String, Object> error = entry("log_error");
            if (entry.containsKey("id")) error.put("id", entry.get("id"));
            error.put("error", e.toString());
            return GSON.toJson(error);
        }
    }

    /** Serialises now (on the caller's thread, so values are captured) and writes later. */
    public void write(Map<String, Object> entry) {
        writeLine(line(entry));
    }

    /** Writes an already serialised line later, in call order. */
    public void writeLine(String finalLine) {
        executor.execute(() -> {
            try {
                writer.write(finalLine);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                Sculk.LOG.warn("event log write failed", e);
            }
        });
    }

    @Override
    public void close() throws IOException {
        executor.shutdown();
        writer.close();
    }
}
