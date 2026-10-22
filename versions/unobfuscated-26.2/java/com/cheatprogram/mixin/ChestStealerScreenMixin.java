package com.cheatprogram.mixin;

import com.cheatprogram.module.inventory.ChestStealer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(net.minecraft.client.gui.Gui.class)
public abstract class ChestStealerScreenMixin {
	@Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$silentContainer(Screen screen, CallbackInfo ci) {
		if (ChestStealer.openedScreen(screen)) ci.cancel();
	}
}
