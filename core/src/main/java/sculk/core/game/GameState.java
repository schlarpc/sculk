package sculk.core.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one connection's packets have said about the game, for
 * {@code sculk.game}: the entities it has spawned and not removed, the
 * players it has listed, the container menus it has opened and not closed,
 * and who "we" are. The platform feeds it ({@code Platform.observe}) as
 * packets pass, before scripts see them, from the packets' meaning rather
 * than their direction: a server-side connection learns the same from what
 * it sends as a client does from what it receives.
 */
public final class GameState {
    /** Entities kept at most; past it, new ones are not tracked (a leak guard, far above any real view distance). */
    static final int MAX_ENTITIES = 100_000;

    private final Map<Integer, Map<String, Object>> entities = new ConcurrentHashMap<>();
    private final Map<String, String> playerNames = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, Object>> menus = new ConcurrentHashMap<>();
    private volatile Integer selfId;
    private volatile String selfUuid;
    private volatile String selfName;

    // ---- fed by the platform ----

    /** We joined (or the login said who we are). */
    public void self(String uuid, String name) {
        if (uuid != null) selfUuid = uuid;
        if (name != null) selfName = name;
        if (uuid != null && name != null) playerNames.put(uuid, name);
    }

    /** The login packet: our entity id; a new world, so everything else is gone. */
    public void login(int entityId) {
        selfId = entityId;
        entities.clear();
        menus.clear();
    }

    /** A respawn or dimension change: entities are sent anew; we keep our id and our menus close on their own. */
    public void respawn() {
        entities.clear();
    }

    public void addEntity(int id, String uuid, String type, double x, double y, double z) {
        if (entities.size() >= MAX_ENTITIES && !entities.containsKey(id)) return;
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("id", id);
        e.put("type", type);
        e.put("uuid", uuid);
        e.put("spawnPos", Map.of("x", x, "y", y, "z", z));
        entities.put(id, e);
    }

    public void removeEntity(int id) {
        entities.remove(id);
    }

    public void playerName(String uuid, String name) {
        if (uuid != null && name != null) playerNames.put(uuid, name);
    }

    public void openMenu(int containerId, String type, String title) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("containerId", containerId);
        m.put("type", type);
        m.put("title", title);
        menus.put(containerId, m);
    }

    public void closeMenu(int containerId) {
        menus.remove(containerId);
    }

    // ---- read by scripts ----

    /**
     * What we know of entity {@code id}: {id, type, uuid, spawnPos, and
     * name for players}, or {id, self: true, ...} for our own; null if
     * this connection has not spawned it (or has removed it).
     */
    public Map<String, Object> entity(int id) {
        Map<String, Object> e = entities.get(id);
        if (e == null) {
            if (selfId == null || selfId != id) return null;
            e = new LinkedHashMap<>();
            e.put("id", id);
            e.put("type", "minecraft:player");
            if (selfUuid != null) e.put("uuid", selfUuid);
        } else {
            e = new LinkedHashMap<>(e);
        }
        if (selfId != null && selfId == id) e.put("self", true);
        Object uuid = e.get("uuid");
        String name = uuid == null ? null : playerNames.get(uuid.toString());
        if (name == null && selfId != null && selfId == id) name = selfName;
        if (name != null) e.put("name", name);
        return e;
    }

    /** The open menu {containerId, type, title}, or null. */
    public Map<String, Object> menu(int containerId) {
        Map<String, Object> m = menus.get(containerId);
        return m == null ? null : new LinkedHashMap<>(m);
    }

    /** All open menus, by container id. */
    public List<Map<String, Object>> menus() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : menus.values()) out.add(new LinkedHashMap<>(m));
        return out;
    }

    /** Our own entity id, or null before the login packet. */
    public Integer selfId() {
        return selfId;
    }

    /** {self, entities, players, menus}: counts and who we are. */
    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> self = new LinkedHashMap<>();
        if (selfId != null) self.put("id", selfId);
        if (selfUuid != null) self.put("uuid", selfUuid);
        if (selfName != null) self.put("name", selfName);
        m.put("self", self);
        m.put("entities", entities.size());
        m.put("players", playerNames.size());
        m.put("menus", menus.size());
        return m;
    }
}
