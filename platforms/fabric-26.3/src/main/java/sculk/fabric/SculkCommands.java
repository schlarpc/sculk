package sculk.fabric;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import sculk.core.command.SculkCommand;

/** {@link sculk.core.platform.Platform#registerCommands} through Fabric API's command events. */
final class SculkCommands {
    private SculkCommands() {}

    static void register(boolean dedicatedServer) {
        if (dedicatedServer) {
            // 26.3 checks permissions by name; LEVEL_GAMEMASTERS is SculkCommand.OPERATOR_LEVEL.
            SculkCommand.Reply<CommandSourceStack> reply = SculkCommand.Reply.of(
                    (s, m) -> s.sendSuccess(() -> Component.literal(m), false),
                    (s, m) -> s.sendFailure(Component.literal(m)));
            CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
                    SculkCommand.tree(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS), reply)));
        } else {
            Client.register();
        }
    }

    /** Separate so that a dedicated server never loads client classes. */
    private static final class Client {
        static void register() {
            SculkCommand.Reply<FabricClientCommandSource> reply = SculkCommand.Reply.of(
                    (s, m) -> s.sendFeedback(Component.literal(m)),
                    (s, m) -> s.sendError(Component.literal(m)));
            ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                    SculkCommand.tree(s -> true, reply)));
        }
    }
}
