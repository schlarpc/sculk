package sculktest.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import sculktest.Report;
import sculktest.Sink;
import sculktest.TestMod;

public final class SculkTestFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(Sink.TYPE, (sink, ctx) -> TestMod.onSink(sink, ctx.player()));
    }

    static void send(Report report) {
        ClientPlayNetworking.send(report);
    }
}
