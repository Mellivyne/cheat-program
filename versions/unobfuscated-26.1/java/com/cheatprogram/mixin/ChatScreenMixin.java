package com.cheatprogram.mixin;

import com.cheatprogram.hud.GlassChat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"))
	private void cheatprogram$replaceInputBackground(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom, int color, Operation<Void> original) {
		if (!GlassChat.inputBackgroundReplaced()) original.call(graphics, left, top, right, bottom, color);
	}
}
