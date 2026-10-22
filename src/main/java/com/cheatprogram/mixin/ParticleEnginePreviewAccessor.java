package com.cheatprogram.mixin;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Uses the registered vanilla/modded factory without adding to the world's particle engine. */
@Mixin(ParticleEngine.class)
public interface ParticleEnginePreviewAccessor {
    @Invoker("makeParticle")
    Particle cheatprogram$makePreviewParticle(ParticleOptions options, double x, double y, double z, double vx, double vy, double vz);
}
