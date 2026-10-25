package com.cheatprogram.mixin;

import com.cheatprogram.render.RearMirrorRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.Camera;

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
	private void cheatprogram$mirrorLifecycle(float delta,long frameTime,boolean renderLevel, CallbackInfo ci) {
		RearMirrorRenderer.checkLifecycle();
	}
    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V"))
    private void cheatprogram$rearScene(LevelRenderer renderer,com.mojang.blaze3d.vertex.PoseStack pose,float partial,long frameTime,boolean outline,Camera camera,GameRenderer gameRenderer,net.minecraft.client.renderer.LightTexture light,Matrix4f projection,Operation<Void> original){
        RearMirrorRenderer.capture(renderer,partial,frameTime,camera,gameRenderer,light);
        original.call(renderer,pose,partial,frameTime,outline,camera,gameRenderer,light,projection);
    }
}
