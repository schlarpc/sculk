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
}
