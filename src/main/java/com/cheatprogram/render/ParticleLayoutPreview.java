package com.cheatprogram.render;

import com.cheatprogram.core.Lang;
import com.cheatprogram.mixin.ParticleEnginePreviewAccessor;
import com.cheatprogram.module.visual.ParticleEffects;
import com.cheatprogram.util.ParticleFrameAccess;
import com.cheatprogram.util.ParticlePath;
import com.cheatprogram.util.ParticleStudio;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** A private simulation of real registered particle providers, never the live world engine. */
public final class ParticleLayoutPreview implements AutoCloseable {
    private static final int MAX_LIVE = 4096, MAX_NEW_PER_TICK = 1024;
    private static Context context;
    private final Minecraft mc = Minecraft.getInstance();
    private final ParticleEffects module;
    private final List<Entry> particles = new ArrayList<>();
    private final Set<Particle> identities = Collections.newSetFromMap(new IdentityHashMap<>());
    private ClientLevel level;
    private String roundId = "";
    private long revision = -1, lastTime;
    private double accumulator;
    private int tick, emitted, cursor;
    private record Binding(ParticleStudio.Emitter emitter, Vec3 anchor, float yaw,Vec3 signs,Vec3 birthPath) { }
    private record Entry(Particle particle, Binding binding) { }
    private record Context(ParticleLayoutPreview preview, Binding binding) { }

    public ParticleLayoutPreview(ParticleEffects module) { this.module = module; }
    public static boolean inContext() { return context != null; }
    /** Catches providers that call the global particle engine directly for their children. */
    public static boolean capture(Particle particle) {
        if (context == null) return false;
        if (!context.preview.room()) { particle.remove(); return true; }
        context.preview.emitted++;
        context.preview.retain(particle, context.binding);
        return true;
    }
    /** Catches ClientLevel sub-emitters before world culling or the world queue sees them. */
    public static boolean emitChild(ParticleOptions options, double x, double y, double z, double vx, double vy, double vz) {
        if (context == null) return false;
        context.preview.create(options, new Vec3(x, y, z), new Vec3(vx, vy, vz), context.binding, false);
        return true;
    }
    public int alive() { return particles.size(); }
    public int tick() { return Math.max(0, tick - 1); }
    public float partialTick() { return (float)Math.max(0, Math.min(1, accumulator)); }

    public void update(ParticleStudio.Round round, boolean enabled) {
        if (!enabled || mc.level == null || mc.player == null) { close(); return; }
        long now = System.nanoTime();
        if (level != mc.level || !roundId.equals(round.id) || revision != ParticleStudio.revision()) {
            close(); level = mc.level; roundId = round.id; revision = ParticleStudio.revision(); lastTime = now;
            step(round);
        }
        accumulator += Math.min(.25, Math.max(0, (now - lastTime) / 1_000_000_000.0)) * 20;
        lastTime = now;
        for (int count = 0; accumulator >= 1 && count < 5; count++) { step(round); accumulator--; }
    }
    private void step(ParticleStudio.Round round) {
        emitted = 0;
        if (!round.hold && tick >= round.duration) { clearParticles(); tick = 0; }
        // A provider may emit children while ticking; iterate only the particles present at entry.
        for (Entry entry : List.copyOf(particles)) {
            Particle particle = entry.particle;
            if (!active(entry.binding.emitter, tick) || !particle.isAlive()) { particle.remove(); continue; }
            ((ParticleFrameAccess)particle).cheatprogram$advancePath();
            run(entry.binding, () -> {
                try { particle.tick(); }
                catch (Exception error) { particle.remove(); error(entry.binding.emitter, error); }
            });
        }
        particles.removeIf(entry -> {
            if (entry.particle.isAlive()) return false;
            identities.remove(entry.particle); return true;
        });
        int size = round.emitters.size();
        for (int i = 0; i < size; i++) {
            ParticleStudio.Emitter e = round.emitters.get((i + cursor) % size);
            if (!active(e, tick) || (tick - e.start) % e.every != 0) continue;
            boolean moving=e.path!=null&&!"none".equals(e.path.kind);
            Vec3 birthPath=ParticlePath.atBirth(e.path,tick-e.start);
            for (Vec3 branch : moving?e.mirrorSigns():e.mirrored()) {
                Vec3 offset=moving?e.offset().add(birthPath).multiply(branch.x,branch.y,branch.z):branch;
                Binding binding = new Binding(e,e.anchor(),mc.player.yBodyRot,moving?branch:new Vec3(1,1,1),birthPath);
                Vec3 position = e.toWorld(offset), velocity = e.velocity();
                ParticleOptions options = module.previewOptions(e, position);
                if (options == null) continue;
                for (int n = 0; n < e.count && room(); n++) {
                    double s = e.spread;
                    // Preview jitter is local to this simulation and does not advance the world's RNG.
                    var random = java.util.concurrent.ThreadLocalRandom.current();
                    Vec3 at = position.add(random.nextGaussian() * s, random.nextGaussian() * s, random.nextGaussian() * s);
                    create(options, at, velocity, binding, true);
                }
            }
        }
        if (size > 0) cursor = (cursor + 1) % size;
        tick++;
    }
    private static boolean active(ParticleStudio.Emitter e, int tick) {
        return e.shown && e.inWorld() && tick >= e.start && (e.duration == 0 || tick - e.start < e.duration);
    }
    private boolean room() { return particles.size() < MAX_LIVE && emitted < MAX_NEW_PER_TICK; }
    private void create(ParticleOptions options, Vec3 at, Vec3 velocity, Binding binding, boolean configured) {
        if (!room() || level != mc.level) return;
        // Claim the budget before calling a potentially recursive provider.
        emitted++;
        run(binding, () -> {
            try {
                Particle particle = ((ParticleEnginePreviewAccessor)mc.particleEngine).cheatprogram$makePreviewParticle(options, at.x, at.y, at.z, velocity.x, velocity.y, velocity.z);
                if (particle == null) { binding.emitter.error = Lang.t("ui.particles.noProvider"); return; }
                if (configured) {
                    if (binding.emitter.lifetime > 0) particle.setLifetime(binding.emitter.lifetime);
                    if (particle instanceof SingleQuadParticle && binding.emitter.scale != 1) particle.scale((float)binding.emitter.scale);
                }
                retain(particle, binding);
            } catch (Exception error) { error(binding.emitter, error); }
        });
    }
    private void retain(Particle particle, Binding binding) {
        if (particle == null || identities.contains(particle)) return;
        if (particles.size() >= MAX_LIVE) { particle.remove(); return; }
        ((ParticleFrameAccess)particle).cheatprogram$captureOrigin();
        identities.add(particle); particles.add(new Entry(particle, binding));
    }
    private void run(Binding binding, Runnable work) {
        Context previous = context; context = new Context(this, binding);
        try { work.run(); } finally { context = previous; }
    }
    private static void error(ParticleStudio.Emitter emitter, Exception error) {
        String message = error.getMessage(); if (message == null) message = error.getClass().getSimpleName();
        emitter.error = Lang.t("ui.particles.providerError") + ": " + message.substring(0, Math.min(160, message.length())).replace('\n', ' ');
    }

