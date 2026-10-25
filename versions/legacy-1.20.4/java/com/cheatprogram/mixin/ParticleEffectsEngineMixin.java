package com.cheatprogram.mixin;

import com.cheatprogram.util.ParticleOwnership;
import com.cheatprogram.render.ParticleLayoutPreview;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.MultiBufferSource;
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
    @Inject(method = "tickParticle", at = @At("HEAD"))
    private void cheatprogram$particleParent(Particle particle, CallbackInfo ci) { ParticleOwnership.beforeTick(particle); }
    @Inject(method = "tickParticle", at = @At("RETURN"))
    private void cheatprogram$particleParentEnd(Particle particle, CallbackInfo ci) { ParticleOwnership.afterTick(); }
    @Inject(method = "render", at = @At("HEAD"))
    private void cheatprogram$carryAttachedParticles(com.mojang.blaze3d.vertex.PoseStack pose, MultiBufferSource.BufferSource buffers, net.minecraft.client.renderer.LightTexture light, Camera camera, float partialTick, CallbackInfo ci) {
        ParticleOwnership.beforeRender(partialTick);
    }
    @Inject(method = "render", at = @At("RETURN"))
    private void cheatprogram$restoreAttachedParticles(com.mojang.blaze3d.vertex.PoseStack pose, MultiBufferSource.BufferSource buffers, net.minecraft.client.renderer.LightTexture light, Camera camera, float partialTick, CallbackInfo ci) {
        ParticleOwnership.afterRender();
    }
    @Inject(method = "tick", at = @At("HEAD"))
    private void cheatprogram$restoreBeforeSimulation(CallbackInfo ci) { ParticleOwnership.afterRender(); }
    @Inject(method = "clearParticles", at = @At("HEAD"))
    private void cheatprogram$particleEngineCleared(CallbackInfo ci) { ParticleOwnership.clear(); }
}
