package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** A tunnel behind one engine, a browser speaking its messages on the other, and 5 MB through it. */
class TunnelOverWebRtcTest {
    @Test
    void fiveMegabytesComeThroughIntact() throws Exception {
        byte[] payload = new byte[5 << 20];
        new java.util.Random(7).nextBytes(payload);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/big", ex -> {
            ex.sendResponseHeaders(200, payload.length);
            ex.getResponseBody().write(payload);
            ex.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        ExecutorService readers = Executors.newCachedThreadPool();
        var received = new ByteArrayOutputStream();
        var done = new CompletableFuture<Integer>();
        var browserChannel = new CompletableFuture<Integer>();
        try (var game = new WebRtc(List.of(InetAddress.getLoopbackAddress()), 0, true);
             var browser = new WebRtc(List.of(InetAddress.getLoopbackAddress()), 0, true)) {
            var offer = browser.offer("sculk", new WebRtc.Peer() {
                @Override
                public void opened(WebRtc.Connection c, int channel, String label) {
                    browserChannel.complete(channel);
                }

                @Override
                public void message(WebRtc.Connection c, int channel, byte[] data, boolean binary) {
                    ByteBuffer b = ByteBuffer.wrap(data);
                    b.getInt();
                    int type = b.get() & 0xff;
                    if (type == Tunnel.RESPONSE_BODY) received.writeBytes(Arrays.copyOfRange(data, 5, data.length));
                    if (type == Tunnel.RESPONSE_END) done.complete(received.size());
                    if (type == Tunnel.ERROR) done.completeExceptionally(new AssertionError(new String(data, 5, data.length - 5)));
                }
            }).get(10, TimeUnit.SECONDS);
            String answer = game.answer(offer.getValue(), new WebRtc.Peer() {
                ChannelLink link;
                Tunnel tunnel;

                @Override
                public void opened(WebRtc.Connection c, int channel, String label) {
                    link = new ChannelLink(game, c, channel);
                    tunnel = new Tunnel(link, () -> new Tunnel.Upstream() {
                        @Override
                        public URI base() {
                            return base;
                        }

                        @Override
                        public String token() {
                            return "T";
                        }
                    }, readers);
                }

                @Override
                public void message(WebRtc.Connection c, int channel, byte[] data, boolean binary) {
                    tunnel.receive(data);
                }

                @Override
                public void writable(WebRtc.Connection c, int channel) {
                    link.flush();
                }
            }).get(10, TimeUnit.SECONDS);
            browser.acceptAnswer(offer.getKey(), answer).get(10, TimeUnit.SECONDS);
            int ch = browserChannel.get(15, TimeUnit.SECONDS);
            browser.execute(() -> {
                var c = offer.getKey();
                c.send(ch, Tunnel.message(1, Tunnel.REQUEST, "{\"method\":\"GET\",\"path\":\"/api/big\"}".getBytes(StandardCharsets.UTF_8)), true);
                c.send(ch, Tunnel.message(1, Tunnel.REQUEST_END, new byte[0]), true);
            });
            assertEquals(payload.length, done.get(120, TimeUnit.SECONDS));
            assertArrayEquals(payload, received.toByteArray());
        } finally {
            server.stop(0);
            readers.shutdownNow();
        }
    }
}
