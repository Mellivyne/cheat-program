package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.WeaponAnimations;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(ItemInHandRenderer.class)
public abstract class WeaponAnimationsMixin {
 @WrapOperation(method="renderArmWithItem",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/ItemInHandRenderer;applyItemArmTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/HumanoidArm;F)V"))
 private void cheatprogram$weaponRoot(ItemInHandRenderer renderer,PoseStack pose,HumanoidArm arm,float equipped,Operation<Void> original,AbstractClientPlayer player,float partial,float pitch,InteractionHand hand,float swing,ItemStack stack,float handEquipped,PoseStack handPose,MultiBufferSource buffers,int light){
  var animation=WeaponAnimations.forHand(player,hand,stack);
  if(animation==null){original.call(renderer,pose,arm,equipped);return;}
  int side=arm==HumanoidArm.RIGHT?1:-1;float root=Mth.sqrt(swing);
  pose.translate(side*.4f*Mth.sin(root*Mth.PI),-.2f*Mth.sin(root*Mth.TWO_PI),.2f*Mth.sin(swing*Mth.PI));
  animation.renderSwing(player,hand,stack,pose,arm,swing,equipped);
 }
 @WrapOperation(method="renderArmWithItem",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/ItemInHandRenderer;applyItemArmAttackTransform(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/entity/HumanoidArm;F)V"))
 private void cheatprogram$weaponAttack(ItemInHandRenderer renderer,PoseStack pose,HumanoidArm arm,float swing,Operation<Void> original,AbstractClientPlayer player,float partial,float pitch,InteractionHand hand,float handSwing,ItemStack stack,float handEquipped,PoseStack handPose,MultiBufferSource buffers,int light){if(WeaponAnimations.forHand(player,hand,stack)==null)original.call(renderer,pose,arm,swing);}
}
