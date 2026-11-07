package dev.technix.logic.mixin;

import dev.technix.logic.client.LogicClient;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Font.class)
public abstract class FontMixin {
    @ModifyVariable(method = {
        "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
        "width(Ljava/lang/String;)I"
    }, at = @At("HEAD"), argsOnly = true)
    private String logic$hideNames(String text) { return LogicClient.displayName(text); }

    @ModifyVariable(method = {
        "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
        "prepare8xTextOutline(Lnet/minecraft/util/FormattedCharSequence;FFI)Lnet/minecraft/client/gui/Font$PreparedText;",
        "width(Lnet/minecraft/util/FormattedCharSequence;)I"
    }, at = @At("HEAD"), argsOnly = true)
    private FormattedCharSequence logic$hideStyledNames(FormattedCharSequence text) {
        return (FormattedCharSequence) LogicClient.rewriteDisplayText(text);
    }

    @ModifyVariable(method = {
        "width(Lnet/minecraft/network/chat/FormattedText;)I"
    }, at = @At("HEAD"), argsOnly = true)
    private FormattedText logic$hideComponentNames(FormattedText text) {
        return (FormattedText) LogicClient.rewriteDisplayText(text);
    }
}
