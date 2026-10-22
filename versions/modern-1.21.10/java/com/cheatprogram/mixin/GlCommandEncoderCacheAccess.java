package com.cheatprogram.mixin;
import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(GlCommandEncoder.class)
public interface GlCommandEncoderCacheAccess {
    @Accessor("lastPipeline") void cheatprogram$lastPipeline(RenderPipeline pipeline);
    @Accessor("lastProgram") void cheatprogram$lastProgram(GlProgram program);
}
