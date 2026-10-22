package com.cheatprogram.mixin;

import com.cheatprogram.module.inventory.ChestStealer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class ChestStealerContainerPanelMixin {
	@Shadow protected int leftPos;
	@Shadow protected int topPos;
	@Shadow protected int imageWidth;
	@Shadow protected int imageHeight;
	@Inject(method = "render", at = @At("TAIL"))
	private void cheatprogram$liveAutomaticContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		ChestStealer.renderContainerPanel(graphics, (AbstractContainerScreen<?>) (Object) this, leftPos, topPos, imageWidth, imageHeight);
	}
}
