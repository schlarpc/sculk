package sculk.neoforge;

import java.net.URI;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import sculk.core.command.SculkCommand;
import sculk.mc.Chat;

/**
 * {@link sculk.core.platform.Platform#registerCommands} through NeoForge's command
 * events. Client and server commands share one source type here.
 */
final class SculkCommands {
    private static final SculkCommand.Reply<CommandSourceStack> REPLY = (s, line, error) -> {
        Component c = Chat.render(line);
        if (error) s.sendFailure(c);
        else s.sendSuccess(() -> c, false);
    };

    private SculkCommands() {}

    static void register(boolean dedicatedServer) {
        if (dedicatedServer) {
            // 26.3 checks permissions by name; LEVEL_GAMEMASTERS is SculkCommand.OPERATOR_LEVEL.
            NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> e.getDispatcher().register(
                    SculkCommand.tree(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS), REPLY, null)));
        } else {
            Client.register();
        }
    }

    /** Separate so that a dedicated server never loads the client event class. */
    private static final class Client {
        static void register() {
            NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RegisterClientCommandsEvent e) -> e.getDispatcher().register(
                    SculkCommand.tree(s -> true, REPLY, url -> Blaze3D.openUri(URI.create(url)))));
        }
    }
}
