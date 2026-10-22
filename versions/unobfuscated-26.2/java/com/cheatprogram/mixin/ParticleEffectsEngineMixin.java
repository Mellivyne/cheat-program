package com.cheatprogram.mixin;

import com.cheatprogram.util.ParticleOwnership;
import com.cheatprogram.render.ParticleLayoutPreview;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.Camera;
import com.cheatprogram.compat.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ParticleEngine.class)
public abstract class ParticleEffectsEngineMixin {
    @Inject(method = "add", at = @At("HEAD"), cancellable = true)
    private void cheatprogram$ownParticle(Particle particle, CallbackInfo ci) {
        if (ParticleLayoutPreview.capture(particle) || ParticleOwnership.added(particle)) ci.cancel();
    }
    @Inject(method = "extract", at = @At("HEAD"))
    private void cheatprogram$carryAttachedParticles(net.minecraft.client.renderer.state.level.ParticlesRenderState state, net.minecraft.client.renderer.culling.Frustum frustum, Camera camera, float partialTick, CallbackInfo ci) {
        ParticleOwnership.beforeRender(partialTick);
    }
    @Inject(method = "extract", at = @At("RETURN"))
    private void cheatprogram$restoreAttachedParticles(net.minecraft.client.renderer.state.level.ParticlesRenderState state, net.minecraft.client.renderer.culling.Frustum frustum, Camera camera, float partialTick, CallbackInfo ci) {
        ParticleOwnership.afterRender();
    }
    @Inject(method = "tick", at = @At("HEAD"))
    private void cheatprogram$restoreBeforeSimulation(CallbackInfo ci) { ParticleOwnership.afterRender(); }
    @Inject(method = "clearParticles", at = @At("HEAD"))
    private void cheatprogram$particleEngineCleared(CallbackInfo ci) { ParticleOwnership.clear(); }
}
