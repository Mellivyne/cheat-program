package com.cheatprogram.mixin;
import com.cheatprogram.compat.NativePreviewRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer.class)
public abstract class NativeParticleTargetMixin {
 @WrapOperation(method="executeGroup",at=@At(value="INVOKE",target="Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass(Ljava/util/function/Supplier;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/Optional;Lcom/mojang/blaze3d/textures/GpuTextureView;Ljava/util/OptionalDouble;)Lcom/mojang/blaze3d/systems/RenderPass;"))
 private RenderPass cheatprogram$privateParticles(CommandEncoder encoder,Supplier<String> label,GpuTextureView color,Optional<Vector4fc> clear,GpuTextureView depth,OptionalDouble clearDepth,Operation<RenderPass> original){
  var target=NativePreviewRenderer.target();return original.call(encoder,label,target==null?color:target.getColorTextureView(),clear,target==null?depth:target.getDepthTextureView(),clearDepth);
 }
}
