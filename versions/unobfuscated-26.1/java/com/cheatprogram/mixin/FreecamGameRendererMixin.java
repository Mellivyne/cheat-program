package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.Freecam;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRenderer.class)
public abstract class FreecamGameRendererMixin {
    @Inject(method="renderItemInHand",at=@At("HEAD"),cancellable=true)
    private void cheatprogram$hideHand(CameraRenderState camera,float partial,Matrix4fc view,CallbackInfo ci){if(Freecam.isActive())ci.cancel();}
    @Inject(method="bobView",at=@At("HEAD"),cancellable=true)
    private void cheatprogram$noBodyBob(CameraRenderState camera,PoseStack pose,CallbackInfo ci){if(Freecam.isActive())ci.cancel();}
    @Inject(method="bobHurt",at=@At("HEAD"),cancellable=true)
    private void cheatprogram$noBodyHurt(CameraRenderState camera,PoseStack pose,CallbackInfo ci){if(Freecam.isActive())ci.cancel();}
}
