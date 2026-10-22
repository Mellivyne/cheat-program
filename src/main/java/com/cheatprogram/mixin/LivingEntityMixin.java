package com.cheatprogram.mixin;

import com.cheatprogram.module.movement.Step;
import com.cheatprogram.util.Targets;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@ModifyReturnValue(method = "maxUpStep", at = @At("RETURN"))
	private float cheatprogram$step(float original) {
		if (!Targets.isLocalPlayer(this)) return original;
		return Step.stepHeight(original, ((LivingEntity) (Object) this).isShiftKeyDown());
	}
}
