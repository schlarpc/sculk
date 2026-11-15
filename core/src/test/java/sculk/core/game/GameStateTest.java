package sculk.core.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class GameStateTest {
    @Test
    void followsEntitiesPlayersMenusAndUs() {
        GameState s = new GameState();
        s.self("u-me", "Me");
        s.login(7);
        s.playerName("u-alex", "Alex");
        s.addEntity(12, "u-alex", "minecraft:player", 1, 2, 3);
        s.addEntity(13, "u-cow", "minecraft:cow", 0, 0, 0);
        s.openMenu(4, "ae2:pattern_provider", "Pattern Provider");

        assertEquals("Alex", s.entity(12).get("name"));
        assertEquals(Map.of("x", 1.0, "y", 2.0, "z", 3.0), s.entity(12).get("spawnPos"));
        assertEquals("minecraft:cow", s.entity(13).get("type"));
        assertNull(s.entity(13).get("name"));
        assertEquals(Map.of("id", 7, "type", "minecraft:player", "uuid", "u-me", "self", true, "name", "Me"), s.entity(7),
                "our own entity is never spawned, but we know it");
        assertEquals("Pattern Provider", s.menu(4).get("title"));

        s.removeEntity(13);
        s.closeMenu(4);
        assertNull(s.entity(13));
        assertNull(s.menu(4));

        s.respawn();
        assertNull(s.entity(12), "a respawn sends entities anew");
        assertEquals(7, s.entity(7).get("id"));
        s.login(9);
        assertNull(s.entity(7));
        assertEquals(9, s.summary().get("self") instanceof Map<?, ?> m ? m.get("id") : null);
    }
}
