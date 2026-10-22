package com.cheatprogram.mixin;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.module.movement.AirWalk;
import com.cheatprogram.module.movement.FlatFlight;
import com.cheatprogram.module.world.Scaffold;
import com.cheatprogram.module.world.TargetMotionSupport;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class FreecamKeyboardInputMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$separateCameraInput(CallbackInfo ci) {
		// ModuleManager already cleared user body input, then AI wrote its independent input.
		if (Freecam.isActive()) ci.cancel();
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void cheatprogram$aiInput(CallbackInfo ci) {
		Scaffold.applyInput((KeyboardInput) (Object) this);
		com.cheatprogram.util.InteractionLook.applyInput((KeyboardInput) (Object) this);
		AirWalk.applyInput((KeyboardInput) (Object) this);
		FlatFlight.applyInput((KeyboardInput) (Object) this);
		TargetMotionSupport.applyInput((KeyboardInput) (Object) this);
		AiController.applyInput((KeyboardInput) (Object) this);
	}
}
