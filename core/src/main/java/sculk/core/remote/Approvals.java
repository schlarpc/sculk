package sculk.core.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import sculk.core.platform.ChatLine;

/**
 * Which browsers may connect (docs/REMOTE.md, "Approval"): the ones
 * remembered in {@code browsers.json}, and the ones waiting for the player,
 * who lets one in by typing the code its page shows. Each browser this run
 * knows of has a number, which the other {@code /sculk remote} commands use.
 */
public final class Approvals {
    /** Prompts in chat at once; more waiting browsers are only listed. */
    static final int MAX_PROMPTS = 3;
    /** A browser that is neither remembered, waiting nor connected is dropped after this. */
    private static final long IDLE_NS = TimeUnit.MINUTES.toNanos(5);

    /** A browser: its key, its number, and what it is doing. */
    public static final class Browser {
        public final int number;
        final byte[] key;
        final String keyText;
        boolean remembered;
        String rememberedAt;
        /** Set while it waits for approval. */
        CompletableFuture<Boolean> pending;
        String code;
        /** Counts its revocations: a connection approved under an older count is not let in. */
        int epoch;
        long activeNs = System.nanoTime();
        final List<WebRtc.Connection> connections = new ArrayList<>();

        Browser(int number, byte[] key) {
            this.number = number;
            this.key = key.clone();
            this.keyText = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        }

        /** A short form of the key, to tell browsers apart. */
        public String shortKey() {
            return keyText.substring(0, 8);
        }

        public boolean isPending() {
            return pending != null && !pending.isDone();
        }

        public boolean isRemembered() {
            return remembered;
        }

        public int connected() {
            return (int) connections.stream().filter(WebRtc.Connection::isConnected).count();
        }
    }

    private final Path file;
    private final Consumer<ChatLine> chat;
    private final ScheduledExecutorService timers;
    private final Map<String, Browser> browsers = new LinkedHashMap<>();
    private int nextNumber = 1;

    /**
     * @param file browsers.json: the remembered browsers
     * @param chat shows a line to the player, or the server console
     * @param timers expires waiting browsers
     */
    Approvals(Path file, Consumer<ChatLine> chat, ScheduledExecutorService timers) throws IOException {
        this.file = file;
        this.chat = chat;
        this.timers = timers;
        if (Files.isRegularFile(file)) {
            JsonObject doc = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (var e : doc.getAsJsonArray("browsers")) {
                JsonObject o = e.getAsJsonObject();
                Browser b = browser(Base64.getUrlDecoder().decode(o.get("key").getAsString()));
                b.remembered = true;
                b.rememberedAt = o.has("remembered") ? o.get("remembered").getAsString() : null;
            }
        }
    }

    /** The browsers this run knows of: remembered, waiting or connected. */
    public synchronized List<Browser> browsers() {
        return new ArrayList<>(browsers.values());
    }

    synchronized Browser browser(byte[] key) {
        String k = Base64.getUrlEncoder().withoutPadding().encodeToString(key);
        return browsers.computeIfAbsent(k, x -> new Browser(nextNumber++, key));
    }

    private synchronized Browser byNumber(int number) {
        for (Browser b : browsers.values()) if (b.number == number) return b;
        throw new IllegalArgumentException("no browser #" + number + "; /sculk remote list shows them");
    }

    /**
     * Whether a browser whose handshake completed may connect: a remembered
     * one at once, any other once the player decides, or not if nobody does
     * within {@link Signaling#APPROVAL_MS}. ({@link GameSignaling.Approver})
     */
    synchronized CompletableFuture<Boolean> approve(byte[] browserKey, String code) {
        long now = System.nanoTime();
        browsers.values().removeIf(x -> !x.remembered && !x.isPending() && x.connections.isEmpty() && now - x.activeNs > IDLE_NS);
        Browser b = browser(browserKey);
        b.activeNs = now;
        if (b.remembered) return CompletableFuture.completedFuture(true);
        if (b.isPending()) b.pending.complete(false); // an older attempt from the same browser
        CompletableFuture<Boolean> mine = new CompletableFuture<>();
        b.pending = mine;
        b.code = code;
        timers.schedule(() -> mine.complete(false), Signaling.APPROVAL_MS, TimeUnit.MILLISECONDS);
        long waiting = browsers.values().stream().filter(Browser::isPending).count();
        if (waiting <= MAX_PROMPTS) {
            // Not the code: the player types what their own page shows, so a
            // browser they are not looking at cannot be the one let in.
            chat.accept(ChatLine.of("Browser #" + b.number + " wants remote access, which lets it run code in this game."
                            + " If it is yours, type the code its page shows: /sculk remote accept <code>"
                            + " (then \"remember\" to let it back in next time) ")
                    .button("deny", "/sculk remote deny " + b.number, "Refuse it"));
        }
        return mine;
    }

