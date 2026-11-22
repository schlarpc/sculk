package sculk.core.remote;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * BIP-340 Schnorr signing on secp256k1, which Nostr requires on every event.
 * Signing only: relays check the signatures, and nothing sculk trusts depends
 * on them (that is Noise's job, inside the events).
 *
 * <p>Not constant-time. That is acceptable because the keys are throwaway:
 * one per signaling session, never stored, protecting nothing. Do not use
 * this for a key that matters.
 */
public final class Bip340 {
    static final BigInteger P = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16);
    static final BigInteger N = new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);
    private static final Point G = new Point(
            new BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
            new BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
            BigInteger.ONE);
    private static final SecureRandom RANDOM = new SecureRandom();

    private Bip340() {}

    /** A new random secret key. */
    public static byte[] newSecretKey() {
        while (true) {
            byte[] sk = new byte[32];
            RANDOM.nextBytes(sk);
            BigInteger d = new BigInteger(1, sk);
            if (d.signum() > 0 && d.compareTo(N) < 0) return sk;
        }
    }

    /** The 32-byte x-only public key. */
    public static byte[] publicKey(byte[] secretKey) {
        return bytes(mul(G, secret(secretKey)).affine()[0]);
    }

    /** A signature over {@code msg}, with fresh auxiliary randomness. */
    public static byte[] sign(byte[] secretKey, byte[] msg) {
        byte[] aux = new byte[32];
        RANDOM.nextBytes(aux);
        return sign(secretKey, msg, aux);
    }

    /** BIP-340's Sign, with the given 32 bytes of auxiliary randomness. */
    public static byte[] sign(byte[] secretKey, byte[] msg, byte[] aux) {
        if (aux.length != 32) throw new IllegalArgumentException("aux: " + aux.length + " bytes");
        BigInteger d0 = secret(secretKey);
        BigInteger[] pub = mul(G, d0).affine();
        BigInteger d = pub[1].testBit(0) ? N.subtract(d0) : d0;
        byte[] px = bytes(pub[0]);

        byte[] t = bytes(d);
        byte[] auxHash = taggedHash("BIP0340/aux", aux);
        for (int i = 0; i < 32; i++) t[i] ^= auxHash[i];
        BigInteger k0 = new BigInteger(1, taggedHash("BIP0340/nonce", t, px, msg)).mod(N);
        if (k0.signum() == 0) throw new IllegalStateException("BIP-340: zero nonce"); // negligible
        BigInteger[] r = mul(G, k0).affine();
        BigInteger k = r[1].testBit(0) ? N.subtract(k0) : k0;
        byte[] rx = bytes(r[0]);
        BigInteger e = new BigInteger(1, taggedHash("BIP0340/challenge", rx, px, msg)).mod(N);

        byte[] sig = new byte[64];
        System.arraycopy(rx, 0, sig, 0, 32);
        System.arraycopy(bytes(k.add(e.multiply(d)).mod(N)), 0, sig, 32, 32);
        return sig;
    }

    private static BigInteger secret(byte[] secretKey) {
        if (secretKey.length != 32) throw new IllegalArgumentException("secret key: " + secretKey.length + " bytes");
        BigInteger d = new BigInteger(1, secretKey);
        if (d.signum() == 0 || d.compareTo(N) >= 0) throw new IllegalArgumentException("secret key out of range");
        return d;
    }

    static byte[] taggedHash(String tag, byte[]... parts) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            byte[] th = md.digest(tag.getBytes(StandardCharsets.US_ASCII));
            md.update(th);
            md.update(th);
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 32 bytes, big-endian. */
    static byte[] bytes(BigInteger x) {
        byte[] b = x.toByteArray();
        byte[] out = new byte[32];
        int n = Math.min(b.length, 32);
        System.arraycopy(b, b.length - n, out, 32 - n, n);
        return out;
    }

    private static Point mul(Point p, BigInteger k) {
        Point r = Point.INFINITY;
        for (int i = k.bitLength() - 1; i >= 0; i--) {
            r = r.twice();
            if (k.testBit(i)) r = r.plus(p);
        }
        return r;
    }

    /** A point in Jacobian coordinates (x = X/Z², y = Y/Z³); Z = 0 is infinity. */
    private record Point(BigInteger x, BigInteger y, BigInteger z) {
        static final Point INFINITY = new Point(BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);

        boolean infinite() {
            return z.signum() == 0;
        }

        Point twice() {
            if (infinite() || y.signum() == 0) return INFINITY;
            BigInteger y2 = y.multiply(y).mod(P);
            BigInteger s = x.multiply(y2).shiftLeft(2).mod(P);
            BigInteger m = x.multiply(x).multiply(BigInteger.valueOf(3)).mod(P); // a = 0
            BigInteger x3 = m.multiply(m).subtract(s.shiftLeft(1)).mod(P);
            BigInteger y3 = m.multiply(s.subtract(x3)).subtract(y2.multiply(y2).shiftLeft(3)).mod(P);
            BigInteger z3 = y.multiply(z).shiftLeft(1).mod(P);
            return new Point(x3, y3, z3);
        }

        Point plus(Point o) {
            if (infinite()) return o;
            if (o.infinite()) return this;
            BigInteger z1z1 = z.multiply(z).mod(P);
            BigInteger z2z2 = o.z.multiply(o.z).mod(P);
            BigInteger u1 = x.multiply(z2z2).mod(P);
            BigInteger u2 = o.x.multiply(z1z1).mod(P);
            BigInteger s1 = y.multiply(o.z).multiply(z2z2).mod(P);
            BigInteger s2 = o.y.multiply(z).multiply(z1z1).mod(P);
            if (u1.equals(u2)) return s1.equals(s2) ? twice() : INFINITY;
            BigInteger h = u2.subtract(u1).mod(P);
            BigInteger r = s2.subtract(s1).mod(P);
            BigInteger h2 = h.multiply(h).mod(P);
            BigInteger h3 = h2.multiply(h).mod(P);
            BigInteger u1h2 = u1.multiply(h2).mod(P);
            BigInteger x3 = r.multiply(r).subtract(h3).subtract(u1h2.shiftLeft(1)).mod(P);
            BigInteger y3 = r.multiply(u1h2.subtract(x3)).subtract(s1.multiply(h3)).mod(P);
            BigInteger z3 = h.multiply(z).multiply(o.z).mod(P);
            return new Point(x3, y3, z3);
        }

        /** {x, y}. */
        BigInteger[] affine() {
            if (infinite()) throw new IllegalStateException("point at infinity");
            BigInteger zi = z.modInverse(P);
            BigInteger zi2 = zi.multiply(zi).mod(P);
            return new BigInteger[] {x.multiply(zi2).mod(P), y.multiply(zi2).multiply(zi).mod(P)};
        }
    }
}
