package com.cheatprogram.mixin;

import com.cheatprogram.hud.GlassChat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.GuiMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {
	@Shadow @Final private List<GuiMessage.Line> trimmedMessages;
	@Shadow private int chatScrollbarPos;
	@Unique private boolean cheatprogram$glassBackground;

	@Inject(method = "render", at = @At("HEAD"))
	private void cheatprogram$glass(GuiGraphics graphics, int tick, int mouseX, int mouseY, CallbackInfo ci) {
		cheatprogram$glassBackground = GlassChat.render(graphics, (ChatComponent) (Object) this, trimmedMessages, chatScrollbarPos, tick, net.minecraft.client.Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.ChatScreen);
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V", ordinal = 0))
	private void cheatprogram$replaceLineBackground(GuiGraphics graphics, int left, int top, int right, int bottom, int color, Operation<Void> original) {
		if (!cheatprogram$glassBackground) original.call(graphics, left, top, right, bottom, color);
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V", ordinal = 2))
	private void cheatprogram$replaceQueueBackground(GuiGraphics graphics, int left, int top, int right, int bottom, int color, Operation<Void> original) {
		if (!cheatprogram$glassBackground) original.call(graphics, left, top, right, bottom, color);
	}
}
