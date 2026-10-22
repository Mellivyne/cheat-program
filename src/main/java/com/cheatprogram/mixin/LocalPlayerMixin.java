package com.cheatprogram.mixin;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.movement.NoSlow;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	@WrapOperation(method = {"aiStep", "shouldStopSprinting", "canStartSprinting"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
	private boolean cheatprogram$noSlow(LocalPlayer self, Operation<Boolean> original) {
		if (ModuleManager.on(NoSlow.class)) return false;
		return original.call(self);
	}
}