    /** Caller owns the offscreen framebuffer, projection, model-view and batch flushing. */
    public void render(MultiBufferSource buffers, Vec3 reference, Quaternionf viewRotation) {
        if (level == null || level != mc.level || mc.player == null) return;
        PreviewCamera camera = new PreviewCamera(reference, new Quaternionf(viewRotation).invert());
        List<Entry> snapshot = List.copyOf(particles);
        try {
            for (Entry entry : snapshot) {
                var binding=entry.binding;var e=binding.emitter;var access=(ParticleFrameAccess)entry.particle;
                float yaw=!e.fixed&&e.rotateWithPlayer?(float)Math.toRadians(-Mth.wrapDegrees(mc.player.yBodyRot-binding.yaw)):0;
                if(e.path!=null&&!"none".equals(e.path.kind)) {
                    Vec3 p=ParticlePath.atRender(e.path,tick-1-e.start,access.cheatprogram$pathAge(),partialTick()).subtract(binding.birthPath);
                    p=p.multiply(binding.signs.x,binding.signs.y,binding.signs.z);
                    if(!e.fixed&&e.rotateWithPlayer)p=p.yRot((float)Math.toRadians(-binding.yaw));
                    access.cheatprogram$transformPath(binding.anchor,e.fixed?binding.anchor:e.anchor(),yaw,p,e.path.nativeMotion);
                } else if(!e.fixed)access.cheatprogram$transformFrame(binding.anchor,e.anchor(),yaw);
            }
            ParticleRenderType[] order = {ParticleRenderType.TERRAIN_SHEET, ParticleRenderType.PARTICLE_SHEET_OPAQUE, ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT, ParticleRenderType.CUSTOM};
            for (ParticleRenderType type : order) for (Entry entry : snapshot) {
                Particle particle = entry.particle;
                if (!particle.isAlive() || particle.getRenderType() != type) continue;
                run(entry.binding, () -> {
                    try {
                        if (type == ParticleRenderType.CUSTOM) particle.renderCustom(new PoseStack(), buffers, camera, partialTick());
                        else particle.render(buffers.getBuffer(type.renderType()), camera, partialTick());
                    } catch (Exception error) { particle.remove(); error(entry.binding.emitter, error); }
                });
            }
        } finally {
            for (Entry entry : snapshot) ((ParticleFrameAccess)entry.particle).cheatprogram$restoreFrame();
        }
    }
    private static final class PreviewCamera extends Camera {
        PreviewCamera(Vec3 origin, Quaternionf rotation) { setPosition(origin); rotation().set(rotation); }
    }
    private void clearParticles() {
        for (Entry entry : particles) { ((ParticleFrameAccess)entry.particle).cheatprogram$restoreFrame(); entry.particle.remove(); }
        particles.clear(); identities.clear();
    }
    @Override public void close() {
        clearParticles(); level = null; roundId = ""; revision = -1; tick = emitted = cursor = 0; accumulator = 0; lastTime = 0;
    }
}
