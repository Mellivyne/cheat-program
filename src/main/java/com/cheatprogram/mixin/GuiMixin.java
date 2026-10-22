package com.cheatprogram.mixin;

import com.cheatprogram.hud.HudRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiMixin {
	@Inject(method="renderCrosshair",at=@At("HEAD"),cancellable=true)
	private void cheatprogram$panelCursor(CallbackInfo ci){if(com.cheatprogram.render.WorldControlPanel.inputActive())ci.cancel();}
	@Inject(method = "render", at = @At("TAIL"))
	private void cheatprogram$renderHud(GuiGraphics graphics, DeltaTracker delta, CallbackInfo ci) {
		HudRenderer.render(graphics, delta);
	}
}
