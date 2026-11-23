package sculk.core.remote;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/** The pieces of docs/REMOTE.md's signaling that both sides share. */
public final class Signaling {
    private static final byte[] PROLOGUE = "sculk remote v1".getBytes(StandardCharsets.US_ASCII);
    public static final int SID = 32;
    /** Publish the latest message again this often until the peer answers. */
    public static final long RESEND_MS = 3000;
    /** How long a session may wait for approval. */
    public static final long APPROVAL_MS = 60_000;
    /** How long the handshake may take, from the browser's first message. */
    public static final long HANDSHAKE_MS = 30_000;
    /** How long the game keeps publishing its final answer. */
    public static final long FINAL_MS = 30_000;

    private Signaling() {}

    /**
     * The Noise prologue: {@code "sculk remote v1" || sid}. With the sid in
     * it, a first message someone recorded opens no session under another sid.
     */
    public static byte[] prologue(byte[] sid) {
        if (sid.length != SID) throw new IllegalArgumentException("sid: " + sid.length + " bytes");
        byte[] out = Arrays.copyOf(PROLOGUE, PROLOGUE.length + SID);
        System.arraycopy(sid, 0, out, PROLOGUE.length, SID);
        return out;
    }

    /** {@code sid || n || body}: one message, in an event's content. */
    public record Envelope(byte[] sid, int n, byte[] body) {
        public Envelope {
            if (sid.length != SID) throw new IllegalArgumentException("sid: " + sid.length + " bytes");
            if (n < 0 || n > 255) throw new IllegalArgumentException("n: " + n);
        }

        public String sidHex() {
            return HexFormat.of().formatHex(sid);
        }

        /** As an event's content: standard base64 with padding. */
        public String encode() {
            byte[] out = new byte[SID + 1 + body.length];
            System.arraycopy(sid, 0, out, 0, SID);
            out[SID] = (byte) n;
            System.arraycopy(body, 0, out, SID + 1, body.length);
            return Base64.getEncoder().encodeToString(out);
        }

        /** @throws IllegalArgumentException if it is not an envelope */
        public static Envelope decode(String content) {
            byte[] b = Base64.getDecoder().decode(content);
            if (b.length < SID + 1) throw new IllegalArgumentException("short envelope");
            return new Envelope(Arrays.copyOf(b, SID), b[SID] & 0xff, Arrays.copyOfRange(b, SID + 1, b.length));
        }
    }

    /**
     * The six-digit code the browser shows and the player types:
     * SHA-256("sculk/v1/code" || secret), first four bytes, mod 10^6, where
     * {@code secret} is the session's ({@link NoiseXK.Transport#secret}).
     * Nobody else can compute it, so nobody else can show it.
     */
    public static String approvalCode(byte[] secret) {
        byte[] d = Nostr.sha256("sculk/v1/code".getBytes(StandardCharsets.US_ASCII), secret);
        long v = ((d[0] & 0xffL) << 24) | ((d[1] & 0xffL) << 16) | ((d[2] & 0xffL) << 8) | (d[3] & 0xffL);
        return String.format("%06d", v % 1_000_000);
    }
}
