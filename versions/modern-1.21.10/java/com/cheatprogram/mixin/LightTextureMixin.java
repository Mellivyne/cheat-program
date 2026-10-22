package com.cheatprogram.mixin;

import com.cheatprogram.module.visual.Fullbright;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
	/** The fourth float uniform written while updating the lightmap is NightVisionFactor. */
	@ModifyArg(method = "updateLightTexture", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/buffers/Std140Builder;putFloat(F)Lcom/mojang/blaze3d/buffers/Std140Builder;", ordinal = 3))
	private float cheatprogram$fullbright(float nightVision) {
		return Fullbright.active() ? 1.0f : nightVision;
	}
}
