package com.cheatprogram.mixin;
import com.cheatprogram.util.InventoryPoses;import com.cheatprogram.compat.legacy.LegacyPlayerRenderPose;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;import net.minecraft.client.renderer.MultiBufferSource;import net.minecraft.client.renderer.entity.player.PlayerRenderer;import net.minecraft.client.player.AbstractClientPlayer;import org.spongepowered.asm.mixin.Mixin;
@Mixin(PlayerRenderer.class)
public abstract class InventoryPoseMixin {
 @WrapMethod(method="render(Lnet/minecraft/client/player/AbstractClientPlayer;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V")
 private void cheatprogram$pose(AbstractClientPlayer p,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light,Operation<Void> original){
  LegacyPlayerRenderPose state=new LegacyPlayerRenderPose();state.isUsingItem=p.isUsingItem();state.bodyRot=p.yBodyRot;state.yRot=p.yHeadRot-p.yBodyRot;state.xRot=p.getXRot();state.attackTime=p.attackAnim;state.swinging=p.swinging;
  if(!InventoryPoses.applyRenderPose(p,state,partial)){original.call(p,yaw,partial,pose,buffers,light);return;}
  float body=p.yBodyRot,bodyOld=p.yBodyRotO,head=p.yHeadRot,headOld=p.yHeadRotO,x=p.getXRot(),xOld=p.xRotO,attack=p.attackAnim,attackOld=p.oAttackAnim;boolean swing=p.swinging;
  try{p.yBodyRot=p.yBodyRotO=state.bodyRot;p.yHeadRot=p.yHeadRotO=state.bodyRot+state.yRot;p.setXRot(state.xRot);p.xRotO=state.xRot;p.attackAnim=p.oAttackAnim=state.attackTime;p.swinging=state.swinging;original.call(p,yaw,partial,pose,buffers,light);}
  finally{p.yBodyRot=body;p.yBodyRotO=bodyOld;p.yHeadRot=head;p.yHeadRotO=headOld;p.setXRot(x);p.xRotO=xOld;p.attackAnim=attack;p.oAttackAnim=attackOld;p.swinging=swing;}
 }
}
