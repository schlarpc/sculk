package sculk.core.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class StunTest {
    /** A Binding success response saying the asker is {@code mapped} (IPv4). */
    private static byte[] success(byte[] transactionId, InetSocketAddress mapped) {
        ByteBuffer b = ByteBuffer.allocate(32);
        b.putShort((short) 0x0101).putShort((short) 12).putInt(Stun.MAGIC).put(transactionId);
        b.putShort((short) 0x0020).putShort((short) 8).put((byte) 0).put((byte) 1);
        b.putShort((short) (mapped.getPort() ^ (Stun.MAGIC >>> 16)));
        b.putInt(ByteBuffer.wrap(mapped.getAddress().getAddress()).getInt() ^ Stun.MAGIC);
        return b.array();
    }

    private static InetSocketAddress at(String ip, int port) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(ip), port);
    }

    @Test
    void readsTheMappedAddress() throws Exception {
        byte[] id = Stun.transactionId();
        assertEquals(at("203.0.113.7", 40000), Stun.mappedAddress(success(id, at("203.0.113.7", 40000))));
        assertNull(Stun.mappedAddress(Stun.bindingRequest(id)));
    }

    @Test
    void onlyTheServerAskedCanAnswer() throws Exception {
        byte[] id = Stun.transactionId();
        InetSocketAddress server = at("192.0.2.1", 3478);
        byte[] reply = success(id, at("203.0.113.7", 40000));
        assertTrue(Stun.answers(reply, id, server, at("192.0.2.1", 3478)));
        // The right transaction id is not enough: it must come from where the request went.
        assertFalse(Stun.answers(reply, id, server, at("192.0.2.66", 3478)));
        assertFalse(Stun.answers(reply, id, server, at("192.0.2.1", 3479)));
        assertFalse(Stun.answers(reply, Stun.transactionId(), server, server));
        assertFalse(Stun.answers(reply, null, null, server), "nothing was asked");
        assertFalse(Stun.answers(new byte[] {1, 2, 3}, id, server, server));
    }
}
