package sculk.core.packet;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrameLayerTest {
    /** A custom payload frame as every supported version writes it: id, channel string, payload. */
    private static byte[] customPayload(int id, String channel, byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, id);
        byte[] name = channel.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, name.length);
        out.writeBytes(name);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static void writeVarInt(ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    private static List<String> describe(List<Span> spans) {
        return spans.stream().map(Span::toString).toList();
    }

    @Test
    void customPayloadSplitsIntoIdChannelAndData() {
        byte[] frame = customPayload(200, "waystones:sync", new byte[] {1, 2, 3});
        // 200 takes two VarInt bytes; the channel is one length byte and 14 characters.
        assertEquals(List.of("packet.id[0+2]", "mod.channel[2+15]", "mod.data[17+3]"),
                describe(FrameLayer.spans(frame, "waystones:sync")));
    }

    @Test
    void channelThatDoesNotMatchTheBytesLeavesABody() {
        byte[] frame = customPayload(25, "other:channel", new byte[] {1});
        assertEquals(List.of("packet.id[0+1]", "packet.body[1+15]"), describe(FrameLayer.spans(frame, "waystones:sync")));
    }

    @Test
    void vanillaPacketsHaveIdAndBody() {
        assertEquals(List.of("packet.id[0+1]", "packet.body[1+8]"), describe(FrameLayer.spans(new byte[9], null)));
        assertEquals(List.of("packet.id[0+1]"), describe(FrameLayer.spans(new byte[] {7}, null)));
        assertEquals(List.of(), describe(FrameLayer.spans(null, null)));
        assertEquals(List.of(), describe(FrameLayer.spans(new byte[] {(byte) 0x80}, null)), "a cut-off id");
    }
}
