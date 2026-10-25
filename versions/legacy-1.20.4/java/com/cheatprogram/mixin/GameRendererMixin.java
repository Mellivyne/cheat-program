package com.cheatprogram.mixin;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.legit.Zoom;
import com.cheatprogram.module.visual.NoHurtCam;
import com.cheatprogram.render.Render3D;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Inject(method="render",at=@At("HEAD"))
	private void cheatprogram$pointerLifecycle(float delta,long frameTime,boolean renderLevel,CallbackInfo ci){com.cheatprogram.hud.HudRenderer.beginFrame();com.cheatprogram.render.AppCastPointer.beforeFrame();}
	@WrapOperation(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/Screen;renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
	private void cheatprogram$fullscreenPointer(net.minecraft.client.gui.screens.Screen screen,net.minecraft.client.gui.GuiGraphics graphics,int x,int y,float partial,Operation<Void> original){
		original.call(screen,graphics,x,y,partial);com.cheatprogram.hud.HudRenderer.afterScreen(graphics,screen);com.cheatprogram.render.AppCastPointer.afterScreen(graphics,screen);
	}
	@Inject(method="renderLevel",at=@At("TAIL"))
	private void cheatprogram$worldColors(float delta,long frameTime,PoseStack pose,CallbackInfo ci) {
		com.cheatprogram.module.visual.WorldColors colors=com.cheatprogram.module.visual.WorldColors.active();
		if(colors!=null)colors.draw();
	}
	@ModifyReturnValue(method = "getFov", at = @At("RETURN"))
	private double cheatprogram$zoom(double original, Camera camera, float partialTick, boolean useFovSetting) {
		return useFovSetting ? Zoom.apply((float)original) : original;
	}

	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$noHurtCam(PoseStack poseStack, float partialTick, CallbackInfo ci) {
		if (NoHurtCam.active()) ci.cancel();
	}

    @WrapOperation(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V"))
    private void cheatprogram$render3d(LevelRenderer renderer,PoseStack pose,float partial,long frameTime,boolean outline,Camera camera,GameRenderer gameRenderer,net.minecraft.client.renderer.LightTexture light,Matrix4f projection,Operation<Void> original){
        original.call(renderer,pose,partial,frameTime,outline,camera,gameRenderer,light,projection);
        com.cheatprogram.compat.legacy.LegacyRenderSystem.getModelViewStack().set(com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix());
        try{ModuleManager.onRender3D(new Render3D(camera,new Matrix4f(pose.last().pose()),projection,partial));}catch(Throwable t){CheatProgram.LOGGER.error("World overlay rendering failed",t);}
    }
}
