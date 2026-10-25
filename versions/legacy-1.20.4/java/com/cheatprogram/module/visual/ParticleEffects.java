package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.HudWorldPanel;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.ParticleOwnership;
import com.cheatprogram.util.ParticlePath;
import com.cheatprogram.util.ParticleStudio;
import com.mojang.brigadier.StringReader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.commands.arguments.ParticleArgument;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All particles are created locally. No particle commands or server packets are emitted. */
public final class ParticleEffects extends Module {
    private final NumberSetting budget = add(new NumberSetting("budget",180,10,1000,10));
    private final NumberSetting liveBudget = add(new NumberSetting("liveBudget",3000,100,10000,100));
    private final NumberSetting distance = add(new NumberSetting("distance",96,8,1024,1));
    private final BoolSetting guides = add(new BoolSetting("guides",false));
    private final BoolSetting previewOriginal = add(new BoolSetting("previewOriginal",true));
    private final NumberSetting guideBudget = add(new NumberSetting("guideBudget",128,16,512,16));
    private final Map<String,Parsed> cache = new HashMap<>();
    private final Set<String> activeOwners = new HashSet<>();
    private final List<Projected> projected = new ArrayList<>();
    private ClientLevel level;
    private int roundIndex, tickInRound;
    private long cycle;
    private boolean editing;
    private String selected = "";
    private long projectionTime;
    public record ParticleChoice(String id, boolean parameters) { }
    public record Projected(String id, float x, float y) { }
    private record Parsed(String specification, ParticleOptions options) { }
    public ParticleEffects() { super("particleeffects",Category.VISUAL); }
    public static List<ParticleChoice> choices() {
        ArrayList<ParticleChoice> list=new ArrayList<>();
        for(var type:BuiltInRegistries.PARTICLE_TYPE) list.add(new ParticleChoice(BuiltInRegistries.PARTICLE_TYPE.getKey(type).toString(),!(type instanceof SimpleParticleType)));
        list.sort(Comparator.comparing(ParticleChoice::id));return list;
    }
    /** Current vanilla SNBT payload templates; unknown modded schemas stay editable and fail visibly. */
    public static String defaultOptions(String id) {
        String path=id.substring(id.indexOf(':')+1);
        return switch(path) {
            case "dust" -> "{color:[1.0,0.7,1.0],scale:1.0}";
            case "dust_color_transition" -> "{from_color:[0.3,0.8,1.0],to_color:[1.0,0.5,0.7],scale:1.0}";
            case "entity_effect" -> "{color:[0.4,0.8,1.0,1.0]}";
            case "block","block_marker","falling_dust","dust_pillar","block_crumble" -> "{block_state:{Name:\"minecraft:stone\"}}";
            case "item" -> "{item:{id:\"minecraft:diamond\",count:1}}";
            case "sculk_charge" -> "{roll:0.0}";
            case "shriek" -> "{delay:0}";
            case "trail" -> "{target:[$x,$y,$z],color:[0.4,0.8,1.0],duration:20}";
            case "vibration" -> "{destination:{type:\"minecraft:block\",pos:[I;$bx,$by,$bz]},arrival_in_ticks:20}";
            default -> "";
        };
    }
    @Override public void onEnable() { restart(); }
    @Override public void onDisable() { clearParticles(); projected.clear(); }
    public void checkLifecycle() {
        ParticleStudio.flush(false);
        if(level!=mc.level) { clearParticles(); cache.clear(); level=mc.level; roundIndex=0; tickInRound=0;cycle++;projected.clear(); }
        if(!isEnabled()) { if(!activeOwners.isEmpty()||ParticleOwnership.alive()>0)clearParticles(); return; }
        if(mc.level!=null)ParticleOwnership.budget(mc.level.getGameTime(),budget.intVal(),liveBudget.intVal());
    }
    public void editing(boolean enabled) { editing=enabled;if(!enabled)projected.clear(); }
    public void select(String id) { selected=id==null?"":id; }
    public List<Projected> projected() { return System.nanoTime()-projectionTime<300_000_000L?List.copyOf(projected):List.of(); }
    public int currentRound() { return roundIndex; }
    public int currentTick() { return tickInRound; }
    public int alive() { return ParticleOwnership.alive(); }
    public boolean previewOriginal() { return previewOriginal.on(); }
    public void toggleOriginalPreview() { previewOriginal.set(!previewOriginal.on()); }
    public ParticleOptions previewOptions(ParticleStudio.Emitter emitter, Vec3 position) { return parse(emitter, position); }
    public void restart() { clearParticles();cache.clear();roundIndex=0;tickInRound=0;cycle++; }
    public void playRound(int index) { clearParticles();roundIndex=Math.max(0,Math.min(ParticleStudio.project().rounds.size()-1,index));tickInRound=0;cycle++; }
    public void nextRound() { playRound((roundIndex+1)%ParticleStudio.project().rounds.size()); }
    public void changed(ParticleStudio.Emitter emitter) { cache.remove(emitter.id);emitter.error="";ParticleStudio.touch(); }
    public void removedEmitter(String id) { cache.remove(id);ParticleOwnership.removeOwner(owner(id));activeOwners.remove(owner(id));ParticleStudio.touch(); }
    private String owner(String id) { return cycle+":"+id; }
    private void clearParticles() { ParticleOwnership.clear();activeOwners.clear(); }
    @Override public void onTick() {
        checkLifecycle(); if(!inGame()||mc.isPaused())return;
        var rounds=ParticleStudio.project().rounds;
        roundIndex=Math.max(0,Math.min(roundIndex,rounds.size()-1));
        ParticleStudio.Round round=rounds.get(roundIndex);
        if(!round.hold&&tickInRound>=round.duration) { nextRound();round=rounds.get(roundIndex); }
        Set<String> current=new HashSet<>();
        for(var emitter:round.emitters) {
            String owner=owner(emitter.id);
            boolean visible=emitter.shown&&emitter.inWorld()&&tickInRound>=emitter.start&&(emitter.duration==0||tickInRound-emitter.start<emitter.duration);
            if(visible&&inRange(emitter)) {
                ParticleOwnership.timeline(owner,tickInRound-emitter.start);
                current.add(owner); if((tickInRound-emitter.start)%emitter.every==0)emit(emitter,owner);
            }
        }
        for(String owner:activeOwners)if(!current.contains(owner))ParticleOwnership.removeOwner(owner);
        activeOwners.clear();activeOwners.addAll(current);tickInRound++;
    }
    private boolean inRange(ParticleStudio.Emitter emitter) {
        Vec3 path=ParticlePath.atBirth(emitter.path,tickInRound-emitter.start);
        for(Vec3 signs:emitter.mirrorSigns()) {
            Vec3 position=emitter.toWorld(emitter.offset().add(path).multiply(signs.x,signs.y,signs.z));
            if(mc.player.distanceToSqr(position)<=distance.val()*distance.val())return true;
        }
        return false;
    }
    private void emit(ParticleStudio.Emitter emitter,String owner) {
        boolean moving=emitter.path!=null&&!"none".equals(emitter.path.kind);
        Vec3 birthPath=ParticlePath.atBirth(emitter.path,tickInRound-emitter.start);
        for(Vec3 branch:moving?emitter.mirrorSigns():emitter.mirrored()) {
            Vec3 offset=moving?emitter.offset().add(birthPath).multiply(branch.x,branch.y,branch.z):branch;
            if(!ParticleOwnership.hasRoom())return;
            Vec3 position=emitter.toWorld(offset),velocity=emitter.velocity();
            ParticleOptions options=parse(emitter,position);if(options==null)return;
            ParticleOwnership.begin(owner,emitter,moving?branch:new Vec3(1,1,1),birthPath);
            try {
                for(int n=0;n<emitter.count&&ParticleOwnership.hasRoom();n++) {
                    double s=emitter.spread;
                    var random=mc.level.random;
                    Particle particle=mc.particleEngine.createParticle(options,position.x+random.nextGaussian()*s,position.y+random.nextGaussian()*s,position.z+random.nextGaussian()*s,velocity.x,velocity.y,velocity.z);
                    if(particle==null){emitter.error=Lang.t("ui.particles.noProvider");break;}
                    if(emitter.lifetime>0)particle.setLifetime(emitter.lifetime);
                    if(particle instanceof SingleQuadParticle&&emitter.scale!=1)particle.scale((float)emitter.scale);
                    // Custom renderers may define geometry or sub-emitters; their original scale stays intact.
                }
            } catch(Exception error) { emitter.error=Lang.t("ui.particles.providerError")+": "+shortError(error); }
            finally { ParticleOwnership.end(); }
        }
    }
    private ParticleOptions parse(ParticleStudio.Emitter emitter,Vec3 position) {
        String spec=resolved(emitter,position);Parsed cached=cache.get(emitter.id);
        if(cached!=null&&cached.specification.equals(spec))return cached.options;
        try {
            StringReader reader=new StringReader(spec);
            ParticleOptions options=ParticleArgument.readParticle(reader,net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.asLookup());
            reader.skipWhitespace();if(reader.canRead())throw new IllegalArgumentException("Trailing particle options");
            cache.put(emitter.id,new Parsed(spec,options));emitter.error="";return options;
        } catch(Exception error) { cache.put(emitter.id,new Parsed(spec,null));emitter.error=Lang.t("ui.particles.invalidOptions")+": "+shortError(error);return null; }
    }
    public String validate(ParticleStudio.Emitter emitter) {
        if(mc.level==null)return Lang.t("ui.particles.noWorld");
        parse(emitter,emitter.toWorld(emitter.offset()));return emitter.error;
    }
    private static String resolved(ParticleStudio.Emitter e,Vec3 p) {
        String options=e.options.strip().replace("$bx",Integer.toString((int)Math.floor(p.x))).replace("$by",Integer.toString((int)Math.floor(p.y+1))).replace("$bz",Integer.toString((int)Math.floor(p.z)))
                .replace("$x",Double.toString(p.x)).replace("$y",Double.toString(p.y+1)).replace("$z",Double.toString(p.z));
        return e.particle+options;
    }
    private static String shortError(Exception e) {String s=e.getMessage();if(s==null)s=e.getClass().getSimpleName();return s.substring(0,Math.min(220,s.length())).replace('\n',' ');}
    @Override public String suffix() { return (roundIndex+1)+" / "+ParticleStudio.project().rounds.size()+" · "+alive(); }
    @Override public void onRender3D(Render3D renderer) {
        if(!inGame()||!editing&&!guides.on())return;
        renderer.begin();
        try {
        projected.clear();projectionTime=System.nanoTime();
        ParticleStudio.Round round=ParticleStudio.project().rounds.get(Math.min(roundIndex,ParticleStudio.project().rounds.size()-1));
        var ordered=new ArrayList<>(round.emitters);ordered.sort(Comparator.comparing(e->!e.id.equals(selected)));
        int guideCount=0;
        for(var e:ordered) {
            if(!e.shown||!e.inWorld())continue;
            Vec3 position=e.toWorld(e.offset());if(renderer.camera.distanceToSqr(position)>distance.val()*distance.val())continue;
            boolean chosen=e.id.equals(selected);
            for(Vec3 point:e.mirrored()) {
                if(guideCount++>=guideBudget.intVal())break;
                Vec3 at=e.toWorld(point);double r=chosen?.025:.015;
                renderer.box(new AABB(at.x-r,at.y-r,at.z-r,at.x+r,at.y+r,at.z+r),chosen?0x35FFFFFF:0x154ED5F5,chosen?0xEFFFFFFF:0xB04ED5F5,1.4f);
            }
            if(chosen) {
                Vec3 base=e.anchor();double arm=Math.min(1024,Math.max(2,Math.max(Math.abs(e.x),Math.max(Math.abs(e.y),Math.abs(e.z)))*1.1));
                renderer.line(base,base.add(arm,0,0),0xEFFF7777,1.8f);renderer.line(base,base.add(0,arm,0),0xEF83FF9F,1.8f);renderer.line(base,base.add(0,0,arm),0xEF7DBAFF,1.8f);
                for(int i=-8;i<=8;i++){
                    double d=i*arm/8;
                    renderer.line(base.add(d,0,-.035),base.add(d,0,.035),0x70FFAAAA,1);
                    renderer.line(base.add(-.035,d,0),base.add(.035,d,0),0x70AAFFBB,1);
                    renderer.line(base.add(-.035,0,d),base.add(.035,0,d),0x70AACCFF,1);
                }
                renderer.line(position,position.add(e.velocity().scale(5)),0xDEFFEBA0,1.3f);
            }
            var p=HudWorldPanel.project(renderer,position,distance.val());if(p!=null)projected.add(new Projected(e.id,p.x(),p.y()));
        }
        } finally { renderer.end(); }
    }
}
