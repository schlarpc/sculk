package sculk.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sculk.core.FakeGame.Packet;
import sculk.core.net.Direction;
import sculk.core.net.SculkConnection;
import sculk.core.packet.PacketEvent;
import sculk.core.platform.Side;

class WirePlumbingTest {
    private final List<PacketEvent> seen = new CopyOnWriteArrayList<>();
    private Consumer<PacketEvent> behaviour = e -> {};

    @BeforeEach
    void listen() {
        FakeGame.sculk().addPacketListener(e -> {
            if (e.getConnection().channel() == channel) {
                seen.add(e);
                behaviour.accept(e);
            }
        });
    }

    private EmbeddedChannel channel = FakeGame.channel(Side.CLIENT);

    private SculkConnection conn() {
        return SculkConnection.peek(channel);
    }

    @Test
    void inboundFramesArePairedWithDecodedPackets() {
        channel.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:sync|hello")));
        assertEquals(new Packet(1, "othermod:sync|hello"), channel.readInbound());
        PacketEvent e = seen.get(0);
        assertEquals(Direction.INBOUND, e.getDirection());
        assertEquals("othermod:sync", e.getChannel());
        assertEquals("othermod", e.getOwner());
        assertArrayEquals(FakeGame.frame(1, "othermod:sync|hello"), e.getFrame());
        assertFalse(e.isInjected());
        assertEquals(Side.CLIENT, e.getConnection().side());
    }

    @Test
    void outboundPacketsArePairedWithEncodedFrames() {
        channel.writeOutbound(new Packet(7, "pos"));
        ByteBuf wire = channel.readOutbound();
        assertArrayEquals(FakeGame.frame(7, "pos"), ByteBufUtil.getBytes(wire));
        assertArrayEquals(FakeGame.frame(7, "pos"), seen.get(0).getFrame());
        assertEquals("minecraft", seen.get(0).getOwner());
    }

    @Test
    void droppedInboundPacketsNeverReachTheGame() {
        behaviour = PacketEvent::drop;
        channel.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(3, "x")));
        assertNull(channel.readInbound());
    }

    @Test
    void droppedOutboundPacketsBecomeEmptyWrites() {
        behaviour = PacketEvent::drop;
        channel.writeOutbound(new Packet(3, "x"));
        ByteBuf wire = channel.readOutbound();
        assertEquals(0, wire.readableBytes());
    }

    @Test
    void framesCanBeRewrittenInBothDirections() {
        behaviour = e -> e.replaceFrame(FakeGame.frame(9, "edited"));
        channel.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(3, "orig")));
        assertEquals(new Packet(9, "edited"), channel.readInbound());
        channel.writeOutbound(new Packet(3, "orig"));
        assertArrayEquals(FakeGame.frame(9, "edited"), ByteBufUtil.getBytes((ByteBuf) channel.readOutbound()));
    }

    @Test
    void packetsCanBeReplacedInBothDirections() {
        behaviour = e -> e.replacePacket(new Packet(5, "swapped"));
        channel.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(3, "orig")));
        assertEquals(new Packet(5, "swapped"), channel.readInbound());
        channel.writeOutbound(new Packet(3, "orig"));
        assertArrayEquals(FakeGame.frame(5, "swapped"), ByteBufUtil.getBytes((ByteBuf) channel.readOutbound()));
    }

    @Test
    void injectedInboundFramesAreDecodedAndMarked() {
        conn().sendFrame(Direction.INBOUND, FakeGame.frame(4, "forged"));
        channel.runPendingTasks();
        assertEquals(new Packet(4, "forged"), channel.readInbound());
        assertTrue(seen.get(0).isInjected());
    }

    @Test
    void injectedInboundPacketsSkipTheDecoder() {
        conn().sendPacket(Direction.INBOUND, new Packet(4, "object"));
        channel.runPendingTasks();
        assertEquals(new Packet(4, "object"), channel.readInbound());
        assertTrue(seen.get(0).isInjected());
        assertArrayEquals(FakeGame.frame(4, "object"), seen.get(0).getFrame());
    }

    @Test
    void injectedOutboundFramesBypassTheEncoder() {
        conn().sendFrame(Direction.OUTBOUND, FakeGame.frame(6, "raw"));
        channel.runPendingTasks();
        assertArrayEquals(FakeGame.frame(6, "raw"), ByteBufUtil.getBytes((ByteBuf) channel.readOutbound()));
        assertEquals(new Packet(6, "raw"), seen.get(0).getPacket());
        assertTrue(seen.get(0).isInjected());
    }

    @Test
    void injectedOutboundPacketsAreEncodedAndMarked() {
        conn().sendPacket(Direction.OUTBOUND, new Packet(6, "obj"));
        channel.runPendingTasks();
        assertArrayEquals(FakeGame.frame(6, "obj"), ByteBufUtil.getBytes((ByteBuf) channel.readOutbound()));
        assertTrue(seen.get(0).isInjected());
    }

    @Test
    void replaySendsTheSameBytesAgain() {
        channel.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(2, "once")));
        channel.readInbound();
        conn().replay(seen.get(0));
        channel.runPendingTasks();
        assertEquals(new Packet(2, "once"), channel.readInbound());
        assertEquals(2, seen.size());
        assertNotNull(seen.get(1).getFrame());
    }
}