    /**
     * Approves the waiting browser whose page shows {@code code}; with
     * {@code remember}, it comes back without asking. Two waiting browsers
     * with one code are both refused: one of them may have been made to match.
     */
    public synchronized Browser accept(String code, boolean remember) throws IOException {
        List<Browser> shown = browsers.values().stream().filter(b -> b.isPending() && b.code.equals(code)).toList();
        if (shown.isEmpty()) {
            throw new IllegalArgumentException("no waiting browser shows " + code + "; the code is on the browser's page, and lasts a minute");
        }
        if (shown.size() > 1) {
            shown.forEach(b -> b.pending.complete(false));
            throw new IllegalArgumentException(shown.size() + " waiting browsers show " + code + ", so both are refused; reload the page for a new code");
        }
        Browser b = shown.get(0);
        if (remember) {
            b.remembered = true;
            b.rememberedAt = Instant.now().toString();
            try {
                save();
            } catch (IOException | RuntimeException e) {
                b.remembered = false;
                b.rememberedAt = null;
                throw e;
            }
        }
        b.activeNs = System.nanoTime();
        b.pending.complete(true);
        return b;
    }

    public synchronized Browser deny(int number) {
        Browser b = byNumber(number);
        if (!b.isPending()) throw new IllegalArgumentException("browser #" + number + " is not waiting");
        b.pending.complete(false);
        return b;
    }

    /**
     * Forgets a browser, for this run at least: it is no longer remembered,
     * and a connection of its that is still being made will not be let in.
     * Returns its connections, for the caller to close; {@link #save} then
     * makes it last.
     */
    public synchronized List<WebRtc.Connection> revoke(int number) {
        Browser b = byNumber(number);
        b.remembered = false;
        b.rememberedAt = null;
        b.epoch++;
        if (b.isPending()) b.pending.complete(false);
        return new ArrayList<>(b.connections);
    }

    /** What {@link #allowed} must be given later: taken when a browser is approved. */
    synchronized int epoch(byte[] key) {
        return browser(key).epoch;
    }

    /** Whether a browser approved at {@code epoch} has not been revoked since. */
    synchronized boolean allowed(byte[] key, int epoch) {
        return browser(key).epoch == epoch;
    }

    synchronized void connected(byte[] key, WebRtc.Connection c) {
        browser(key).connections.add(c);
    }

    synchronized void disconnected(byte[] key, WebRtc.Connection c) {
        Browser b = browser(key);
        b.connections.remove(c);
        b.activeNs = System.nanoTime();
    }

    /** Writes the remembered browsers to {@code browsers.json}. */
    public synchronized void save() throws IOException {
        JsonArray list = new JsonArray();
        for (Browser b : browsers.values()) {
            if (!b.remembered) continue;
            JsonObject o = new JsonObject();
            o.addProperty("key", b.keyText);
            if (b.rememberedAt != null) o.addProperty("remembered", b.rememberedAt);
            list.add(o);
        }
        JsonObject doc = new JsonObject();
        doc.add("browsers", list);
        writeOwnerOnly(file, doc + "\n");
    }

    /** Writes {@code text} to {@code file}, readable by the owner only, atomically. */
    static void writeOwnerOnly(Path file, String text) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            tmp.toFile().setReadable(false, false);
            tmp.toFile().setReadable(true, true);
            tmp.toFile().setWritable(false, false);
            tmp.toFile().setWritable(true, true);
        }
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
