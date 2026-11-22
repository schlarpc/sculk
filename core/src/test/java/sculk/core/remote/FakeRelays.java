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
}
