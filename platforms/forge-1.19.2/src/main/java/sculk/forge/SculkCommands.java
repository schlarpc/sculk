package sculk.forge;

import java.net.URI;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import sculk.core.command.SculkCommand;

/**
 * {@link sculk.core.platform.Platform#registerCommands} through Forge's command
 * events. Client and server commands share one source type here.
 */
final class SculkCommands {
    private static final SculkCommand.Reply<CommandSourceStack> REPLY = (s, line, error) -> {
        Component c = Chat.render(line);
        if (error) s.sendFailure(c);
        else s.sendSuccess(c, false);
    };

    private SculkCommands() {}

    static void register(boolean dedicatedServer) {
        if (dedicatedServer) {
            MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                    SculkCommand.tree(s -> s.hasPermission(SculkCommand.OPERATOR_LEVEL), REPLY, null)));
        } else {
            Client.register();
        }
    }

    /** Separate so that a dedicated server never loads the client event class. */
    private static final class Client {
        static void register() {
            MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.RegisterClientCommandsEvent e) -> e.getDispatcher().register(
                    SculkCommand.tree(s -> true, REPLY, url -> Util.getPlatform().openUri(URI.create(url)))));
        }
    }
}
