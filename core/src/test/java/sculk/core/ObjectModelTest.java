package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void bytesShowTheirTextAndLengthWhenCut() {
        byte[] terminal = "Up to 8 item IDs\u00f0\u00f0ab\u00f0> diamond_block   ".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        Map<?, ?> t = (Map<?, ?>) ObjectModel.toTree(terminal, 2);
        assertEquals("Up to 8 item IDs\n> diamond_block   ", t.get("$text"), "runs of 4+ printable characters, one per line");
        assertEquals(null, t.get("$len"), "not cut");

        Map<?, ?> big = (Map<?, ?>) ObjectModel.toTree(new byte[5000], 2);
        assertEquals(5000, big.get("$len"));
        assertEquals(ObjectModel.MAX_BYTES * 2 + 3, ((String) big.get("$bytes")).length());
        assertEquals(null, big.get("$text"));
    }

    @Test
    void oneTreeShowsOnlySoManyBytesInAll() {
        List<byte[]> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) many.add(new byte[2048]);
        List<?> tree = (List<?>) ObjectModel.toTree(many, 3);
        int hex = 0;
        for (Object o : tree) hex += ((String) ((Map<?, ?>) o).get("$bytes")).replace("...", "").length();
        assertEquals(ObjectModel.TREE_BYTES * 2, hex);
        assertEquals("...", ((Map<?, ?>) tree.get(19)).get("$bytes"));
        assertEquals(2048, ((Map<?, ?>) tree.get(19)).get("$len"));
        // ...and the budget is per tree: the next one starts afresh.
        assertEquals(5000, ((Map<?, ?>) ObjectModel.toTree(new byte[5000], 2)).get("$len"));
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

    record Property(String name, String value, String signature) {}

    /** Shaped like authlib's GameProfile: plain fields, properties in a Guava multimap. */
    static final class Profile {
        private final String id = "d959";
        private final String name = "schlarpc";
        private final com.google.common.collect.Multimap<String, Property> properties =
                com.google.common.collect.LinkedHashMultimap.create();
    }

    @Test
    void guavaMultimapsShowAsTheirMapOfLists() {
        Profile p = new Profile();
        p.properties.put("textures", new Property("textures", "ewog", "sig"));
        Map<?, ?> tree = (Map<?, ?>) ObjectModel.toTree(p, 6);
        assertEquals(Map.of("textures", List.of(Map.of("$class", Property.class.getName(), "name", "textures",
                "value", "ewog", "signature", "sig"))), tree.get("properties"));
        assertEquals("schlarpc", tree.get("name"));
    }

    @Test
    void guavaTablesShowAsTheirRows() {
        com.google.common.collect.Table<String, String, Integer> t = com.google.common.collect.HashBasedTable.create();
        t.put("worldGen", "enabled", 1);
        t.put("teleports", "cost", 3);
        record Schema(com.google.common.collect.Table<String, String, Integer> properties) {}
        Map<?, ?> tree = (Map<?, ?>) ObjectModel.toTree(new Schema(t), 6);
        assertEquals(Map.of("worldGen", Map.of("enabled", 1), "teleports", Map.of("cost", 3)), tree.get("properties"));
    }

    record Holder2(java.util.function.Function<Integer, Integer> serializer, int value) {}

    @Test
    void lambdasAreNamedNotWalked() {
        int captured = 7;
        Map<?, ?> tree = (Map<?, ?>) ObjectModel.toTree(new Holder2(x -> x + captured, 2), 6);
        assertEquals("(lambda) Function", tree.get("serializer"));
        assertEquals(2, tree.get("value"));
    }

    @Test
    void oneTreeHasOnlySoManyValues() {
        // 64 x 64 x 64 would be 262,144 leaves.
        Object[][][] cube = new Object[64][64][64];
        for (Object[][] plane : cube) for (Object[] row : plane) java.util.Arrays.fill(row, "stone");
        String json = sculk.core.log.EventLog.toJson(ObjectModel.toTree(cube, 8));
        assertTrue(json.length() < ObjectModel.TREE_NODES * 12, "bounded: " + json.length());
        assertTrue(json.contains(ObjectModel.CUT));
        // ...and the budget is per tree.
        assertEquals("stone", ((List<?>) ((List<?>) ((List<?>) ObjectModel.toTree(cube, 8)).get(0)).get(0)).get(0));
    }

    @Test
    void jdkClassesShowAsTheirString() {
        var key = new javax.crypto.spec.SecretKeySpec(new byte[] {1, 2}, "AES");
        assertTrue(ObjectModel.toTree(key, 4) instanceof String);
    }
}
