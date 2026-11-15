package sculktest.neoforge;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import sculktest.Report;
import sculktest.Sink;
import sculktest.TestMod;

/** The integration test mod on NeoForge: registration and events; the rest is {@link TestMod}. */
@Mod(TestMod.MOD_ID)
public final class SculkTestNeoForge {
    public SculkTestNeoForge(IEventBus modBus) {
        TestMod.init(new TestMod.Net() {
            @Override
            public void toClient(ServerPlayer player, Sink sink) {
                PacketDistributor.sendToPlayer(player, sink);
            }

            @Override
            public void toServer(Report report) {
                Client.send(report);
            }
        });
        modBus.addListener(RegisterPayloadHandlersEvent.class, e -> e.registrar("1")
                .playToClient(Sink.TYPE, Sink.STREAM_CODEC, (sink, ctx) -> TestMod.onSink(sink, ctx.player()))
                .playToServer(Report.TYPE, Report.STREAM_CODEC,
                        (report, ctx) -> TestMod.onReport(report, (ServerPlayer) ctx.player())));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
            if (e.getEntity() instanceof ServerPlayer p) TestMod.joined(p);
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, e -> TestMod.tick());
    }

    /** Separate so that a dedicated server never loads client classes. */
    private static final class Client {
        static void send(Report report) {
            net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(report);
        }
    }
}
