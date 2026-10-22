package com.cheatprogram.mixin;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(RenderPipeline.class)
public interface RenderPipelineAccess {
    @Accessor("vertexShader") ResourceLocation cheatprogram$vertexShader();
    @Accessor("fragmentShader") ResourceLocation cheatprogram$fragmentShader();
    @Accessor("shaderDefines") ShaderDefines cheatprogram$defines();
    @Accessor("depthBiasScaleFactor") float cheatprogram$biasScale();
    @Accessor("depthBiasConstant") float cheatprogram$biasConstant();
}
