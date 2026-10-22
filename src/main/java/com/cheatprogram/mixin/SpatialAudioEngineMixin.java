package com.cheatprogram.mixin;

import com.cheatprogram.audio.SpatialAudioBackend;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(SoundEngine.class)
public abstract class SpatialAudioEngineMixin {
    @Shadow private boolean loaded;
    @Shadow @Final private SoundEngineExecutor executor;
    @Shadow @Final private Map<SoundInstance, ChannelAccess.ChannelHandle> instanceToChannel;

    @Inject(method = "tick", at = @At("TAIL"))
    private void cheatprogram$acousticTick(boolean paused, CallbackInfo ci) {
        SpatialAudioBackend.engineTick(executor, loaded, instanceToChannel, paused);
    }

    @Inject(method = "play", at = @At("TAIL"))
    private void cheatprogram$playerSound(SoundInstance sound, CallbackInfo ci) {
        if (loaded) SpatialAudioBackend.soundStarted(executor, sound, instanceToChannel.get(sound));
    }

    @Inject(method = "stopAll", at = @At("HEAD"))
    private void cheatprogram$beforeSoundReset(CallbackInfo ci) {
        if (loaded) SpatialAudioBackend.engineStopping(executor);
    }
}
