package sculk.core.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The Nostr relays remote access uses (docs/REMOTE.md). The shell publishes
 * the list at {@link #SOURCE}, the same one it is built with, so a game that
 * fetches it meets browsers on the same relays. The last good copy is cached
 * on disk, and the copy baked into the mod at build time is the fallback for
 * a game that has never reached the site.
 *
 * <p>Fetching is the only thing remote access sends anywhere before a
 * browser connects, and it tells GitHub Pages this server's address.
 */
public final class RelayList {
    public static final URI SOURCE = URI.create("https://sculk-ui.github.io/relays.json");
    /** The copy baked in at build time (the root project's relays task). */
    static final String BAKED = "/sculk/remote/relays.json";
    /** As in the shell's relays.nix. */
    static final int MIN_RELAYS = 20;
    /** The list has about 30; one far longer is not the shell's. */
    static final int MAX_RELAYS = 200;
    /** Plenty for {@link #MAX_RELAYS} URLs; the fetch reads no more than this. */
    static final int MAX_BYTES = 64 * 1024;

    private static final Pattern URL = Pattern.compile(
            "wss://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:[0-9]+)?/?");
    /**
     * A host a URL parser reads as an IPv4 address: its last label is a
     * number, decimal or 0x hex. Relays have names; an address in the list
     * would be someone pointing the game at a machine, perhaps on its own network.
     */
    private static final Pattern NUMERIC_HOST = Pattern.compile("wss://[^:/]*\\.([0-9]+|0x[0-9a-f]*)(:[0-9]+)?/?");

    private final Path cacheFile;
    private final URI source;
    private final HttpClient http;
    private volatile List<String> current;

    /** Loads the cached copy if there is a good one, else the baked one. */
    public RelayList(Path cacheFile) {
        this(cacheFile, SOURCE, baked());
    }

    RelayList(Path cacheFile, URI source, List<String> baked) {
        this.cacheFile = cacheFile;
        this.source = source;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        List<String> cached = null;
        try {
            if (Files.isRegularFile(cacheFile)) cached = parse(Files.readString(cacheFile));
        } catch (IOException | IllegalArgumentException e) {
            // A damaged cache is as good as none; the next refresh replaces it.
        }
        this.current = cached != null ? cached : baked;
    }

    /** The relays to use now. */
    public List<String> current() {
        return current;
    }

    /**
     * Fetches the published list, and on success uses and caches it.
     *
     * @throws IOException if it cannot be fetched or is not a good list; the
     *     current one stays
     */
    public void refresh() throws IOException {
        var request = HttpRequest.newBuilder(source)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "sculk")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        String body;
        // Read to a limit, whatever the server says or sends: closing the
        // stream early drops the rest of the response.
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) throw new IOException(source + ": HTTP " + response.statusCode());
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException(source + ": over " + MAX_BYTES + " bytes");
            body = new String(bytes, StandardCharsets.UTF_8);
        }
        List<String> fetched;
        try {
            fetched = parse(body);
        } catch (IllegalArgumentException e) {
            throw new IOException(source + ": " + e.getMessage(), e);
        }
        current = fetched;
        Files.createDirectories(cacheFile.getParent());
        Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        Files.writeString(tmp, body, StandardCharsets.UTF_8);
        Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The list in a relays.json, checked as the shell's relays.nix checks it. */
    static List<String> parse(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not JSON");
        }
        if (!root.isJsonObject() || !((JsonObject) root).has("relays") || !((JsonObject) root).get("relays").isJsonArray()) {
            throw new IllegalArgumentException("no relays array");
        }
        JsonArray array = ((JsonObject) root).getAsJsonArray("relays");
        List<String> out = new ArrayList<>();
        var seen = new HashSet<String>();
        if (array.size() > MAX_RELAYS) throw new IllegalArgumentException(array.size() + " relays");
        for (JsonElement e : array) {
            String url = e.isJsonPrimitive() ? e.getAsString() : "";
            if (!URL.matcher(url).matches()) throw new IllegalArgumentException("not a plain wss:// URL: " + url);
            if (NUMERIC_HOST.matcher(url).matches()) throw new IllegalArgumentException("an address, not a name: " + url);
            if (!seen.add(url)) throw new IllegalArgumentException("duplicate: " + url);
            out.add(url);
        }
        if (out.size() < MIN_RELAYS) throw new IllegalArgumentException("only " + out.size() + " relays");
        return List.copyOf(out);
    }

    static List<String> baked() {
        try (InputStream in = RelayList.class.getResourceAsStream(BAKED)) {
            if (in == null) throw new IllegalStateException(BAKED + " missing from the mod");
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(BAKED + ": " + e.getMessage(), e);
        }
    }
}
