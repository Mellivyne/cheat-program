package com.cheatprogram.mixin;
import com.cheatprogram.compat.NativeBlockEntityBaker;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Diverts genuine vanilla model/text/block meshes only during explicit blueprint baking. */
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class NativeGeometryCaptureMixin {
 @Inject(method="getVertexBuilder",at=@At("HEAD"),cancellable=true)
 private void cheatprogram$capture(RenderType type,CallbackInfoReturnable<VertexConsumer> cir){
  var target=NativeBlockEntityBaker.destination();if(target!=null)cir.setReturnValue(target.getBuffer(type));
 }
}
