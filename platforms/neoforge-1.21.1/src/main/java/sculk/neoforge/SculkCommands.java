package sculk.neoforge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import sculk.core.command.SculkCommand;

/**
 * {@link sculk.core.platform.Platform#registerCommands} through NeoForge's command
 * events. Client and server commands share one source type here.
 */
final class SculkCommands {
    private static final SculkCommand.Reply<CommandSourceStack> REPLY = SculkCommand.Reply.of(
            (s, m) -> s.sendSuccess(() -> Component.literal(m), false),
            (s, m) -> s.sendFailure(Component.literal(m)));

    private SculkCommands() {}

    static void register(boolean dedicatedServer) {
        if (dedicatedServer) {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent e) -> e.getDispatcher().register(
                    SculkCommand.tree(s -> s.hasPermission(SculkCommand.OPERATOR_LEVEL), REPLY)));
        } else {
            Client.register();
        }
    }

    /** Separate so that a dedicated server never loads the client event class. */
    private static final class Client {
        static void register() {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RegisterClientCommandsEvent e) -> e.getDispatcher().register(SculkCommand.tree(s -> true, REPLY)));
        }
    }
}
