package com.cheatprogram.mixin;
import com.cheatprogram.core.ModuleManager;import com.cheatprogram.module.visual.DroppedItems3D;import com.cheatprogram.compat.legacy.LegacyItemRenderState;
import com.llamalad7.mixinextras.injector.wrapoperation.*;import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;import net.minecraft.client.renderer.MultiBufferSource;import net.minecraft.client.renderer.entity.ItemEntityRenderer;import net.minecraft.client.renderer.entity.ItemRenderer;import net.minecraft.client.resources.model.BakedModel;import net.minecraft.world.entity.item.ItemEntity;import net.minecraft.world.item.ItemDisplayContext;import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.At;
@Mixin(ItemEntityRenderer.class)
public abstract class DroppedItems3DMixin {
 @WrapOperation(method="render(Lnet/minecraft/world/entity/item/ItemEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/entity/ItemRenderer;render(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;IILnet/minecraft/client/resources/model/BakedModel;)V"))
 private void cheatprogram$dropped(ItemRenderer renderer,ItemStack stack,ItemDisplayContext context,boolean left,PoseStack pose,MultiBufferSource buffers,int light,int overlay,BakedModel model,Operation<Void> original,ItemEntity entity,float yaw,float partial,PoseStack entityPose,MultiBufferSource entityBuffers,int entityLight){
  DroppedItems3D module=ModuleManager.get(DroppedItems3D.class);
  if(module==null||!module.isEnabled()){original.call(renderer,stack,context,left,pose,buffers,light,overlay,model);return;}
  LegacyItemRenderState state=new LegacyItemRenderState();state.item=new LegacyItemRenderState.Item(model);state.count=1;state.ageInTicks=entity.getAge()+partial;state.bobOffset=entity.bobOffs;
  var frame=module.extract(entity,state,partial);if(frame==null){original.call(renderer,stack,context,left,pose,buffers,light,overlay,model);return;}
  module.render(pose,state,frame,()->original.call(renderer,stack,context,left,pose,buffers,light,overlay,model));
 }
}
