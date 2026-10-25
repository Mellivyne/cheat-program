package com.cheatprogram.mixin;

import com.cheatprogram.render.InventoryGlass;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

@Mixin(CreativeModeInventoryScreen.class)
public abstract class InventoryGlassCreativeMixin {
	@Redirect(method = "renderTabButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
	private void cheatprogram$glassTab(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height) {
		if (!InventoryGlass.control(graphics, (Screen) (Object) this, x, y, width, height, !sprite.getPath().contains("unselected"), false))
			graphics.blitSprite(sprite, x, y, width, height);
	}
	@Redirect(method = "renderBg", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
	private void cheatprogram$glassScroller(GuiGraphics graphics, ResourceLocation sprite, int x, int y, int width, int height) {
		if (!InventoryGlass.control(graphics, (Screen) (Object) this, x, y, width, height, true, sprite.getPath().contains("disabled")))
			graphics.blitSprite(sprite, x, y, width, height);
	}
}
