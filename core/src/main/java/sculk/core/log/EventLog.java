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

    /** Serialises now (on the caller's thread, so values are captured) and writes later. */
    public void write(Map<String, Object> entry) {
        String line;
        try {
            line = GSON.toJson(entry);
        } catch (RuntimeException e) {
            line = GSON.toJson(Map.of("kind", "log_error", "t", System.currentTimeMillis(), "error", e.toString()));
        }
        String finalLine = line;
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
