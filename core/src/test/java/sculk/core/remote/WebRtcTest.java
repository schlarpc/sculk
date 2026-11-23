package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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

    /** What a connected pair's game side saw. */
    private static final class GameSide implements WebRtc.Peer {
        final CompletableFuture<WebRtc.Connection> connected = new CompletableFuture<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();

        @Override
        public void connected(WebRtc.Connection c) {
            connected.complete(c);
        }

        @Override
        public void message(WebRtc.Connection c, int channel, byte[] data, boolean binary) {
            c.send(channel, data, binary);
        }

        @Override
        public void closed(WebRtc.Connection c) {
            closed.complete(null);
        }
    }

    /** Connects browser to game and returns the browser's echo of "ping", proving the pair works. */
    private static String echo(WebRtc game, WebRtc browser, GameSide side) throws Exception {
        var open = new CompletableFuture<Integer>();
        var echo = new CompletableFuture<String>();
        var offer = browser.offer("sculk", new WebRtc.Peer() {
            @Override
            public void opened(WebRtc.Connection c, int channel, String label) {
                open.complete(channel);
            }

            @Override
            public void message(WebRtc.Connection c, int channel, byte[] data, boolean binary) {
                echo.complete(new String(data, StandardCharsets.UTF_8));
            }
        }).get(10, TimeUnit.SECONDS);
        String answer = game.answer(offer.getValue(), side).get(10, TimeUnit.SECONDS);
        browser.acceptAnswer(offer.getKey(), answer).get(10, TimeUnit.SECONDS);
        int channel = open.get(15, TimeUnit.SECONDS);
        browser.execute(() -> offer.getKey().send(channel, "ping".getBytes(StandardCharsets.UTF_8), false));
        return echo.get(15, TimeUnit.SECONDS);
    }

    @Test
    void aTrapEndsConnectionsAndTheNextStartsAfresh() throws Exception {
        try (var game = engine(); var browser = engine()) {
            var first = new GameSide();
            assertEquals("ping", echo(game, browser, first));
            game.breakModule().get(10, TimeUnit.SECONDS);
            // The loop meets the trap at its next call into the module, and ends what was running on it.
            first.closed.get(15, TimeUnit.SECONDS);
            var c = first.connected.get(1, TimeUnit.SECONDS);
            // Its connection is over, and using it is harmless.
            var sent = new CompletableFuture<Boolean>();
            game.execute(() -> sent.complete(c.isConnected() || c.send(0, new byte[1], true)));
            assertFalse(sent.get(10, TimeUnit.SECONDS));
            // A browser reconnects from a new port; from the old one, its old connection would take the datagrams.
            try (var again = engine()) {
                assertEquals("ping", echo(game, again, new GameSide()));
            }
        }
    }

    @Test
    void aTrapWithNothingConnectedIsRecoveredFromToo() throws Exception {
        try (var game = engine(); var browser = engine()) {
            game.breakModule().get(10, TimeUnit.SECONDS);
            assertEquals("ping", echo(game, browser, new GameSide()));
        }
    }

    @Test
    void aBrowserThatVanishesIsDropped() throws Exception {
        var side = new GameSide();
        try (var game = engine()) {
            game.iceTimeoutMs = 500;
            try (var browser = engine()) {
                assertEquals("ping", echo(game, browser, side));
            }
            // The browser's engine is gone without a word; ICE notices, and then the connection ends.
            long t0 = System.nanoTime();
            side.closed.get(60, TimeUnit.SECONDS);
            System.out.printf("a vanished browser was dropped after %d ms%n", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
        }
    }

    @Test
    void answersNoMoreThanTheCap() throws Exception {
        try (var game = engine(); var browser = engine()) {
            String offer = browser.offer("sculk", new WebRtc.Peer() {}).get(10, TimeUnit.SECONDS).getValue();
            // A cap of two: sixteen answers take a slow machine longer than a
            // connection that never connects lasts, and then there is room again.
            game.maxConnections = 2;
            // The same offer each time: nothing connects, but each answer is a connection until it times out.
            // Asked for together, as each answer waits a while for STUN.
            var answers = new java.util.ArrayList<CompletableFuture<String>>();
            for (int i = 0; i < game.maxConnections; i++) answers.add(game.answer(offer, new WebRtc.Peer() {}));
            var closed = new CompletableFuture<Void>();
            var over = game.answer(offer, new WebRtc.Peer() {
                @Override
                public void closed(WebRtc.Connection c) {
                    closed.complete(null);
                }
            });
            var e = assertThrows(ExecutionException.class, () -> over.get(30, TimeUnit.SECONDS));
            assertTrue(e.getCause().getMessage().contains("too many connections"), e.getCause().toString());
            assertFalse(closed.isDone(), "no connection was made, so none closed");
            for (var answer : answers) assertTrue(answer.get(30, TimeUnit.SECONDS).contains("a=fingerprint:sha-256"));
        }
    }
}
