package com.cheatprogram.mixin;

import com.cheatprogram.module.legit.TimeChanger;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(net.minecraft.client.ClientClockManager.class)
public abstract class ClientLevelDataMixin {
	@ModifyReturnValue(method = "getTotalTicks", at = @At("RETURN"))
	private long cheatprogram$dayTime(long original, net.minecraft.core.Holder<net.minecraft.world.clock.WorldClock> clock) {
		return clock.is(net.minecraft.world.clock.WorldClocks.OVERWORLD) ? TimeChanger.apply(original) : original;
	}
}
