package com.cheatprogram.mixin;

import com.cheatprogram.module.visual.Esp;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class EntityMixin {
	@ModifyReturnValue(method = "getTeamColor", at = @At("RETURN"))
	private int cheatprogram$espColor(int original) {
		return Esp.glowing((Entity) (Object) this) ? 0xFFFFFF : original;
	}
}
