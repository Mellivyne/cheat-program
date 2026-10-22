package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.ModelWear;import com.mojang.blaze3d.vertex.PoseStack;import net.minecraft.client.renderer.MultiBufferSource;import net.minecraft.client.renderer.entity.LivingEntityRenderer;import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;import net.minecraft.client.renderer.entity.state.PlayerRenderState;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LivingEntityRenderer.class)
public abstract class ModelWearPlayerMixin {
 @Inject(method="render(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",at=@At("HEAD"),cancellable=true)
 private void cheatprogram$replaceLocal(LivingEntityRenderState state,PoseStack pose,MultiBufferSource buffers,int light,CallbackInfo ci){if(state instanceof PlayerRenderState player&&ModelWear.replaces(player.id))ci.cancel();}
}

