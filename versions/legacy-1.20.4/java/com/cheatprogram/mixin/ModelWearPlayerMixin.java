package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.ModelWear;import com.mojang.blaze3d.vertex.PoseStack;import net.minecraft.client.renderer.MultiBufferSource;import net.minecraft.client.renderer.entity.LivingEntityRenderer;import net.minecraft.world.entity.LivingEntity;import net.minecraft.client.player.AbstractClientPlayer;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LivingEntityRenderer.class)
public abstract class ModelWearPlayerMixin {
 @Inject(method="render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",at=@At("HEAD"),cancellable=true)
 private void cheatprogram$replaceLocal(LivingEntity entity,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci){if(entity instanceof AbstractClientPlayer&&ModelWear.replaces(entity.getId()))ci.cancel();}
}
