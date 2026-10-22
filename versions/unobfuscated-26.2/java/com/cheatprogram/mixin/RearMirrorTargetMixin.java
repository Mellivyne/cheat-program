package com.cheatprogram.mixin;

import com.cheatprogram.render.RearMirrorRenderer;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(net.minecraft.client.renderer.GameRenderer.class)
public abstract class RearMirrorTargetMixin {
	@ModifyReturnValue(method = "mainRenderTarget", at = @At("RETURN"))
	private RenderTarget cheatprogram$mirrorTarget(RenderTarget original) {
		RenderTarget mirror = RearMirrorRenderer.activeTarget();
		return mirror == null ? original : mirror;
	}
}
