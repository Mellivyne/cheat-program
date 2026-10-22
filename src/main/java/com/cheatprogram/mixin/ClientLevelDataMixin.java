package com.cheatprogram.mixin;

import com.cheatprogram.module.legit.TimeChanger;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientLevel.ClientLevelData.class)
public abstract class ClientLevelDataMixin {
	@ModifyReturnValue(method = "getDayTime", at = @At("RETURN"))
	private long cheatprogram$dayTime(long original) {
		return TimeChanger.apply(original);
	}
}
