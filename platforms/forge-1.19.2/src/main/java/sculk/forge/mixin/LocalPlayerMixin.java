package sculk.forge.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.client.ClientCommandHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets a clicked chat button run {@code /sculk}. In 1.19.2 a click goes
 * through {@code LocalPlayer.commandUnsigned} straight to the server, past
 * Forge's client commands. This runs sculk's commands on the client
 * instead; other mods' clicks behave as before.
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
    @Inject(method = "commandUnsigned", at = @At("HEAD"), cancellable = true)
    private void sculk$clientCommand(String command, CallbackInfoReturnable<Boolean> cir) {
        if ((command.equals("sculk") || command.startsWith("sculk ")) && ClientCommandHandler.runCommand(command)) {
            cir.setReturnValue(true);
        }
    }
}
