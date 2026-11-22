package sculk.core.remote;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.function.Supplier;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The {@code Noise_XK_25519_AESGCM_SHA256} handshake (Noise revision 34), on
 * the JDK's own primitives. Remote access (issue #3) uses it so that a
 * browser holding the server's public key from a link can reach that server
 * through relays that see only ciphertext, while sending its own static key
 * encrypted, so the relays never learn which browser it is.
 *
 * <pre>
 *   XK:
 *     &lt;- s
 *     ...
 *     -&gt; e, es
 *     &lt;- e, ee
 *     -&gt; s, se
 * </pre>
 *
 * The game is the responder. After the third message, {@link #split} gives
 * the two transport ciphers, {@link #remoteStatic} the browser's key (for the
 * allowlist), and {@link #handshakeHash} the value the approval code is
 * derived from. One instance per handshake, used from one thread.
 */
public final class NoiseXK {
    public static final String PROTOCOL = "Noise_XK_25519_AESGCM_SHA256";
    /** Noise's limit on any one message. */
    public static final int MAX_MESSAGE = 65535;
    static final int HASH = 32;
    static final int TAG = 16;

    private final boolean initiator;
    private final KeyPair s;
    private final Supplier<KeyPair> ephemerals;
    private final Symmetric sym;
    private KeyPair e;
    private byte[] rs;
    private byte[] re;
    /** Messages done: 0 to 3. */
    private int step;

    private NoiseXK(boolean initiator, KeyPair s, byte[] rs, byte[] prologue, Supplier<KeyPair> ephemerals) {
        this.initiator = initiator;
        this.s = s;
        this.rs = rs == null ? null : rs.clone();
        this.ephemerals = ephemerals;
        this.sym = new Symmetric(PROTOCOL);
        sym.mixHash(prologue);
        // The pre-message: the responder's static key, known to both.
        sym.mixHash(initiator ? this.rs : X25519.raw(s.getPublic()));
    }

    /** The browser's side: its own static key, and the server's from the link. */
    public static NoiseXK initiator(KeyPair s, byte[] serverStatic, byte[] prologue) {
        return initiator(s, serverStatic, prologue, X25519::generate);
    }

    /** The game's side: its persistent static key. */
    public static NoiseXK responder(KeyPair s, byte[] prologue) {
        return responder(s, prologue, X25519::generate);
    }

    /** As above, with ephemeral keys from {@code ephemerals}: for test vectors. */
    static NoiseXK initiator(KeyPair s, byte[] serverStatic, byte[] prologue, Supplier<KeyPair> ephemerals) {
        if (serverStatic.length != X25519.LEN) throw new IllegalArgumentException("server key: " + serverStatic.length + " bytes");
        return new NoiseXK(true, s, serverStatic, prologue, ephemerals);
    }

    static NoiseXK responder(KeyPair s, byte[] prologue, Supplier<KeyPair> ephemerals) {
        return new NoiseXK(false, s, null, prologue, ephemerals);
    }

    /** Whether the next handshake message is ours to write. */
    public boolean ourTurn() {
        return !isComplete() && (step % 2 == 0) == initiator;
    }

    public boolean isComplete() {
        return step == 3;
    }

    /** The next handshake message, carrying {@code payload} (encrypted in all three). */
    public byte[] writeMessage(byte[] payload) throws GeneralSecurityException {
        if (!ourTurn()) throw new IllegalStateException("not our turn to write (step " + step + ")");
        var out = new ByteArrayOutputStream();
        switch (step) {
            case 0 -> { // -> e, es
                e = ephemerals.get();
                writeEphemeral(out);
                sym.mixKey(X25519.dh(e.getPrivate(), rs));
            }
            case 1 -> { // <- e, ee
                e = ephemerals.get();
                writeEphemeral(out);
                sym.mixKey(X25519.dh(e.getPrivate(), re));
            }
            case 2 -> { // -> s, se
                out.writeBytes(sym.encryptAndHash(X25519.raw(s.getPublic())));
                sym.mixKey(X25519.dh(s.getPrivate(), re));
            }
            default -> throw new IllegalStateException();
        }
        out.writeBytes(sym.encryptAndHash(payload));
        if (out.size() > MAX_MESSAGE) throw new IllegalArgumentException("message over " + MAX_MESSAGE + " bytes");
        step++;
        return out.toByteArray();
    }

    /**
     * Reads the peer's next handshake message and returns its payload.
     *
     * @throws GeneralSecurityException if it does not authenticate; the
     *     handshake is then dead and must be dropped
     */
    public byte[] readMessage(byte[] message) throws GeneralSecurityException {
        if (isComplete() || ourTurn()) throw new IllegalStateException("not our turn to read (step " + step + ")");
        if (message.length > MAX_MESSAGE) throw new GeneralSecurityException("message over " + MAX_MESSAGE + " bytes");
        int at = 0;
        switch (step) {
            case 0 -> { // -> e, es (we are the responder)
                at = readEphemeral(message);
                sym.mixKey(X25519.dh(s.getPrivate(), re));
            }
            case 1 -> { // <- e, ee (we are the initiator)
                at = readEphemeral(message);
                sym.mixKey(X25519.dh(e.getPrivate(), re));
            }
            case 2 -> { // -> s, se (we are the responder)
                int len = X25519.LEN + TAG;
                if (message.length < len) throw new GeneralSecurityException("short handshake message");
                rs = sym.decryptAndHash(Arrays.copyOfRange(message, 0, len));
                at = len;
                sym.mixKey(X25519.dh(e.getPrivate(), rs));
            }
            default -> throw new IllegalStateException();
        }
        byte[] payload = sym.decryptAndHash(Arrays.copyOfRange(message, at, message.length));
        step++;
        return payload;
    }

    private void writeEphemeral(ByteArrayOutputStream out) {
        byte[] pub = X25519.raw(e.getPublic());
        out.writeBytes(pub);
        sym.mixHash(pub);
    }

    private int readEphemeral(byte[] message) throws GeneralSecurityException {
        if (message.length < X25519.LEN) throw new GeneralSecurityException("short handshake message");
        re = Arrays.copyOf(message, X25519.LEN);
        sym.mixHash(re);
        return X25519.LEN;
    }

    /** The peer's static key: the server's for the initiator, the browser's after message 3 for the responder. */
    public byte[] remoteStatic() {
        return rs == null ? null : rs.clone();
    }

    /** The handshake hash, which both sides share once the handshake is complete. */
    public byte[] handshakeHash() {
        if (!isComplete()) throw new IllegalStateException("handshake not complete");
        return sym.h.clone();
    }

    /** The two transport ciphers: ours to send with, and ours to receive with. */
    public Transport split() {
        if (!isComplete()) throw new IllegalStateException("handshake not complete");
        byte[][] k = Symmetric.hkdf(sym.ck, new byte[0], 2);
        var c1 = new CipherState(k[0]);
        var c2 = new CipherState(k[1]);
        return initiator ? new Transport(c1, c2) : new Transport(c2, c1);
    }

    /** The ciphers after the handshake. Each direction's nonces count up from 0. */
    public record Transport(CipherState send, CipherState receive) {}

    /** A key and a nonce counter: Noise's CipherState, with AES-256-GCM. */
    public static final class CipherState {
        private SecretKeySpec key;
        private long n;
        private final Cipher cipher;

        CipherState(byte[] k) {
            try {
                cipher = Cipher.getInstance("AES/GCM/NoPadding");
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException(ex);
            }
            if (k != null) initializeKey(k);
        }

        void initializeKey(byte[] k) {
            key = new SecretKeySpec(k, "AES");
            n = 0;
        }

        boolean hasKey() {
            return key != null;
        }

        /** AES-GCM's nonce: 32 zero bits, then n big-endian. */
        private GCMParameterSpec nonce() {
            if (n == -1L) throw new IllegalStateException("nonce exhausted"); // 2^64-1 is reserved
            byte[] iv = new byte[12];
            for (int i = 0; i < 8; i++) {
                iv[4 + i] = (byte) (n >>> (56 - 8 * i));
            }
            return new GCMParameterSpec(TAG * 8, iv);
        }

        public byte[] encrypt(byte[] ad, byte[] plaintext) throws GeneralSecurityException {
            if (!hasKey()) return plaintext.clone();
            cipher.init(Cipher.ENCRYPT_MODE, key, nonce());
            cipher.updateAAD(ad);
            byte[] out = cipher.doFinal(plaintext);
            n++;
            return out;
        }

        /** @throws AEADBadTagException if it does not authenticate (the nonce does not advance) */
        public byte[] decrypt(byte[] ad, byte[] ciphertext) throws GeneralSecurityException {
            if (!hasKey()) return ciphertext.clone();
            cipher.init(Cipher.DECRYPT_MODE, key, nonce());
            cipher.updateAAD(ad);
            byte[] out = cipher.doFinal(ciphertext);
            n++;
            return out;
        }
    }

    /** Noise's SymmetricState: the chaining key, the hash, and the cipher between them. */
    static final class Symmetric {
        byte[] ck;
        byte[] h;
        final CipherState cs = new CipherState(null);

        Symmetric(String protocol) {
            byte[] name = protocol.getBytes(StandardCharsets.US_ASCII);
            h = name.length <= HASH ? Arrays.copyOf(name, HASH) : sha256(name);
            ck = h.clone();
        }

        void mixKey(byte[] ikm) {
            byte[][] out = hkdf(ck, ikm, 2);
            ck = out[0];
            cs.initializeKey(out[1]); // HASHLEN is 32, the AES-256 key length
        }

        void mixHash(byte[] data) {
            h = sha256(h, data);
        }

        byte[] encryptAndHash(byte[] plaintext) throws GeneralSecurityException {
            byte[] c = cs.encrypt(h, plaintext);
            mixHash(c);
            return c;
        }

        byte[] decryptAndHash(byte[] ciphertext) throws GeneralSecurityException {
            byte[] p = cs.decrypt(h, ciphertext);
            mixHash(ciphertext);
            return p;
        }

        static byte[] sha256(byte[]... parts) {
            try {
                var md = MessageDigest.getInstance("SHA-256");
                for (byte[] p : parts) md.update(p);
                return md.digest();
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException(ex);
            }
        }

        static byte[] hmac(byte[] key, byte[]... parts) {
            try {
                var mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                for (byte[] p : parts) mac.update(p);
                return mac.doFinal();
            } catch (GeneralSecurityException ex) {
                throw new IllegalStateException(ex);
            }
        }

        /** Noise's HKDF (RFC 5869 with an empty info), giving 2 or 3 outputs. */
        static byte[][] hkdf(byte[] ck, byte[] ikm, int outputs) {
            byte[] temp = hmac(ck, ikm);
            byte[][] out = new byte[outputs][];
            byte[] prev = new byte[0];
            for (int i = 0; i < outputs; i++) {
                prev = hmac(temp, prev, new byte[] {(byte) (i + 1)});
                out[i] = prev;
            }
            return out;
        }
    }
}
