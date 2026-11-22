package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Two engines on loopback: one offers as a browser would, the other answers as the game does. */
class WebRtcTest {
    private static WebRtc engine() throws Exception {
        return new WebRtc(List.of(InetAddress.getLoopbackAddress()), 0, true);
    }

    @Test
    void connectsAndEchoes() throws Exception {
        var gotEcho = new CompletableFuture<String>();
        var gameOpened = new CompletableFuture<String>();
        var browserOpen = new CompletableFuture<Integer>();
        try (var game = engine(); var browser = engine()) {
            var offer = browser.offer("sculk", new WebRtc.Peer() {
                @Override
                public void opened(WebRtc.Connection c, int channel, String label) {
                    browserOpen.complete(channel);
                }

                @Override
                public void message(WebRtc.Connection c, int channel, byte[] data, boolean binary) {
                    gotEcho.complete(new String(data, StandardCharsets.UTF_8));
                }
            }).get(10, TimeUnit.SECONDS);
            String answer = game.answer(offer.getValue(), new WebRtc.Peer() {
                @Override
                public void opened(WebRtc.Connection c, int channel, String label) {
                    gameOpened.complete(label);
                }

                @Override
                public void message(WebRtc.Connection c, int channel, byte[] data, boolean binary) {
                    c.send(channel, ("echo: " + new String(data, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8), false);
                }
            }).get(10, TimeUnit.SECONDS);
            assertTrue(answer.contains("a=fingerprint:sha-256"), answer);
            browser.acceptAnswer(offer.getKey(), answer).get(10, TimeUnit.SECONDS);

            int channel = browserOpen.get(15, TimeUnit.SECONDS);
            assertEquals("sculk", gameOpened.get(15, TimeUnit.SECONDS));
            browser.execute(() -> offer.getKey().send(channel, "hello".getBytes(StandardCharsets.UTF_8), false));
            assertEquals("echo: hello", gotEcho.get(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void carriesAMegabyteInOrder() throws Exception {
        byte[] sent = new byte[1 << 20];
        new java.util.Random(1).nextBytes(sent);
        var received = new java.io.ByteArrayOutputStream();
        var done = new CompletableFuture<Void>();
        var browserOpen = new CompletableFuture<Integer>();
        try (var game = engine(); var browser = engine()) {
            int[] at = {0};
            var offer = browser.offer("bulk", new WebRtc.Peer() {
                @Override
                public void opened(WebRtc.Connection c, int channel, String label) {
                    c.setLowMark(channel, 64 * 1024);
                    browserOpen.complete(channel);
                }

                /** Send in 16 KiB messages, keeping at most 256 KiB queued. */
                void pump(WebRtc.Connection c, int channel) {
                    while (at[0] < sent.length && c.queued(channel) < 256 * 1024) {
                        int n = Math.min(16 * 1024, sent.length - at[0]);
                        if (!c.send(channel, java.util.Arrays.copyOfRange(sent, at[0], at[0] + n), true)) return;
                        at[0] += n;
                    }
                }

                @Override
                public void writable(WebRtc.Connection c, int channel) {
                    pump(c, channel);
                }
            }).get(10, TimeUnit.SECONDS);
            String answer = game.answer(offer.getValue(), new WebRtc.Peer() {
                @Override
                public void message(WebRtc.Connection c, int channel, byte[] data, boolean binary) {
                    received.writeBytes(data);
                    if (received.size() >= sent.length) done.complete(null);
                }
            }).get(10, TimeUnit.SECONDS);
            browser.acceptAnswer(offer.getKey(), answer).get(10, TimeUnit.SECONDS);
            int channel = browserOpen.get(15, TimeUnit.SECONDS);
            long t0 = System.nanoTime();
            // The first batch; the low mark brings the rest.
            browser.execute(() -> {
                var c = offer.getKey();
                while (at[0] < sent.length && c.queued(channel) < 256 * 1024) {
                    int n = Math.min(16 * 1024, sent.length - at[0]);
                    if (!c.send(channel, java.util.Arrays.copyOfRange(sent, at[0], at[0] + n), true)) break;
                    at[0] += n;
                }
            });
            done.get(60, TimeUnit.SECONDS);
            System.out.printf("1 MiB over loopback in %d ms%n", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
            assertArrayEquals(sent, received.toByteArray());
        }
    }
}
