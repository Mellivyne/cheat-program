package com.cheatprogram.mixin;

import com.cheatprogram.util.InteractionLook;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One real body/input/sendPosition scope; camera restoration never sends a reverse packet. */
@Mixin(LocalPlayer.class)
public abstract class AuraMovementMixin {
	@Shadow protected abstract boolean isControlledCamera();

	@Inject(method = "sendPosition", at = @At("TAIL"))
	private void cheatprogram$movementSent(CallbackInfo ci) {
		if (isControlledCamera()) InteractionLook.movementSent();
	}

	@WrapMethod(method = "tick")
	private void cheatprogram$interactionMovement(Operation<Void> original) {
		boolean started = InteractionLook.beginMovement();
		boolean completed = false;
		try { original.call(); completed = true; }
		finally { InteractionLook.endMovement(started, completed); }
	}
}
