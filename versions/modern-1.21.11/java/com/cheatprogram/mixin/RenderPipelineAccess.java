package com.cheatprogram.mixin;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(RenderPipeline.class)
public interface RenderPipelineAccess {
    @Accessor("vertexShader") Identifier cheatprogram$vertexShader();
    @Accessor("fragmentShader") Identifier cheatprogram$fragmentShader();
    @Accessor("shaderDefines") ShaderDefines cheatprogram$defines();
    @Accessor("depthBiasScaleFactor") float cheatprogram$biasScale();
    @Accessor("depthBiasConstant") float cheatprogram$biasConstant();

    @Mixin(net.minecraft.client.renderer.rendertype.RenderType.class)
    public interface RenderTypeSetupAccess {
        @Accessor("state") net.minecraft.client.renderer.rendertype.RenderSetup cheatprogram$setup();
    }
    @Mixin(net.minecraft.client.renderer.rendertype.RenderSetup.class)
    public interface RenderSetupAccess {
        @Accessor("textureTransform") net.minecraft.client.renderer.rendertype.TextureTransform cheatprogram$textureTransform();
        @Accessor("layeringTransform") net.minecraft.client.renderer.rendertype.LayeringTransform cheatprogram$layeringTransform();
    }
    @Mixin(net.minecraft.client.renderer.texture.AbstractTexture.class)
    public interface TextureSamplerAccess {
        @Accessor("sampler") void cheatprogram$sampler(com.mojang.blaze3d.textures.GpuSampler sampler);
    }
}
