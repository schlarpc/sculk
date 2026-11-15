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
                                 data: e.packet.data, action: e.action });
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
    void connectionsCanBeFoundById() {
        EmbeddedChannel ch = FakeGame.channel(Side.CLIENT);
        int id = sculk.core.net.SculkConnection.peek(ch).getId();
        assertEquals("client", FakeGame.sculk().scripts().eval("sculk.connection(" + id + ").side"));
        assertNull(FakeGame.sculk().scripts().eval("sculk.connection(-1)"));
    }

    @Test
    void scriptsCanEditRecords() {
        Object result = FakeGame.sculk().scripts().eval("""
                sculk.tree(sculk.with(new Packages.sculk.core.FakeGame.Packet(1, 'a'), 'text', 'b'))
                """);
        assertEquals("b", ((java.util.Map<?, ?>) result).get("text"));
    }

    /** A mod payload that keeps its content as raw bytes, as AE2's GuiDataSyncPacket does. */
    public record Blob(int id, byte[] data) {}

    @Test
    void decodersShowOpaqueBytesTheirWay() throws Exception {
        var scripts = FakeGame.sculk().scripts();
        scripts.load("blob-decoder.js", """
                sculk.decoder('sculk.core.ScriptApiTest$Blob', (obj, tree) => {
                  const r = sculk.reader(obj.data());
                  tree.fields = [];
                  while (r.readable() > 0) tree.fields.push({ key: r.u16(), name: r.string() });
                  delete tree.data;
                });
                """);
        try {
            // 0x0064 "hi", 0x0065 "yo"
            Object tree = scripts.eval("JSON.stringify(sculk.tree(new Packages.sculk.core.ScriptApiTest.Blob(5, sculk.unhex('0064026869' + '006502796f'))))");
            assertEquals("{\"$class\":\"sculk.core.ScriptApiTest$Blob\",\"id\":5,"
                    + "\"fields\":[{\"key\":100,\"name\":\"hi\"},{\"key\":101,\"name\":\"yo\"}]}",
                    tree);
            // Nested: a list of them decodes each.
            Object nested = sculk.core.model.ObjectModel.toTree(java.util.List.of(new Blob(1, new byte[] {0, 1, 1, 'z'})), 4);
            assertEquals("z", ((java.util.Map<?, ?>) ((java.util.List<?>) ((java.util.Map<?, ?>) ((java.util.List<?>) nested).get(0)).get("fields")).get(0)).get("name"));
        } finally {
            scripts.stop("blob-decoder.js");
        }
        Object plain = sculk.core.model.ObjectModel.toTree(new Blob(1, new byte[] {1}), 4);
        assertEquals(java.util.Map.of("$bytes", "01"), ((java.util.Map<?, ?>) plain).get("data"), "the decoder left with its script");
    }

    @Test
    void aFailingDecoderShowsThePlainTree() throws Exception {
        var scripts = FakeGame.sculk().scripts();
        scripts.load("bad-decoder.js", """
                sculk.decoder('sculk.core.ScriptApiTest$Blob', (obj, tree) => { sculk.reader(obj.data()).i32(); });
                """);
        try {
            Object tree = sculk.core.model.ObjectModel.toTree(new Blob(2, new byte[] {1}), 4);
            assertEquals(java.util.Map.of("$bytes", "01"), ((java.util.Map<?, ?>) tree).get("data"));
        } finally {
            scripts.stop("bad-decoder.js");
        }
    }

    @Test
    void readersReadWhatTheGameWrites() {
        var scripts = FakeGame.sculk().scripts();
        // varint 300, i32 -2, string "é", bool, i64 2^60, varLong 1, uuid, f32 1.5
        String hex = "ac02" + "fffffffe" + "02c3a9" + "01" + "1000000000000000" + "01"
                + "00000000000000010000000000000002" + "3fc00000";
        Object read = scripts.eval("""
                (() => { const r = sculk.reader('%s');
                  return [r.varInt(), r.i32(), r.string(), r.bool(), r.i64(), r.varLong(), r.uuid(), r.f32(), r.readable()].join(','); })()
                """.formatted(hex));
        assertEquals("300,-2,é,true,1152921504606846976,1,00000000-0000-0001-0000-000000000002,1.5,0", read);
    }

    @Test
    void treesAreScriptObjects() {
        Object r = FakeGame.sculk().scripts().eval(
                "const t = sculk.tree(new Packages.sculk.core.FakeGame.Packet(1, 'a')); [t.text, t.id + 1, Object.keys(t).length].join('|')");
        assertEquals("a|2|3", r);
    }
}
