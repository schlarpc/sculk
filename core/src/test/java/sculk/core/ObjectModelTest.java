package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import sculk.core.model.ObjectModel;

class ObjectModelTest {
    record Inner(String name, byte[] data) {}

    record Outer(int count, List<Inner> items) {}

    @Test
    void recordsBecomeTrees() {
        Object tree = ObjectModel.toTree(new Outer(2, List.of(new Inner("a", new byte[] {1, 2}))), 4);
        Map<?, ?> m = (Map<?, ?>) tree;
        assertEquals(2, m.get("count"));
        Map<?, ?> inner = (Map<?, ?>) ((List<?>) m.get("items")).get(0);
        assertEquals("a", inner.get("name"));
        assertEquals(Map.of("$bytes", "0102"), inner.get("data"));
    }

    @Test
    void canonicalFormIgnoresCollectionOrder() {
        assertEquals(
                ObjectModel.canonical(new Outer(1, List.of(new Inner("a", null), new Inner("b", null)))),
                ObjectModel.canonical(new Outer(1, List.of(new Inner("b", null), new Inner("a", null)))));
    }

    @Test
    void recordComponentsCanBeReplacedWithScriptNumbers() throws Exception {
        Object edited = ObjectModel.withComponent(new Outer(2, List.of()), "count", 7.0d);
        assertEquals(new Outer(7, List.of()), edited);
    }

    record Ident(String namespace, String path) {}

    record Holder(Ident id, java.util.Map<Ident, Integer> levels) {}

    @Test
    void aRendererShowsGameTypesItsWayIncludingAsMapKeys() {
        ObjectModel.setRenderer((value, depth) ->
                value instanceof Ident i ? i.namespace() + ":" + i.path() : ObjectModel.UNHANDLED);
        try {
            Map<?, ?> m = (Map<?, ?>) ObjectModel.toTree(
                    new Holder(new Ident("a", "b"), Map.of(new Ident("x", "y"), 3)), 4);
            assertEquals("a:b", m.get("id"));
            assertEquals(Map.of("x:y", 3), m.get("levels"));
        } finally {
            ObjectModel.setRenderer(null);
        }
    }

    @Test
    void aFailingRendererFallsBackToReflection() {
        ObjectModel.setRenderer((value, depth) -> {
            throw new IllegalStateException("broken");
        });
        try {
            Map<?, ?> m = (Map<?, ?>) ObjectModel.toTree(new Ident("a", "b"), 4);
            assertEquals("a", m.get("namespace"));
        } finally {
            ObjectModel.setRenderer(null);
        }
    }

    @Test
    void buffersShowTheirReadableBytes() {
        io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.wrappedBuffer(new byte[] {1, 2, 3});
        buf.readByte();
        assertEquals(Map.of("$bytes", "0203"), ObjectModel.toTree(buf, 2));
        assertEquals(1, buf.readerIndex());
    }
}
