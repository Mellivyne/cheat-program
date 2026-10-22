package com.cheatprogram.mixin;

import com.cheatprogram.render.RearMirrorRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class RearMirrorGameRendererMixin {
	@Inject(method = "render", at = @At("HEAD"))
	private void cheatprogram$mirrorLifecycle(DeltaTracker delta, boolean renderLevel, CallbackInfo ci) {
		RearMirrorRenderer.checkLifecycle();
	}
	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"))
	private void cheatprogram$rearScene(LevelRenderer renderer, GraphicsResourceAllocator allocator, DeltaTracker delta, boolean outline,
			Camera camera, GameRenderer gameRenderer, Matrix4f view, Matrix4f projection, Operation<Void> original) {
		RearMirrorRenderer.capture(renderer, allocator, delta, camera, gameRenderer);
		original.call(renderer, allocator, delta, outline, camera, gameRenderer, view, projection);
	}
}
