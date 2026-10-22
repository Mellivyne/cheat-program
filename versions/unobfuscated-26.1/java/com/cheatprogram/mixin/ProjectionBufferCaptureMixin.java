package com.cheatprogram.mixin;
import com.cheatprogram.compat.gl.UniformCapture;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** 26.1 unified the three projection caches; capture actual writes without GPU readback. */
@Mixin(ProjectionMatrixBuffer.class)
public abstract class ProjectionBufferCaptureMixin {
    @Inject(method="getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;",at=@At("RETURN"))
    private void cheatprogram$capture(Matrix4f matrix,CallbackInfoReturnable<GpuBufferSlice> cir){UniformCapture.projection(cir.getReturnValue(),matrix);}
}
