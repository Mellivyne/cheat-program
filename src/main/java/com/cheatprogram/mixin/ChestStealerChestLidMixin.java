package com.cheatprogram.mixin;

import com.cheatprogram.render.ChestContainerVisuals;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({ChestBlockEntity.class, EnderChestBlockEntity.class})
public abstract class ChestStealerChestLidMixin {
	@Inject(method = "getOpenNess", at = @At("RETURN"), cancellable = true)
	private void cheatprogram$confirmedAutomaticLid(float partialTick, CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue(ChestContainerVisuals.openness((BlockEntity) (Object) this, partialTick, cir.getReturnValue()));
	}
}
