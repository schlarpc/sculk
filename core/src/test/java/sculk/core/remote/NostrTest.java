package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Against nostr-vectors.json, which the shell's tests use too, so the two
 * sides agree. Its event was also checked once outside both: the id by
 * NIP-01's serialization in Python, the signature by BIP-340's reference.py.
 */
class NostrTest {
    private static final HexFormat HEX = HexFormat.of();

    private static JsonObject vectors() throws Exception {
        try (var in = NostrTest.class.getResourceAsStream("/sculk/remote/nostr-vectors.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        o.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
        return out;
    }

    @Test
    void sharedVectors() throws Exception {
        JsonObject v = vectors();
        String topic = Nostr.serverTopic(HEX.parseHex(v.get("serverKey").getAsString()));
        assertEquals(v.get("serverTopic").getAsString(), topic);
        assertEquals(v.get("kind").getAsInt(), Nostr.kind(topic));
        assertEquals(v.get("filter").toString(), Nostr.filter(topic, v.get("filterSince").getAsLong()));
        assertEquals(strings(v, "relaysForServerTopic"), Nostr.relaysFor(topic, strings(v, "relays")));

        JsonObject e = v.getAsJsonObject("event");
        String event = new Nostr.Signer(HEX.parseHex(e.get("secretKey").getAsString())).event(
                e.get("topic").getAsString(), e.get("content").getAsString(),
                e.get("createdAt").getAsLong(), HEX.parseHex(e.get("aux").getAsString()));
        assertEquals(e.get("json").getAsString(), event);
    }

    @Test
    void contentMustBeBase64() {
        var signer = new Nostr.Signer();
        String topic = "00".repeat(32);
        assertThrows(IllegalArgumentException.class, () -> signer.event(topic, "\"}", 0));
        assertThrows(IllegalArgumentException.class, () -> signer.event("not a topic", "", 0));
    }
}
