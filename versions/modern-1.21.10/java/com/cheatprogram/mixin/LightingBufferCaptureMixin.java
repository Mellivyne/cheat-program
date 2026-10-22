package com.cheatprogram.mixin;
import com.cheatprogram.compat.gl.UniformCapture;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.platform.Lighting;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Lighting.class)
public abstract class LightingBufferCaptureMixin {
    @Shadow @Final private GpuBuffer buffer;
    @Shadow @Final private int paddedSize;
    @Inject(method="updateBuffer",at=@At("RETURN"))
    private void cheatprogram$capture(Lighting.Entry entry,Vector3f a,Vector3f b,CallbackInfo ci){UniformCapture.lighting(buffer.slice(entry.ordinal()*paddedSize,Lighting.UBO_SIZE),a,b);}
}
