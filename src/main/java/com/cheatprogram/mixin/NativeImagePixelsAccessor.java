package com.cheatprogram.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Enables one bounded bulk frame copy instead of a JNI call for every pixel. */
@Mixin(NativeImage.class)
public interface NativeImagePixelsAccessor {
    @Accessor("pixels") long cheatprogram$pixels();
}
