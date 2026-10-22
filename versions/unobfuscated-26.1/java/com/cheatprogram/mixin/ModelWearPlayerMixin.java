package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.ModelWear;import com.mojang.blaze3d.vertex.PoseStack;import net.minecraft.client.renderer.SubmitNodeCollector;import net.minecraft.client.renderer.state.level.CameraRenderState;import net.minecraft.client.renderer.entity.LivingEntityRenderer;import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;import net.minecraft.client.renderer.entity.state.AvatarRenderState;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LivingEntityRenderer.class)
public abstract class ModelWearPlayerMixin {
 @Inject(method="submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",at=@At("HEAD"),cancellable=true)
 private void cheatprogram$replaceLocal(LivingEntityRenderState state,PoseStack pose,SubmitNodeCollector buffers,CameraRenderState camera,CallbackInfo ci){if(state instanceof AvatarRenderState player&&ModelWear.replaces(player.id))ci.cancel();}
}

