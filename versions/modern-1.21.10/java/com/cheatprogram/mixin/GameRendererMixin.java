package com.cheatprogram.mixin;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.legit.Zoom;
import com.cheatprogram.module.visual.NoHurtCam;
import com.cheatprogram.render.Render3D;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.vertex.PoseStack;
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
public abstract class GameRendererMixin {
	@Inject(method="render",at=@At("HEAD"))
	private void cheatprogram$pointerLifecycle(DeltaTracker delta,boolean renderLevel,CallbackInfo ci){com.cheatprogram.hud.HudRenderer.beginFrame();com.cheatprogram.render.AppCastPointer.beforeFrame();}
	@WrapOperation(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/gui/screens/Screen;renderWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"))
	private void cheatprogram$fullscreenPointer(net.minecraft.client.gui.screens.Screen screen,net.minecraft.client.gui.GuiGraphics graphics,int x,int y,float partial,Operation<Void> original){
		original.call(screen,graphics,x,y,partial);com.cheatprogram.hud.HudRenderer.afterScreen(graphics,screen);com.cheatprogram.render.AppCastPointer.afterScreen(graphics,screen);
	}
	@Inject(method="renderLevel",at=@At("TAIL"))
	private void cheatprogram$worldColors(DeltaTracker delta, CallbackInfo ci) {
		com.cheatprogram.module.visual.WorldColors colors=com.cheatprogram.module.visual.WorldColors.active();
		if(colors!=null)colors.draw();
	}
	@ModifyReturnValue(method = "getFov", at = @At("RETURN"))
	private float cheatprogram$zoom(float original, Camera camera, float partialTick, boolean useFovSetting) {
		return useFovSetting ? Zoom.apply(original) : original;
	}

	@Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
	private void cheatprogram$noHurtCam(PoseStack poseStack, float partialTick, CallbackInfo ci) {
		if (NoHurtCam.active()) ci.cancel();
	}

	@WrapOperation(method = "renderLevel", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
	private void cheatprogram$render3d(LevelRenderer renderer, GraphicsResourceAllocator allocator, DeltaTracker delta, boolean outline,
									   Camera camera, Matrix4f view, Matrix4f projection, Matrix4f cullingProjection, com.mojang.blaze3d.buffers.GpuBufferSlice fog, org.joml.Vector4f fogColor, boolean sky, Operation<Void> original) {
		original.call(renderer, allocator, delta, outline, camera, view, projection, cullingProjection, fog, fogColor, sky);
		try {
            var saved = new com.cheatprogram.render.RenderStateSnapshot();
            try {
                com.cheatprogram.compat.gl.Framebuffers.bindWrite(net.minecraft.client.Minecraft.getInstance().getMainRenderTarget(), true);
                com.cheatprogram.compat.gl.RenderSystem.setProjectionMatrix(projection, com.mojang.blaze3d.ProjectionType.PERSPECTIVE);
                ModuleManager.onRender3D(new Render3D(camera, view, projection, delta.getGameTimeDeltaPartialTick(true)));
            } finally { saved.restore(); }
		} catch (Throwable t) {
			CheatProgram.LOGGER.error("World overlay rendering failed", t);
		}
	}
}
