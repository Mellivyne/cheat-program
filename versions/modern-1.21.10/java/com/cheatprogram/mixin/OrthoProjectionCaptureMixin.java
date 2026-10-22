package com.cheatprogram.mixin;
import com.cheatprogram.compat.gl.UniformCapture;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(CachedOrthoProjectionMatrixBuffer.class)
public abstract class OrthoProjectionCaptureMixin {
    @Shadow protected abstract Matrix4f createProjectionMatrix(float width,float height);
    @Inject(method="getBuffer",at=@At("RETURN"))
    private void cheatprogram$capture(float width,float height,CallbackInfoReturnable<GpuBufferSlice> cir){UniformCapture.projection(cir.getReturnValue(),createProjectionMatrix(width,height));}
}
