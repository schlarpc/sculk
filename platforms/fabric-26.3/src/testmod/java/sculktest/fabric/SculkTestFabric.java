package sculktest.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import sculktest.Report;
import sculktest.Sink;
import sculktest.TestMod;

/** The integration test mod on Fabric: registration and events; the rest is {@link TestMod}. */
public final class SculkTestFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        TestMod.init(new TestMod.Net() {
            @Override
            public void toClient(ServerPlayer player, Sink sink) {
                ServerPlayNetworking.send(player, sink);
            }

            @Override
            public void toServer(Report report) {
                SculkTestFabricClient.send(report);
            }
        });
        PayloadTypeRegistry.clientboundPlay().register(Sink.TYPE, Sink.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Report.TYPE, Report.STREAM_CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Report.TYPE, (report, ctx) -> TestMod.onReport(report, ctx.player()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> TestMod.joined(handler.getPlayer()));
        ServerTickEvents.END_SERVER_TICK.register(server -> TestMod.tick());
    }
}
