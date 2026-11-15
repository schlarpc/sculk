package sculktest.forge;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import sculktest.Report;
import sculktest.Sink;
import sculktest.TestMod;

/**
 * The integration test mod on Forge: one SimpleChannel, sculktest:main, with
 * the sink (0, to clients) and the report (1, to the server).
 */
@Mod(TestMod.MOD_ID)
public final class SculkTestForge {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(TestMod.MOD_ID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    public SculkTestForge() {
        CHANNEL.messageBuilder(Sink.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Sink::write).decoder(Sink::read)
                .consumerMainThread((sink, ctx) -> TestMod.onSink(sink))
                .add();
        CHANNEL.messageBuilder(Report.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Report::write).decoder(Report::read)
                .consumerMainThread((report, ctx) -> TestMod.onReport(report, ctx.get().getSender()))
                .add();
        TestMod.init(new TestMod.Net() {
            @Override
            public void toClient(ServerPlayer player, Sink sink) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), sink);
            }

            @Override
            public void toServer(Report report) {
                CHANNEL.sendToServer(report);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) TestMod.joined(p);
        });
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) TestMod.tick();
        });
    }
}
