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
			target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
	private void cheatprogram$rearScene(LevelRenderer renderer, GraphicsResourceAllocator allocator, DeltaTracker delta, boolean outline,
			net.minecraft.client.renderer.state.level.CameraRenderState camera, org.joml.Matrix4fc view, com.mojang.blaze3d.buffers.GpuBufferSlice fog, org.joml.Vector4f fogColor, boolean sky, Operation<Void> original) {
		RearMirrorRenderer.capture(renderer, allocator, delta, net.minecraft.client.Minecraft.getInstance().gameRenderer.mainCamera(), net.minecraft.client.Minecraft.getInstance().gameRenderer, fog, fogColor, sky);
		original.call(renderer, allocator, delta, outline, camera, view, fog, fogColor, sky);
	}
}
