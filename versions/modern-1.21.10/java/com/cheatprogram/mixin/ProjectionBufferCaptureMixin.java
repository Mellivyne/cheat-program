package com.cheatprogram.mixin;
import com.cheatprogram.compat.gl.UniformCapture;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.PerspectiveProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(PerspectiveProjectionMatrixBuffer.class)
public abstract class ProjectionBufferCaptureMixin {
    @Inject(method="getBuffer",at=@At("RETURN"))
    private void cheatprogram$capture(Matrix4f matrix,CallbackInfoReturnable<GpuBufferSlice> cir){UniformCapture.projection(cir.getReturnValue(),matrix);}
}
