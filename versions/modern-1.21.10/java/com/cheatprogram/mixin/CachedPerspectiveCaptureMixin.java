package com.cheatprogram.mixin;
import com.cheatprogram.compat.gl.UniformCapture;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(CachedPerspectiveProjectionMatrixBuffer.class)
public abstract class CachedPerspectiveCaptureMixin {
    @Shadow protected abstract Matrix4f createProjectionMatrix(int width,int height,float fov);
    @Inject(method="getBuffer",at=@At("RETURN"))
    private void cheatprogram$capture(int width,int height,float fov,CallbackInfoReturnable<GpuBufferSlice> cir){UniformCapture.projection(cir.getReturnValue(),createProjectionMatrix(width,height,fov));}
}
