package sculk.fabric;

import java.net.URI;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import sculk.core.command.SculkCommand;
import sculk.mc.Chat;

/** {@link sculk.core.platform.Platform#registerCommands} through Fabric API's command events. */
final class SculkCommands {
    private SculkCommands() {}

    static void register(boolean dedicatedServer) {
        if (dedicatedServer) {
            SculkCommand.Reply<CommandSourceStack> reply = (s, line, error) -> {
                Component c = Chat.render(line);
                if (error) s.sendFailure(c);
                else s.sendSuccess(() -> c, false);
            };
            CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> dispatcher.register(
                    SculkCommand.tree(s -> s.hasPermission(SculkCommand.OPERATOR_LEVEL), reply, null)));
        } else {
            Client.register();
        }
    }

    /** Separate so that a dedicated server never loads client classes. */
    private static final class Client {
        static void register() {
            SculkCommand.Reply<FabricClientCommandSource> reply = (s, line, error) -> {
                if (error) s.sendError(Chat.render(line));
                else s.sendFeedback(Chat.render(line));
            };
            ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
                    SculkCommand.tree(s -> true, reply, url -> Util.getPlatform().openUri(URI.create(url)))));
        }
    }
}
