package sculk.core.remote;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * Just enough STUN (RFC 8489) to learn this host's public address: a Binding
 * request, and the XOR-MAPPED-ADDRESS in the success response. {@link WebRtc}
 * sends it from the socket browsers will reach, so the address it learns is
 * the one to offer them as a server-reflexive candidate.
 */
final class Stun {
    /** Public STUN servers, as Trystero and browsers commonly use. */
    static final List<String> SERVERS = List.of("stun.l.google.com:19302", "stun.cloudflare.com:3478");

    static final int MAGIC = 0x2112A442;
    private static final int BINDING_REQUEST = 0x0001;
    private static final int BINDING_SUCCESS = 0x0101;
    private static final int XOR_MAPPED_ADDRESS = 0x0020;
    private static final SecureRandom RANDOM = new SecureRandom();

    private Stun() {}

    /** A new transaction id: 12 random bytes. */
    static byte[] transactionId() {
        byte[] id = new byte[12];
        RANDOM.nextBytes(id);
        return id;
    }

    /** A Binding request with no attributes. */
    static byte[] bindingRequest(byte[] transactionId) {
        ByteBuffer b = ByteBuffer.allocate(20);
        b.putShort((short) BINDING_REQUEST).putShort((short) 0).putInt(MAGIC).put(transactionId);
        return b.array();
    }

    /** Whether a datagram looks like STUN: the two top bits clear and the magic cookie. */
    static boolean isStun(byte[] d) {
        return d.length >= 20 && (d[0] & 0xc0) == 0 && ByteBuffer.wrap(d, 4, 4).getInt() == MAGIC;
    }

    /** The transaction id of a STUN message. */
    static byte[] transactionId(byte[] d) {
        return Arrays.copyOfRange(d, 8, 20);
    }

    /** The mapped address in a Binding success response, or null if it is not one or has none. */
    static InetSocketAddress mappedAddress(byte[] d) {
        if (!isStun(d)) return null;
        ByteBuffer b = ByteBuffer.wrap(d);
        if ((b.getShort(0) & 0xffff) != BINDING_SUCCESS) return null;
        int length = b.getShort(2) & 0xffff;
        if (20 + length > d.length) return null;
        int at = 20;
        while (at + 4 <= 20 + length) {
            int type = b.getShort(at) & 0xffff;
            int len = b.getShort(at + 2) & 0xffff;
            int value = at + 4;
            if (value + len > 20 + length) return null;
            if (type == XOR_MAPPED_ADDRESS && len >= 8) {
                int family = d[value + 1] & 0xff;
                int port = (b.getShort(value + 2) & 0xffff) ^ (MAGIC >>> 16);
                byte[] ip;
                if (family == 1) {
                    ip = Arrays.copyOfRange(d, value + 4, value + 8);
                    for (int i = 0; i < 4; i++) ip[i] ^= d[4 + i];
                } else if (family == 2 && len >= 20) {
                    ip = Arrays.copyOfRange(d, value + 4, value + 20);
                    for (int i = 0; i < 16; i++) ip[i] ^= d[4 + i]; // cookie, then transaction id
                } else {
                    return null;
                }
                try {
                    return new InetSocketAddress(InetAddress.getByAddress(ip), port);
                } catch (UnknownHostException e) {
                    return null;
                }
            }
            at = value + ((len + 3) & ~3); // attributes are padded to 4 bytes
        }
        return null;
    }
}
