package com.cheatprogram.mixin;

import com.cheatprogram.audio.SpatialAudioBackend;
import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Channel.class)
public abstract class SpatialAudioChannelMixin {
    @Shadow @Final private int source;

    @Inject(method = "destroy", at = @At("HEAD"))
    private void cheatprogram$releaseAcoustics(CallbackInfo ci) {
        SpatialAudioBackend.channelDestroyed(source);
    }
}
