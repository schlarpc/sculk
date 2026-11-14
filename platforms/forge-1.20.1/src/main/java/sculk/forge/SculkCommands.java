package sculk.forge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import sculk.core.command.SculkCommand;

/**
 * {@link sculk.core.platform.Platform#registerCommands} through Forge's command
 * events. Client and server commands share one source type here.
 */
final class SculkCommands {
    private static final SculkCommand.Reply<CommandSourceStack> REPLY = SculkCommand.Reply.of(
            (s, m) -> s.sendSuccess(() -> Component.literal(m), false),
            (s, m) -> s.sendFailure(Component.literal(m)));

    private SculkCommands() {}

    static void register(boolean dedicatedServer) {
        if (dedicatedServer) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.RegisterCommandsEvent e) -> e.getDispatcher().register(
                    SculkCommand.tree(s -> s.hasPermission(SculkCommand.OPERATOR_LEVEL), REPLY)));
        } else {
            Client.register();
        }
    }

    /** Separate so that a dedicated server never loads the client event class. */
    private static final class Client {
        static void register() {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.RegisterClientCommandsEvent e) -> e.getDispatcher().register(SculkCommand.tree(s -> true, REPLY)));
        }
    }
}
