package dev.technix.logic.mixin;

import dev.technix.logic.Logic;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.impl.ClickGui;
import dev.technix.logic.event.impl.KeyPressEvent;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = KeyboardHandler.class, priority = 1100)
public class KeyboardHandlerMixin {

    @Inject(method = "charTyped(JLnet/minecraft/client/input/CharacterEvent;)V", at = @At("HEAD"), cancellable = true)
    private void logic$character(long window, CharacterEvent event, CallbackInfo ci) {
        if (!LogicClient.running()) return;
        ClickGui gui = LogicClient.MODULES.get(ClickGui.class);
        if (gui.open()) {
            gui.handleCharacter(event.codepoint());
            ci.cancel();
        }
    }

    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), cancellable = true)
    private void logic$keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (!LogicClient.running()) return;
        ClickGui gui = (ClickGui) LogicClient.MODULES.byName("ClickGUI").orElse(null);
        if (gui != null && gui.open()) {
            gui.handleKey(event.key(), action);
            ci.cancel();
            return;
        }
        Logic.events().post(new KeyPressEvent(event.key(), action));
        if (gui != null && gui.open()) ci.cancel();
    }
}
