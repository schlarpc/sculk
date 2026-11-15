package sculk.core.script;

import java.util.List;
import java.util.Map;
import sculk.core.Sculk;
import sculk.core.game.GameState;
import sculk.core.net.SculkConnection;
import sculk.core.platform.Side;
import sculk.shaded.org.mozilla.javascript.Scriptable;

/**
 * {@code sculk.game}: what the connections' packets have said about the
 * game ({@link GameState}), so a script can tell what an entity id or a
 * container id in a packet stands for. Without a connection argument,
 * each lookup asks the connection whose packet is being handled, else the
 * game client's own connection, else the first open one. Values are plain
 * script objects, or null when nothing is known.
 */
public final class Game {
    private final Sculk engine;
    private final Scriptable scope;

    Game(Sculk engine, Scriptable scope) {
        this.engine = engine;
        this.scope = scope;
    }

    /** Entity {@code id}: {id, type, uuid, spawnPos, name (players), self (ours)}, or null. */
    public Object entity(int id) { return entity(id, null); }

    public Object entity(int id, SculkConnection conn) {
        GameState s = state(conn);
        return s == null ? null : ScriptHost.jsTree(s.entity(id), scope);
    }

    /** The open menu with this container id: {containerId, type, title}, or null. */
    public Object menu(int containerId) { return menu(containerId, null); }

    public Object menu(int containerId, SculkConnection conn) {
        GameState s = state(conn);
        return s == null ? null : ScriptHost.jsTree(s.menu(containerId), scope);
    }

    /** All open menus. */
    public Object menus() {
        GameState s = state(null);
        return ScriptHost.jsTree(s == null ? List.of() : s.menus(), scope);
    }

    /** Who we are and how much is tracked: {self: {id, uuid, name}, entities, players, menus}. */
    public Object summary() {
        GameState s = state(null);
        return ScriptHost.jsTree(s == null ? Map.of() : s.summary(), scope);
    }

    private GameState state(SculkConnection conn) {
        if (conn != null) return conn.gameState();
        var channel = Sculk.currentChannel();
        SculkConnection current = channel == null ? null : SculkConnection.peek(channel);
        if (current != null) return current.gameState();
        SculkConnection first = null;
        for (SculkConnection c : engine.connections()) {
            if (c.sideEnum() == Side.CLIENT) return c.gameState();
            if (first == null) first = c;
        }
        return first == null ? null : first.gameState();
    }
}
