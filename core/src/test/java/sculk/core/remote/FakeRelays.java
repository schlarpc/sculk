package sculk.core.remote;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory Nostr relays for tests: each forwards EVENTs to subscriptions on the event's x tag. */
final class FakeRelays {
    private FakeRelays() {}

    static final class Net implements RelayPool.Connector {
        final Map<String, Relay> relays = new ConcurrentHashMap<>();

        Relay relay(String url) {
            return relays.computeIfAbsent(url, Relay::new);
        }

        @Override
        public RelayPool.Link open(String url, RelayPool.LinkEvents events) {
            return relay(url).connect(events);
        }
    }

    static final class Relay {
        final String url;
        volatile boolean up = true;
        /** When set, every EVENT gets ["OK", id, false, refuse]. */
        volatile String refuse;
        /** When true, half the EVENTs, at random, are accepted and then lost. */
        volatile boolean lossy;
        private final java.util.Random random = new java.util.Random();
        final List<Conn> conns = new CopyOnWriteArrayList<>();
        final List<String> received = new CopyOnWriteArrayList<>();

        Relay(String url) {
            this.url = url;
        }

        final class Conn implements RelayPool.Link {
            final RelayPool.LinkEvents events;
            final Map<String, String> subs = new ConcurrentHashMap<>(); // subId -> topic
            volatile boolean open = true;

            Conn(RelayPool.LinkEvents events) {
                this.events = events;
            }

            @Override
            public void send(String text) {
                if (!open) return;
                JsonArray msg = JsonParser.parseString(text).getAsJsonArray();
                switch (msg.get(0).getAsString()) {
                    case "REQ" -> {
                        JsonObject filter = msg.get(2).getAsJsonObject();
                        subs.put(msg.get(1).getAsString(), filter.getAsJsonArray("#x").get(0).getAsString());
                        events.text("[\"EOSE\",\"" + msg.get(1).getAsString() + "\"]");
                    }
                    case "EVENT" -> {
                        JsonObject event = msg.get(1).getAsJsonObject();
                        String id = event.get("id").getAsString();
                        if (refuse != null) {
                            events.text("[\"OK\",\"" + id + "\",false,\"" + refuse + "\"]");
                            return;
                        }
                        received.add(id);
                        events.text("[\"OK\",\"" + id + "\",true,\"\"]");
                        if (lossy && random.nextBoolean()) return;
                        String topic = event.getAsJsonArray("tags").get(0).getAsJsonArray().get(1).getAsString();
                        for (Conn c : conns) {
                            c.subs.forEach((sub, t) -> {
                                if (t.equals(topic) && c.open) c.events.text("[\"EVENT\",\"" + sub + "\"," + event + "]");
                            });
                        }
                    }
                    default -> {}
                }
            }

            /** Sends {@code event} down this connection's subscriptions, whatever its topic or worth. */
            void deliver(String event) {
                for (String sub : subs.keySet()) events.text("[\"EVENT\",\"" + sub + "\"," + event + "]");
            }

            @Override
            public void close() {
                open = false;
                conns.remove(this);
            }
        }

        RelayPool.Link connect(RelayPool.LinkEvents events) {
            Conn c = new Conn(events);
            if (!up) {
                c.open = false;
                new Thread(() -> events.closed("connection refused")).start();
                return c;
            }
            conns.add(c);
            new Thread(events::opened).start();
            return c;
        }

        /** Drops every connection, as a restarting relay would. */
        void bounce() {
            for (Conn c : conns) {
                c.open = false;
                conns.remove(c);
                c.events.closed("1006 gone");
            }
        }
    }

    /**
     * A relay on a real socket, for {@link RelayPool#webSockets}: it shakes
     * hands, then sends one text message in fragments that never ends, to
     * each connection in turn until that one is cut off.
     */
    static final class Endless implements AutoCloseable {
        /** Where it stops by itself, had nobody cut it off. */
        static final long GIVE_UP = 64L << 20;

        private final java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
        /** Connections the other end dropped mid-message. */
        final java.util.concurrent.atomic.AtomicInteger cut = new java.util.concurrent.atomic.AtomicInteger();
        /** Set when a connection took {@link #GIVE_UP} bytes of one message. */
        volatile boolean swallowed;

        Endless() throws java.io.IOException {
            Thread t = new Thread(this::serve, "endless-relay");
            t.setDaemon(true);
            t.start();
        }

        String url() {
            return "ws://127.0.0.1:" + server.getLocalPort();
        }

        private void serve() {
            while (!server.isClosed()) {
                try (var socket = server.accept()) {
                    var in = new java.io.BufferedReader(new java.io.InputStreamReader(
                            socket.getInputStream(), java.nio.charset.StandardCharsets.ISO_8859_1));
                    String key = null;
                    for (String line; (line = in.readLine()) != null && !line.isEmpty(); ) {
                        if (line.toLowerCase(java.util.Locale.ROOT).startsWith("sec-websocket-key:")) {
                            key = line.substring(line.indexOf(':') + 1).trim();
                        }
                    }
                    if (key == null) continue;
                    String accept = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
                            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
                    var out = socket.getOutputStream();
                    out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                            + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    // A text frame, then continuations, none final: 16 KiB of "a" each.
                    byte[] frame = new byte[4 + 16384];
                    java.util.Arrays.fill(frame, (byte) 'a');
                    frame[0] = 0x01;
                    frame[1] = 126;
                    frame[2] = 0x40;
                    frame[3] = 0x00;
                    try {
                        for (long sent = 0; sent < GIVE_UP; sent += frame.length) {
                            out.write(frame);
                            frame[0] = 0x00;
                        }
                        swallowed = true;
                        return;
                    } catch (java.io.IOException e) {
                        cut.incrementAndGet();
                    }
                } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
                    // closed, or a client that left during the handshake: next
                }
            }
        }

        @Override
        public void close() throws java.io.IOException {
            server.close();
        }
    }
}
