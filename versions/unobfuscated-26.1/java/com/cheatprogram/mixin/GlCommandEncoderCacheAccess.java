package com.cheatprogram.mixin;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(targets="com.mojang.blaze3d.opengl.GlCommandEncoder")
public interface GlCommandEncoderCacheAccess {
    @Accessor("lastPipeline") void cheatprogram$lastPipeline(RenderPipeline pipeline);
    @Accessor("lastProgram") void cheatprogram$lastProgram(GlProgram program);

    @Mixin(com.mojang.blaze3d.systems.GpuDevice.class)
    public interface DeviceBackendAccess {
        @Accessor("backend") com.mojang.blaze3d.systems.GpuDeviceBackend cheatprogram$backend();
    }
    @Mixin(targets="com.mojang.blaze3d.opengl.GlDevice")
    public interface OpenGlBackendAccess {
        @Accessor("directStateAccess") com.mojang.blaze3d.opengl.DirectStateAccess cheatprogram$directStateAccess();
    }
}
