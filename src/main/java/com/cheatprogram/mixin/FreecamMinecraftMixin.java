package com.cheatprogram.mixin;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.module.visual.Freecam;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class FreecamMinecraftMixin {
	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$noManualAttack(CallbackInfoReturnable<Boolean> cir) {
		if (Freecam.isActive() || AiController.ownsMining()) cir.setReturnValue(false);
	}
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$noManualMining(boolean held, CallbackInfo ci) {
		if (Freecam.isActive() || AiController.ownsMining()) ci.cancel();
	}
	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$noManualUse(CallbackInfo ci) { if (Freecam.isActive()) ci.cancel(); }

	@Redirect(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;consumeClick()Z"))
	private boolean cheatprogram$separateBodyActions(KeyMapping key) {
		if (Freecam.isActive()) {
			var options = Minecraft.getInstance().options;
			boolean bodyAction = key == options.keyAttack || key == options.keyUse || key == options.keyPickItem
					|| key == options.keyDrop || key == options.keySwapOffhand;
			for (KeyMapping hotbar : options.keyHotbarSlots) bodyAction |= key == hotbar;
			if (bodyAction) { while (key.consumeClick()) { } return false; }
		}
		return key.consumeClick();
	}
}
