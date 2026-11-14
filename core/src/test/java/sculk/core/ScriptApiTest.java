package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.file.Files;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import sculk.core.platform.Side;

class ScriptApiTest {
    @Test
    void scriptsCanObserveDropAndEmit() throws Exception {
        FakeGame.sculk().scripts().load("test.js", """
                sculk.on('packet', e => {
                  if (e.mod && e.mod.channel === 'othermod:secret') {
                    sculk.emit({ saw: e.channel, owner: e.owner, dir: e.frame.dir, len: e.frame.bytes.length,
                                 same: e.len === e.frame.len && e.type === e.packet.type && e.side === e.conn.side && e.side === 'server',
                                 data: e.mod.data, action: e.action });
                    e.drop();
                  }
                });
                """);
        EmbeddedChannel ch = FakeGame.channel(Side.SERVER);
        ch.writeInbound(Unpooled.wrappedBuffer(FakeGame.frame(1, "othermod:secret|pw")));
        assertNull(ch.readInbound());

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        String log = "";
        while (System.nanoTime() < deadline && !log.contains("\"saw\":\"othermod:secret\"")) {
            Thread.sleep(20);
            log = Files.readString(FakeGame.EVENT_LOG);
        }
        assertTrue(log.contains("{\"saw\":\"othermod:secret\",\"owner\":\"othermod\",\"dir\":\"inbound\",\"len\":19,"
                + "\"same\":true,\"data\":\"pw\",\"action\":\"pass\"}"), log);
        assertTrue(log.contains("\"action\":\"drop\""), log);
    }

    @Test
    void scriptsCanEditRecords() {
        Object result = FakeGame.sculk().scripts().eval("""
                sculk.tree(sculk.with(new Packages.sculk.core.FakeGame.Packet(1, 'a'), 'text', 'b'))
                """);
        assertEquals("b", ((java.util.Map<?, ?>) result).get("text"));
    }
}
