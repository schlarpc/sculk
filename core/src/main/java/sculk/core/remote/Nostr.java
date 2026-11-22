package sculk.core.remote;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Nostr as remote access uses it (docs/REMOTE.md): topics, which relays a
 * topic lives on, and signed ephemeral events. The shell's src/nostr.ts is
 * the browser's side of the same.
 */
public final class Nostr {
    /** How many relays the browser uses for a topic; the game uses {@link #GAME_RELAYS}. */
    public static final int BROWSER_RELAYS = 5;
    public static final int GAME_RELAYS = 8;
    /** How far back a subscription looks, for clocks that disagree. */
    public static final long CLOCK_SKEW_SECONDS = 60;

    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern TOPIC = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/=]*");

    private Nostr() {}

    static byte[] sha256(byte[]... parts) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The topic the game listens on: hex(SHA-256("sculk/v1/server" || server key)). */
    public static String serverTopic(byte[] serverKey) {
        return HEX.formatHex(sha256("sculk/v1/server".getBytes(StandardCharsets.US_ASCII), serverKey));
    }

    /** The ephemeral event kind for a topic: 20000 + its first two bytes mod 10000. */
    public static int kind(String topic) {
        checkTopic(topic);
        return 20000 + Integer.parseInt(topic.substring(0, 4), 16) % 10000;
    }

    /** The relays for a topic, best first: {@code relays} ({@link RelayList}) by SHA-256(topic || url). */
    public static List<String> relaysFor(String topic, List<String> relays) {
        checkTopic(topic);
        byte[] t = topic.getBytes(StandardCharsets.US_ASCII);
        List<String> out = new ArrayList<>(relays);
        out.sort(Comparator.comparing(
                url -> sha256(t, url.getBytes(StandardCharsets.UTF_8)), Arrays::compareUnsigned));
        return out;
    }

    /** The subscription filter for a topic's events since {@code nowSeconds} less the clock skew. */
    public static String filter(String topic, long nowSeconds) {
        return "{\"kinds\":[" + kind(topic) + "],\"#x\":[\"" + topic + "\"],\"since\":"
                + Math.max(0, nowSeconds - CLOCK_SKEW_SECONDS) + "}";
    }

    private static void checkTopic(String topic) {
        if (!TOPIC.matcher(topic).matches()) throw new IllegalArgumentException("topic: " + topic);
    }

    /** A throwaway signing key, one per session. */
    public static final class Signer {
        private final byte[] secretKey;
        private final String pubkey;
        private long lastCreatedAt = Long.MIN_VALUE / 2;

        public Signer() {
            this(Bip340.newSecretKey());
        }

        Signer(byte[] secretKey) {
            this.secretKey = secretKey.clone();
            this.pubkey = HEX.formatHex(Bip340.publicKey(secretKey));
        }

        public String pubkey() {
            return pubkey;
        }

        /** A signed event on {@code topic}, as JSON. */
        public String event(String topic, String content, long createdAt) {
            return event(topic, content, createdAt, null);
        }

        /** As above, with fixed BIP-340 randomness: for tests. */
        synchronized String event(String topic, String content, long createdAt, byte[] aux) {
            checkTopic(topic);
            // An event's id covers its time in seconds but not its signature,
            // and relays drop an id they have seen: so each event is at least
            // a second after this signer's last, or a message sent again
            // within a second would vanish.
            createdAt = Math.max(createdAt, lastCreatedAt + 1);
            lastCreatedAt = createdAt;
            // Everything here is ASCII that JSON does not escape, so NIP-01's
            // serialization is plain concatenation.
            if (!BASE64.matcher(content).matches()) throw new IllegalArgumentException("content must be base64");
            int kind = kind(topic);
            String tags = "[[\"x\",\"" + topic + "\"]]";
            String serial = "[0,\"" + pubkey + "\"," + createdAt + "," + kind + "," + tags + ",\"" + content + "\"]";
            byte[] id = sha256(serial.getBytes(StandardCharsets.US_ASCII));
            byte[] sig = aux == null ? Bip340.sign(secretKey, id) : Bip340.sign(secretKey, id, aux);
            return "{\"id\":\"" + HEX.formatHex(id) + "\",\"pubkey\":\"" + pubkey + "\",\"created_at\":" + createdAt
                    + ",\"kind\":" + kind + ",\"tags\":" + tags + ",\"content\":\"" + content
                    + "\",\"sig\":\"" + HEX.formatHex(sig) + "\"}";
        }
    }
}
