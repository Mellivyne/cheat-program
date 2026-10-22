package com.cheatprogram.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/** Only this editor's particles are attached. Child emitters inherit their parent's birth frame. */
public final class ParticleOwnership {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Map<Particle,Binding> OWNERS = new IdentityHashMap<>();
    private static final ArrayDeque<Binding> CONTEXT = new ArrayDeque<>();
    private static final Binding NONE = new Binding("", null, Vec3.ZERO, 0,new Vec3(1,1,1),Vec3.ZERO);
    private static int tickBudget = 180, liveBudget = 3000, used;
    private static long budgetTick = Long.MIN_VALUE;
    private static boolean transformed;
    private static final Map<String,Integer> TIMELINES = new java.util.HashMap<>();
    private record Binding(String owner, ParticleStudio.Emitter emitter, Vec3 origin, float yaw,Vec3 signs,Vec3 birthPath) { }
    private ParticleOwnership() { }
    public static void budget(long tick, int perTick, int live) {
        afterRender();
        if (tick != budgetTick) { budgetTick = tick; used = 0; }
        tickBudget = Math.max(1, Math.min(1000, perTick)); liveBudget = Math.max(100, Math.min(10000, live));
        OWNERS.entrySet().removeIf(entry -> !entry.getKey().isAlive());
    }
    public static boolean hasRoom() { return used < tickBudget && OWNERS.size() < liveBudget; }
    public static int alive() { return OWNERS.size(); }
    public static void begin(String owner,ParticleStudio.Emitter emitter) {
        begin(owner,emitter,new Vec3(1,1,1));
    }
    public static void timeline(String owner,int elapsed) { TIMELINES.put(owner,Math.max(0,elapsed)); }
    public static void begin(String owner,ParticleStudio.Emitter emitter,Vec3 signs) {
        begin(owner,emitter,signs,Vec3.ZERO);
    }
    public static void begin(String owner,ParticleStudio.Emitter emitter,Vec3 signs,Vec3 birthPath) {
        CONTEXT.push(new Binding(owner==null?"":owner,emitter,emitter==null?Vec3.ZERO:emitter.anchor(),MC.player==null?0:MC.player.yBodyRot,signs,birthPath));
    }
    public static void end() { if (!CONTEXT.isEmpty()) CONTEXT.pop(); }
    public static void beforeTick(Particle particle) {
        Binding binding=OWNERS.getOrDefault(particle,NONE);CONTEXT.push(binding);
        if(binding!=NONE)((ParticleFrameAccess)particle).cheatprogram$advancePath();
    }
    public static void afterTick() { end(); }
    /**
     * Transform both interpolation endpoints for rendering and restore them afterwards. Keeping
     * simulation in the birth frame also preserves absolute-target particles (trail/vibration),
     * the vanilla drift, lifetime and child-emitter logic instead of rewriting their internals.
     */
    public static void beforeRender(float partial) {
        afterRender();if(MC.player==null)return;
        partial=Mth.clamp(partial,0,1);
        Vec3 anchor=MC.player.getPosition(partial);
        float bodyYaw=Mth.rotLerp(partial,MC.player.yBodyRotO,MC.player.yBodyRot);
        for(var entry:OWNERS.entrySet()) {
            Binding binding=entry.getValue();Particle particle=entry.getKey();
            if(!particle.isAlive()||binding.emitter==null)continue;
            var e=binding.emitter;var access=(ParticleFrameAccess)particle;
            float angle=!e.fixed&&e.rotateWithPlayer?(float)Math.toRadians(-Mth.wrapDegrees(bodyYaw-binding.yaw)):0;
            Vec3 destination=e.fixed?binding.origin:anchor;
            if(e.path!=null&&!"none".equals(e.path.kind)) {
                Vec3 p=ParticlePath.atRender(e.path,TIMELINES.getOrDefault(binding.owner,access.cheatprogram$pathAge()),access.cheatprogram$pathAge(),partial).subtract(binding.birthPath);
                p=new Vec3(p.x*binding.signs.x,p.y*binding.signs.y,p.z*binding.signs.z);
                if(!e.fixed&&e.rotateWithPlayer)p=p.yRot((float)Math.toRadians(-binding.yaw));
                access.cheatprogram$transformPath(binding.origin,destination,angle,p,e.path.nativeMotion);
            } else if(!e.fixed)access.cheatprogram$transformFrame(binding.origin,destination,angle);
        }
        transformed=true;
    }
    /** Also called before ticking/cleanup so interrupted rendering cannot leak altered positions. */
    public static void afterRender() {
        if(!transformed)return;
        for(Particle particle:OWNERS.keySet())((ParticleFrameAccess)particle).cheatprogram$restoreFrame();
        transformed=false;
    }
    /** @return true only when a particle owned by this feature exceeds its budget. */
    public static boolean added(Particle particle) {
        if (CONTEXT.isEmpty() || CONTEXT.peek().owner.isEmpty()) return false;
        if (!hasRoom()) { particle.remove(); return true; }
        if(OWNERS.containsKey(particle))return false;
        ((ParticleFrameAccess)particle).cheatprogram$captureOrigin();
        OWNERS.put(particle,CONTEXT.peek()); used++; return false;
    }
    public static void removeOwner(String owner) {
        afterRender();TIMELINES.remove(owner);Iterator<Map.Entry<Particle,Binding>> iterator = OWNERS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getValue().owner.equals(owner)) { entry.getKey().remove(); iterator.remove(); }
        }
    }
    public static void clear() {
        afterRender();for (Particle particle : OWNERS.keySet()) particle.remove();
        OWNERS.clear(); CONTEXT.clear(); TIMELINES.clear(); used = 0; budgetTick = Long.MIN_VALUE;
    }
}
