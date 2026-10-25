package com.cheatprogram.mixin;

import com.cheatprogram.render.InventoryGlass;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

@Mixin(InventoryScreen.class)
public abstract class InventoryGlassBackgroundMixin {
	@Inject(method = "renderBg", at = @At("HEAD"))
	private void cheatprogram$glassFrame(GuiGraphics graphics, float partialTick, int mouseX, int mouseY, CallbackInfo ci) {
		InventoryGlass.beginBackground();
	}
	@Redirect(method = "renderBg", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIIII)V"))
	private void cheatprogram$glassBackground(GuiGraphics graphics, ResourceLocation texture,
			int x, int y, int u, int v, int width, int height) {
		if (!InventoryGlass.background(graphics, (AbstractContainerScreen<?>) (Object) this, x, y, width, height))
			graphics.blit(texture, x, y, u, v, width, height);
	}
	@ModifyArg(method = "renderLabels", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"), index = 4)
	private int cheatprogram$whiteLabels(int color) { return InventoryGlass.enabled((Screen) (Object) this) ? 0xF8FFFFFF : color; }
}
