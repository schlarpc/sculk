package sculk.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import testmod.FakeModNetworking;

class AttributionTest {
    @Test
    void namespaceOfALoadedModWins() {
        assertEquals("othermod", FakeGame.sculk().attribution().owner("othermod:x", null));
    }

    @Test
    void registrationStackAttributesForeignNamespaces() {
        FakeModNetworking.register("unknownns:thing");
        assertEquals(List.of("testmod"), FakeGame.sculk().attribution().registeredVia("unknownns:thing"));
        assertEquals("testmod", FakeGame.sculk().attribution().owner("unknownns:thing", null));
    }
}
