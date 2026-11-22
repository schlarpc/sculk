package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * BIP-340's own test vectors (bip-0340/test-vectors.csv in bitcoin/bips,
 * BSD-2-Clause): every row with a secret key is a signing vector.
 */
class Bip340Test {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void signingVectors() throws Exception {
        int signed = 0;
        try (var in = new BufferedReader(new InputStreamReader(
                Bip340Test.class.getResourceAsStream("/sculk/remote/bip340-vectors.csv"), StandardCharsets.UTF_8))) {
            in.readLine(); // header
            for (String line; (line = in.readLine()) != null; ) {
                String[] f = line.split(",", -1);
                if (f[1].isEmpty()) continue; // verification-only vector
                byte[] sk = HEX.parseHex(f[1]);
                byte[] msg = HEX.parseHex(f[4]);
                assertArrayEquals(HEX.parseHex(f[2]), Bip340.publicKey(sk), "public key, vector " + f[0]);
                assertArrayEquals(HEX.parseHex(f[5]), Bip340.sign(sk, msg, HEX.parseHex(f[3])), "signature, vector " + f[0]);
                signed++;
            }
        }
        assertEquals(8, signed);
    }

    @Test
    void randomKeysSign() {
        byte[] sk = Bip340.newSecretKey();
        assertEquals(32, Bip340.publicKey(sk).length);
        assertEquals(64, Bip340.sign(sk, new byte[32]).length);
    }
}
