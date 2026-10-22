package com.cheatprogram.mixin;

import com.cheatprogram.hud.GlassChat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
	private void cheatprogram$replaceInputBackground(GuiGraphics graphics, int left, int top, int right, int bottom, int color, Operation<Void> original) {
		if (!GlassChat.inputBackgroundReplaced()) original.call(graphics, left, top, right, bottom, color);
	}
}
