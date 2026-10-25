package com.cheatprogram.mixin;

import com.cheatprogram.module.visual.Freecam;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class FreecamGameRendererMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$hideHand(PoseStack pose, Camera camera, float partialTick, CallbackInfo ci) {
		if (Freecam.isActive()) ci.cancel();
	}
	@Inject(method = "bobView", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$noBodyBob(PoseStack pose, float partialTick, CallbackInfo ci) { if (Freecam.isActive()) ci.cancel(); }
	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$noBodyHurt(PoseStack pose, float partialTick, CallbackInfo ci) { if (Freecam.isActive()) ci.cancel(); }
}
