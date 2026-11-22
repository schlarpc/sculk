package sculk.core.remote;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;

/**
 * X25519 (RFC 7748) on the JDK's {@code XDH}, with keys as Noise writes them:
 * public keys as their 32 raw bytes, little-endian.
 */
public final class X25519 {
    public static final int LEN = 32;

    private static final NamedParameterSpec CURVE = NamedParameterSpec.X25519;
    private static final byte[] BASE_POINT = new byte[LEN];

    static {
        BASE_POINT[0] = 9;
    }

    private X25519() {}

    /** A new key pair from the JDK's default SecureRandom. */
    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no X25519 in this JVM", e);
        }
    }

    /** The key pair with this 32-byte private key (clamped by X25519 itself). */
    public static KeyPair fromPrivate(byte[] raw) {
        if (raw.length != LEN) throw new IllegalArgumentException("X25519 private key: " + raw.length + " bytes");
        try {
            PrivateKey priv = KeyFactory.getInstance("XDH")
                    .generatePrivate(new XECPrivateKeySpec(CURVE, raw.clone()));
            // The public key is the private key times the base point.
            return new KeyPair(publicKey(dh(priv, BASE_POINT)), priv);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The 32 raw bytes of a public key. */
    public static byte[] raw(PublicKey key) {
        BigInteger u = ((XECPublicKey) key).getU();
        byte[] be = u.toByteArray(); // big-endian, maybe with a leading 0
        byte[] le = new byte[LEN];
        for (int i = 0; i < LEN && i < be.length; i++) {
            le[i] = be[be.length - 1 - i];
        }
        return le;
    }

    /** A public key from its 32 raw bytes. */
    public static PublicKey publicKey(byte[] raw) {
        if (raw.length != LEN) throw new IllegalArgumentException("X25519 public key: " + raw.length + " bytes");
        byte[] be = new byte[LEN];
        for (int i = 0; i < LEN; i++) {
            be[i] = raw[LEN - 1 - i];
        }
        be[0] &= 0x7f; // RFC 7748 5: ignore the top bit
        try {
            return KeyFactory.getInstance("XDH")
                    .generatePublic(new XECPublicKeySpec(CURVE, new BigInteger(1, be)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The shared secret of a private key and a raw public key.
     *
     * @throws GeneralSecurityException if the peer's key is of small order (an
     *     all-zero result), which the JDK refuses and Noise allows refusing
     */
    public static byte[] dh(PrivateKey priv, byte[] peerPublic) throws GeneralSecurityException {
        var ka = javax.crypto.KeyAgreement.getInstance("X25519");
        ka.init(priv);
        ka.doPhase(publicKey(peerPublic), true);
        byte[] out = ka.generateSecret();
        if (out.length != LEN || Arrays.equals(out, new byte[LEN])) {
            throw new GeneralSecurityException("X25519: small-order public key");
        }
        return out;
    }
}
