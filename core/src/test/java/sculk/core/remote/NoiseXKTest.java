package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoiseXKTest {
    private static final HexFormat HEX = HexFormat.of();

    private static List<JsonObject> vectors() throws Exception {
        try (var in = NoiseXKTest.class.getResourceAsStream("/sculk/remote/noise-vectors.json")) {
            var doc = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            List<JsonObject> out = new ArrayList<>();
            doc.getAsJsonArray("vectors").forEach(v -> out.add(v.getAsJsonObject()));
            return out;
        }
    }

    private static byte[] hex(JsonObject v, String key) {
        return HEX.parseHex(v.get(key).getAsString());
    }

    @Test
    void publishedVectors() throws Exception {
        List<JsonObject> vectors = vectors();
        assertEquals(2, vectors.size());
        for (JsonObject v : vectors) {
            String source = v.get("source").getAsString();
            byte[] prologue = hex(v, "init_prologue");
            KeyPair initE = X25519.fromPrivate(hex(v, "init_ephemeral"));
            KeyPair respE = X25519.fromPrivate(hex(v, "resp_ephemeral"));
            var init = NoiseXK.initiator(X25519.fromPrivate(hex(v, "init_static")),
                    hex(v, "init_remote_static"), prologue, () -> initE);
            var resp = NoiseXK.responder(X25519.fromPrivate(hex(v, "resp_static")), prologue, () -> respE);

            NoiseXK.Transport it = null;
            NoiseXK.Transport rt = null;
            var messages = v.getAsJsonArray("messages");
            for (int i = 0; i < messages.size(); i++) {
                var m = messages.get(i).getAsJsonObject();
                byte[] payload = hex(m, "payload");
                byte[] expected = hex(m, "ciphertext");
                boolean fromInit = i % 2 == 0;
                byte[] wire;
                byte[] got;
                if (i < 3) {
                    wire = (fromInit ? init : resp).writeMessage(payload);
                    assertArrayEquals(expected, wire, source + ": handshake message " + i);
                    got = (fromInit ? resp : init).readMessage(wire);
                } else {
                    if (it == null) {
                        it = init.split();
                        rt = resp.split();
                    }
                    wire = (fromInit ? it : rt).send().encrypt(new byte[0], payload);
                    assertArrayEquals(expected, wire, source + ": transport message " + i);
                    got = (fromInit ? rt : it).receive().decrypt(new byte[0], wire);
                }
                assertArrayEquals(payload, got, source + ": payload " + i);
            }
            assertTrue(init.isComplete() && resp.isComplete());
            if (v.has("handshake_hash")) {
                assertArrayEquals(hex(v, "handshake_hash"), init.handshakeHash(), source);
            }
            assertArrayEquals(init.handshakeHash(), resp.handshakeHash(), source);
            // Split()'s third output, which the approval code is derived from: the shell pins the same.
            assertArrayEquals(hex(v, "secret"), it.secret(), source);
            assertArrayEquals(hex(v, "secret"), rt.secret(), source);
            // The responder learns the initiator's static key.
            assertArrayEquals(X25519.raw(X25519.fromPrivate(hex(v, "init_static")).getPublic()),
                    resp.remoteStatic(), source);
        }
    }

    @Test
    void wrongServerKeyFailsAtTheFirstMessage() throws Exception {
        KeyPair server = X25519.generate();
        KeyPair other = X25519.generate();
        var init = NoiseXK.initiator(X25519.generate(), X25519.raw(other.getPublic()), new byte[0]);
        var resp = NoiseXK.responder(server, new byte[0]);
        byte[] m1 = init.writeMessage(new byte[0]);
        assertThrows(GeneralSecurityException.class, () -> resp.readMessage(m1));
    }

    @Test
    void aFalseMessageLeavesTheHandshakeAbleToReadTheRealOne() throws Exception {
        KeyPair server = X25519.generate();
        var init = NoiseXK.initiator(X25519.generate(), X25519.raw(server.getPublic()), new byte[0]);
        var resp = NoiseXK.responder(server, new byte[0]);
        assertThrows(GeneralSecurityException.class, () -> resp.readMessage(new byte[48]));
        resp.readMessage(init.writeMessage(new byte[0]));
        byte[] m2 = resp.writeMessage(new byte[0]);
        assertThrows(GeneralSecurityException.class, () -> init.readMessage(new byte[48]));
        init.readMessage(m2);
        byte[] m3 = init.writeMessage("offer".getBytes(StandardCharsets.UTF_8));
        assertThrows(GeneralSecurityException.class, () -> resp.readMessage(new byte[80]));
        assertArrayEquals("offer".getBytes(StandardCharsets.UTF_8), resp.readMessage(m3));
        assertArrayEquals(init.split().secret(), resp.split().secret());
    }

    @Test
    void prologuesMustMatch() throws Exception {
        KeyPair server = X25519.generate();
        var init = NoiseXK.initiator(X25519.generate(), X25519.raw(server.getPublic()), new byte[] {1});
        var resp = NoiseXK.responder(server, new byte[] {2});
        byte[] m1 = init.writeMessage(new byte[0]);
        assertThrows(GeneralSecurityException.class, () -> resp.readMessage(m1));
    }

    @Test
    void tamperedMessageFails() throws Exception {
        KeyPair server = X25519.generate();
        var init = NoiseXK.initiator(X25519.generate(), X25519.raw(server.getPublic()), new byte[0]);
        var resp = NoiseXK.responder(server, new byte[0]);
        resp.readMessage(init.writeMessage("hi".getBytes(StandardCharsets.UTF_8)));
        byte[] m2 = resp.writeMessage(new byte[0]);
        m2[40] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> init.readMessage(m2));
    }

    @Test
    void smallOrderKeysAreRefused() {
        // The all-zero point, and 1: both give an all-zero shared secret.
        for (int u : new int[] {0, 1}) {
            byte[] bad = new byte[32];
            bad[0] = (byte) u;
            var init = NoiseXK.initiator(X25519.generate(), bad, new byte[0]);
            assertThrows(GeneralSecurityException.class, () -> init.writeMessage(new byte[0]));
        }
    }

    @Test
    void turnsAreEnforced() throws Exception {
        KeyPair server = X25519.generate();
        var init = NoiseXK.initiator(X25519.generate(), X25519.raw(server.getPublic()), new byte[0]);
        var resp = NoiseXK.responder(server, new byte[0]);
        assertTrue(init.ourTurn());
        assertFalse(resp.ourTurn());
        assertThrows(IllegalStateException.class, () -> resp.writeMessage(new byte[0]));
        assertThrows(IllegalStateException.class, init::split);
    }

    @Test
    void x25519RoundTrips() {
        KeyPair k = X25519.generate();
        byte[] raw = X25519.raw(k.getPublic());
        assertArrayEquals(raw, X25519.raw(X25519.publicKey(raw)));
    }
}
