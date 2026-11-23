package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sculk.core.platform.ChatLine;

class ApprovalsTest {
    @TempDir
    Path dir;

    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor();
    private final List<ChatLine> chat = new ArrayList<>();

    @AfterEach
    void stop() {
        timers.shutdownNow();
    }

    private Approvals approvals() throws Exception {
        return new Approvals(dir.resolve("browsers.json"), chat::add, timers);
    }

    private static byte[] key(int b) {
        byte[] k = new byte[32];
        k[0] = (byte) b;
        return k;
    }

    @Test
    void anUnknownBrowserWaitsForThePlayer() throws Exception {
        var a = approvals();
        var decision = a.approve(key(1), "123456");
        assertFalse(decision.isDone());
        assertEquals(1, chat.size());
        String line = chat.get(0).plain();
        assertTrue(line.contains("#1") && line.contains("/sculk remote accept <code>"), line);
        assertFalse(chat.get(0).toString().contains("123456"), "the code is the page's to show, and the player's to type");
        assertThrows(IllegalArgumentException.class, () -> a.accept("654321", false), "not a code a browser shows");
        assertFalse(decision.isDone());

        a.accept("123456", false);
        assertTrue(decision.getNow(false));
        assertFalse(a.browsers().get(0).isRemembered());
        assertFalse(Files.exists(dir.resolve("browsers.json")), "nothing remembered");
    }

    @Test
    void rememberedBrowsersComeBackWithoutAsking() throws Exception {
        var a = approvals();
        var first = a.approve(key(2), "111111");
        a.accept("111111", true);
        assertTrue(first.getNow(false));
        Path file = dir.resolve("browsers.json");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));

        // The next run reads the file: no prompt, approved at once.
        chat.clear();
        var again = approvals().approve(key(2), "222222");
        assertTrue(again.getNow(false));
        assertTrue(chat.isEmpty());
    }

    @Test
    void denyingRefuses() throws Exception {
        var a = approvals();
        var decision = a.approve(key(3), "333333");
        a.deny(1);
        assertFalse(decision.getNow(true));
        assertThrows(IllegalArgumentException.class, () -> a.accept("333333", false), "no longer waiting");
    }

    @Test
    void aNewAttemptReplacesTheOldOne() throws Exception {
        var a = approvals();
        var old = a.approve(key(4), "444444");
        var fresh = a.approve(key(4), "555555");
        assertFalse(old.getNow(true), "the older attempt is refused");
        assertFalse(fresh.isDone());
        assertThrows(IllegalArgumentException.class, () -> a.accept("444444", false), "the older code is gone");
        a.accept("555555", false);
        assertTrue(fresh.getNow(false));
        assertEquals(1, a.browsers().size(), "one browser, one number");
    }

    @Test
    void twoBrowsersShowingOneCodeAreBothRefused() throws Exception {
        var a = approvals();
        var mine = a.approve(key(6), "424242");
        var other = a.approve(key(7), "424242");
        assertThrows(IllegalArgumentException.class, () -> a.accept("424242", true));
        assertFalse(mine.getNow(true));
        assertFalse(other.getNow(true));
        assertFalse(Files.exists(dir.resolve("browsers.json")), "nothing remembered");
    }

    @Test
    void aRevokeReachesAConnectionStillBeingMade() throws Exception {
        var a = approvals();
        a.approve(key(8), "888888");
        a.accept("888888", true);
        int epoch = a.epoch(key(8));
        assertTrue(a.allowed(key(8), epoch));
        a.revoke(1);
        assertFalse(a.allowed(key(8), epoch), "approved before the revoke: not let in after it");
        assertFalse(a.approve(key(8), "999999").isDone(), "and asked again");
    }

    @Test
    void revokingForgets() throws Exception {
        var a = approvals();
        a.approve(key(5), "666666");
        a.accept("666666", true);
        a.revoke(1);
        a.save();
        assertFalse(approvals().approve(key(5), "777777").isDone(), "asks again after a restart");
        assertThrows(IllegalArgumentException.class, () -> a.revoke(2), "no such browser");
    }

    @Test
    void promptsAreCapped() throws Exception {
        var a = approvals();
        for (int i = 0; i < Approvals.MAX_PROMPTS + 3; i++) a.approve(key(10 + i), "000000");
        assertEquals(Approvals.MAX_PROMPTS, chat.size());
        assertEquals(Approvals.MAX_PROMPTS + 3, a.browsers().stream().filter(Approvals.Browser::isPending).count());
    }

    @Test
    void theServerKeyPersists() throws Exception {
        Path file = dir.resolve("server.key");
        var k1 = RemoteAccess.loadOrCreateKey(file);
        var k2 = RemoteAccess.loadOrCreateKey(file);
        assertArrayEquals(X25519.raw(k1.getPublic()), X25519.raw(k2.getPublic()));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
}
