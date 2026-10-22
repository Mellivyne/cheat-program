package com.cheatprogram.mixin;

import com.cheatprogram.render.InventoryGlass;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

@Mixin({InventoryScreen.class, CreativeModeInventoryScreen.class})
public abstract class InventoryGlassBackgroundMixin {
	@Inject(method = "extractBackground", at = @At("HEAD"))
	private void cheatprogram$glassFrame(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		InventoryGlass.beginBackground();
	}
	@Redirect(method = "extractBackground", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"))
	private void cheatprogram$glassBackground(GuiGraphicsExtractor graphics, com.mojang.blaze3d.pipeline.RenderPipeline type, Identifier texture,
			int x, int y, float u, float v, int width, int height, int textureWidth, int textureHeight) {
		if (!InventoryGlass.background(graphics, (AbstractContainerScreen<?>) (Object) this, x, y, width, height))
			graphics.blit(type, texture, x, y, u, v, width, height, textureWidth, textureHeight);
	}
	@ModifyArg(method = "extractLabels", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V"), index = 4)
	private int cheatprogram$whiteLabels(int color) { return InventoryGlass.enabled((Screen) (Object) this) ? 0xF8FFFFFF : color; }
}
