package sculktest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The integration test mod's behaviour, the same on every loader: each
 * loader's glue registers {@link Sink} and {@link Report} and calls in here.
 *
 * <ul>
 *   <li>Server: two seconds after a player joins, send them the expected sink.</li>
 *   <li>Client: check each sink against the expected one, log what differs,
 *       and report back with the sink echoed.</li>
 *   <li>Server: log each report.</li>
 * </ul>
 * scripts/integ.py reads the "[sculktest]" lines from the game logs.
 */
public final class TestMod {
    public static final String MOD_ID = "sculktest";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);
    private static final int DELAY_TICKS = 40;
    private static final Map<ServerPlayer, Integer> pending = new ConcurrentHashMap<>();

    /** What the loader's networking does. */
    public interface Net {
        void toClient(ServerPlayer player, Sink sink);

        void toServer(Report report);
    }

    private static Net net;

    private TestMod() {}

    public static void init(Net loaderNet) {
        net = loaderNet;
        LOG.info("[sculktest] loaded");
    }

    public static void joined(ServerPlayer player) {
        pending.put(player, DELAY_TICKS);
    }

    /** Once per server tick. */
    public static void tick() {
        for (Map.Entry<ServerPlayer, Integer> e : pending.entrySet()) {
            int left = e.getValue() - 1;
            if (left > 0) {
                e.setValue(left);
                continue;
            }
            pending.remove(e.getKey());
            ServerPlayer player = e.getKey();
            if (player.hasDisconnected()) continue;
            net.toClient(player, Expected.sink(player.registryAccess()));
            LOG.info("[sculktest] server sent sink to {}", player.getGameProfile().getName());
        }
    }

    /** On the client, on the game thread. */
    public static void onSink(Sink sink, Player player) {
        List<String> differences = Expected.differences(Expected.sink(player.registryAccess()), sink);
        String edited = sink.tag().getString("edited");
        LOG.info("[sculktest] client got sink: matched={} differences={} edited={}", differences.isEmpty(), differences, edited);
        net.toServer(new Report(edited.isEmpty() ? "natural" : "edited: " + edited, differences.isEmpty(), differences, sink));
    }

    /** On the server, on the game thread. */
    public static void onReport(Report report, ServerPlayer player) {
        List<String> echoDifferences = Expected.differences(Expected.sink(player.registryAccess()), report.echo());
        LOG.info("[sculktest] server got report: note={} matched={} differences={} echoDifferences={}",
                report.note(), report.matched(), report.differences(), echoDifferences);
    }
}
