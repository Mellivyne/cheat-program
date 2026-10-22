package com.cheatprogram.mixin;

import com.cheatprogram.util.ParticleOwnership;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native 1.21.10 moved each particle simulation into its owning ParticleGroup. */
@Mixin(ParticleGroup.class)
public abstract class ParticleGroupTickMixin {
    @Inject(method = "tickParticle", at = @At("HEAD"))
    private void cheatprogram$particleParent(Particle particle, CallbackInfo ci) { ParticleOwnership.beforeTick(particle); }
    @Inject(method = "tickParticle", at = @At("RETURN"))
    private void cheatprogram$particleParentEnd(Particle particle, CallbackInfo ci) { ParticleOwnership.afterTick(); }
}
