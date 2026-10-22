package com.cheatprogram.mixin;

import com.cheatprogram.render.ParticleLayoutPreview;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preview sub-emitters bypass world distance checks, queues and local audio. */
@Mixin(ClientLevel.class)
public abstract class ParticlePreviewLevelMixin {
    @Inject(method = {"addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V", "addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"}, at = @At("HEAD"), cancellable = true)
    private void cheatprogram$previewChild(ParticleOptions options, double x, double y, double z, double vx, double vy, double vz, CallbackInfo ci) {
        if (ParticleLayoutPreview.emitChild(options, x, y, z, vx, vy, vz)) ci.cancel();
    }
    @Inject(method = "addParticle(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void cheatprogram$previewForcedChild(ParticleOptions options, boolean force, boolean visible, double x, double y, double z, double vx, double vy, double vz, CallbackInfo ci) {
        if (ParticleLayoutPreview.emitChild(options, x, y, z, vx, vy, vz)) ci.cancel();
    }
    @Inject(method = "addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;ZDDDDDD)V", at = @At("HEAD"), cancellable = true)
    private void cheatprogram$previewVisibleChild(ParticleOptions options, boolean force, double x, double y, double z, double vx, double vy, double vz, CallbackInfo ci) {
        if (ParticleLayoutPreview.emitChild(options, x, y, z, vx, vy, vz)) ci.cancel();
    }
    @Inject(method = "playLocalSound", at = @At("HEAD"), cancellable = true)
    private void cheatprogram$mutePreview(CallbackInfo ci) {
        if (ParticleLayoutPreview.inContext()) ci.cancel();
    }
}
