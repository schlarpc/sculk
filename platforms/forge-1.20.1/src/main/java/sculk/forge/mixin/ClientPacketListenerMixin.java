package sculk.forge.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraftforge.client.ClientCommandHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a clicked chat button run {@code /sculk}. Forge 1.20.1 runs client
 * commands only when typed ({@code sendCommand}); a click goes through
 * {@code sendUnsignedCommand} straight to the server, which has no such
 * command. NeoForge checks client commands there too; this does the same,
 * for sculk's commands only, so other mods' clicks behave as before.
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @Inject(method = "sendUnsignedCommand", at = @At("HEAD"), cancellable = true)
    private void sculk$clientCommand(String command, CallbackInfoReturnable<Boolean> cir) {
        if ((command.equals("sculk") || command.startsWith("sculk ")) && ClientCommandHandler.runCommand(command)) {
            cir.setReturnValue(true);
        }
    }
}
