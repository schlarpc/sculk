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
        assertTrue(line.contains("#1") && line.contains("123456"), line);
        assertTrue(chat.get(0).toString().contains("/sculk remote accept 1"), "has an accept button");

        a.accept(1, false);
        assertTrue(decision.getNow(false));
        assertFalse(a.browsers().get(0).isRemembered());
        assertFalse(Files.exists(dir.resolve("browsers.json")), "nothing remembered");
    }

    @Test
    void rememberedBrowsersComeBackWithoutAsking() throws Exception {
        var a = approvals();
        var first = a.approve(key(2), "111111");
        a.accept(1, true);
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
        assertThrows(IllegalArgumentException.class, () -> a.accept(1, false), "no longer waiting");
    }

    @Test
    void aNewAttemptReplacesTheOldOne() throws Exception {
        var a = approvals();
        var old = a.approve(key(4), "444444");
        var fresh = a.approve(key(4), "555555");
        assertFalse(old.getNow(true), "the older attempt is refused");
        assertFalse(fresh.isDone());
        assertEquals("555555", a.browsers().get(0).code());
        assertEquals(1, a.browsers().size(), "one browser, one number");
    }

    @Test
    void revokingForgets() throws Exception {
        var a = approvals();
        a.approve(key(5), "666666");
        a.accept(1, true);
        a.revoke(1);
        assertFalse(approvals().approve(key(5), "777777").isDone(), "asks again after a restart");
        assertThrows(IllegalArgumentException.class, () -> a.revoke(1), "nothing left to revoke");
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
