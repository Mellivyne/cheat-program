package com.cheatprogram.mixin;

import com.cheatprogram.util.AuraKnockbackFilter;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LivingEntity.class)
public abstract class AuraKnockbackLivingMixin {
	@WrapMethod(method = "knockback")
	private void cheatprogram$observeKnockback(double strength, double x, double z, Operation<Void> original) {
		LivingEntity victim = (LivingEntity) (Object) this;
		var probe = AuraKnockbackFilter.beginKnockback(victim);
		Vec3 before = probe == null ? null : victim.getDeltaMovement();
		original.call(strength, x, z);
		if (probe != null) AuraKnockbackFilter.observedKnockback(probe, before, victim.getDeltaMovement());
	}
}
