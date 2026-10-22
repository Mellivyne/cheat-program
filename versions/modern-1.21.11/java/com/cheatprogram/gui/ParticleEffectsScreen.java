package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.module.visual.ParticleEffects;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.render.ParticlePlayerPreview;
import com.cheatprogram.render.ParticleLayoutPreview;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.ParticleStudio;
import com.cheatprogram.util.ParticleNames;
import com.cheatprogram.util.ParticlePath;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/** Independent rounds, full registry chooser, a manipulable layout preview and real-world preview mode. */
public final class ParticleEffectsScreen extends GuiBase {
    private static final float W=1060;
    private final Screen parent;
    private final ParticleEffects module;
    private final Scroller emittersScroll=new Scroller(),controlsScroll=new Scroller(),libraryScroll=new Scroller();
    private final TextField search=new TextField(256),options=new TextField(8192),roundName=new TextField(80),numberInput=new TextField(40);
    private final List<ParticleEffects.ParticleChoice> registry=ParticleEffects.choices();
    private final List<Dot> dots=new ArrayList<>();
    private final List<PathDot> pathDots=new ArrayList<>();
    private final ParticlePlayerPreview playerPreview=new ParticlePlayerPreview();
    private final ParticleLayoutPreview originalPreview;
    private GuiGraphics frameGraphics;
    private int roundIndex,pathPointIndex;
    private String selected="",moveAxis="free",numberLabel="",numericError="";
    private DoubleConsumer numberSetter;
    private double numberMin,numberMax,numberStep;
    private boolean libraryOpen,replaceType,worldMode,editPathPoints;
    private float previewYaw=-35,previewPitch=20,previewZoom=1,panX,panY;
    private long deleteUntil;
    private String deleteKey="";
    private record Dot(String id,float x,float y) { }
    private record PathDot(int index,float x,float y) { }
    public ParticleEffectsScreen(Screen parent,ParticleEffects module) {
        super(Lang.t("ui.particles.title"));this.parent=parent;this.module=module;originalPreview=new ParticleLayoutPreview(module);
        search.onChange=value->{libraryScroll.target=0;libraryScroll.value.snap(0);};
        options.onChange=value->{var e=emitter();if(e!=null){e.options=value;module.changed(e);}};
        roundName.onChange=value->{round().name=value;ParticleStudio.touch();};
        numberInput.onEnter=this::applyNumber;
        if(!round().emitters.isEmpty())select(round().emitters.getFirst());
        syncRound();module.editing(true);
    }
    private ParticleStudio.Round round() {
        var rounds=ParticleStudio.project().rounds;roundIndex=Math.max(0,Math.min(rounds.size()-1,roundIndex));return rounds.get(roundIndex);
    }
    private ParticleStudio.Emitter emitter() {for(var e:round().emitters)if(e.id.equals(selected))return e;return null;}
    private String roundTitle(int index) {
        var r=ParticleStudio.project().rounds.get(index);return r.name.isBlank()?Lang.t("ui.particles.round")+" "+(index+1):r.name;
    }
    private void select(ParticleStudio.Emitter e) {if(!selected.equals(e.id)){pathPointIndex=0;editPathPoints=false;}selected=e.id;module.select(e.id);options.setText(e.options);focused=null;controlsScroll.target=0;controlsScroll.value.snap(0);}
    private void syncRound() {syncRound(true);}
    private void syncRound(boolean play) {roundName.setText(round().name);if(play)module.playRound(roundIndex);selected="";module.select("");if(!round().emitters.isEmpty())select(round().emitters.getFirst());}
    @Override public void tick(){ParticleStudio.flush(false);}
    @Override protected void init(){syncRound(false);}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partialTick) {
        beginFrame();module.editing(true);frameGraphics=graphics;
        if(!worldMode&&!preparePanelBackground(graphics,partialTick)){endFrame();return;}
        R2D.begin(graphics);
        try {
            if(worldMode){renderWorld();return;}
            float h=layoutPanel(W,740);drawPanelSurface(graphics,W,h,20);
            c.textC(BOLD,Lang.t("ui.particles.title"),24,29,15,Theme.TEXT);
            button("particles.share",Lang.t("ui.scopedShare.open"),Icon.LINK,820,16,184,28,false,()->mc.setScreen(new ScopedShareScreen(this,com.cheatprogram.core.ScopedPresetCodec.Scope.PARTICLES)));
            iconButton("particles.close",Icon.CLOSE,W-29,29,14,Theme.TEXT_2,this::onClose);
            c.textC(REG,Lang.t("ui.particles.local"),24,53,8.5f,Theme.TEXT_3);
            roundBar(24,72,W-48);
            float top=123,bottom=h-65,paneH=Math.max(100,bottom-top);
            emitterList(24,top,204,paneH);
            layoutPreview(244,top,370,paneH);
            settings(630,top,406,paneH);
            button("particles.back",Lang.t("ui.back"),Icon.BACK,24,h-44,90,28,false,this::onClose);
            button("particles.on",Lang.t(module.isEnabled()?"ui.particles.stop":"ui.particles.play"),Icon.POWER,126,h-44,130,28,module.isEnabled(),module::toggle);
            button("particles.real",Lang.t("ui.particles.worldPreview"),null,268,h-44,154,28,false,()->{if(mc.level!=null){originalPreview.close();module.setEnabled(true);module.playRound(roundIndex);worldMode=true;focused=null;}});
            button("particles.next",Lang.t("ui.particles.nextRound"),null,434,h-44,126,28,false,()->{module.nextRound();roundIndex=module.currentRound();syncRound();});
            String state=Lang.t("ui.particles.running")+" "+(module.currentRound()+1)+" / "+ParticleStudio.project().rounds.size()+" · "+Lang.t("ui.particles.alive")+" "+module.alive();
            c.textRight(REG,state,W-24,h-30,9,Theme.TEXT_3);
            if(!ParticleStudio.ioStatus().isBlank())c.textC(REG,Lang.t(ParticleStudio.ioStatus()),24,h-59,8.5f,0xFFFFBCA8);
            if(libraryOpen)library(h);
            if(numberSetter!=null)numericPopup(h);
        } finally {R2D.end();endFrame();}
    }
    private void roundBar(float x,float y,float w) {
        int count=ParticleStudio.project().rounds.size();
        button("particles.previousRound","‹",null,x,y,28,30,false,()->{roundIndex=(roundIndex+count-1)%count;syncRound();});
        button("particles.roundSelect",roundTitle(roundIndex)+"  "+(roundIndex+1)+" / "+count,null,x+36,y,212,30,true,()->{roundIndex=(roundIndex+1)%count;syncRound();});
        button("particles.addRound",Lang.t("ui.particles.addRound"),Icon.PLUS,x+260,y,114,30,false,()->{if(ParticleStudio.addRound()!=null){roundIndex=ParticleStudio.project().rounds.size()-1;syncRound();}});
        button("particles.copyRound",Lang.t("ui.particles.copyRound"),null,x+386,y,114,30,false,this::copyRound);
        button("particles.deleteRound",deleteLabel("round",Lang.t("ui.particles.deleteRound")),null,x+512,y,114,30,false,()->confirmDelete("round",()->{
            if(count==1){for(var e:round().emitters)module.removedEmitter(e.id);round().emitters.clear();round().name="";}else ParticleStudio.project().rounds.remove(roundIndex);
            ParticleStudio.touch();roundIndex=Math.min(roundIndex,ParticleStudio.project().rounds.size()-1);syncRound();
        }));
        textField(roundName,Lang.t("ui.particles.roundName"),null,x+638,y,w-638,30);
    }
    private void copyRound() {
        var from=round();var to=ParticleStudio.addRound();if(to==null)return;
        to.name=from.name;to.duration=from.duration;to.hold=from.hold;
        for(var e:from.emitters)if(ParticleStudio.total()<ParticleStudio.MAX_TOTAL)to.emitters.add(e.copy());
        roundIndex=ParticleStudio.project().rounds.size()-1;syncRound();ParticleStudio.touch();
    }
    private void emitterList(float x,float y,float w,float h) {
        c.lens(x,y,w,h,14,Theme.lift(.025f),Theme.lensGlass(c.scale));
        c.textC(MED,Lang.t("ui.particles.emitters")+" · "+round().emitters.size(),x+13,y+20,12,Theme.TEXT);
        button("particles.add",Lang.t("ui.particles.add"),Icon.PLUS,x+12,y+36,w-24,28,false,()->{replaceType=false;libraryOpen=true;focused=search;});
        float listY=y+75,listH=Math.max(20,h-81);hit(x,listY,w,listH).scroll(emittersScroll::scroll);
        c.clip(x+3,listY,w-6,listH);pushHitClip(x+3,listY,w-6,listH);
        float cy=listY-emittersScroll.get();
        for(var e:round().emitters) {
            boolean chosen=e.id.equals(selected);c.rrect(x+8,cy,w-16,46,9,Anim.white(chosen?.16f:.045f));
            c.textC(REG,c.ellipsize(REG,ParticleNames.display(e.particle),11,w-44),x+16,cy+16,11,Theme.TEXT);
            c.textC(REG,Lang.t(e.fixed?"ui.particles.worldAnchor":"ui.particles.playerAnchor")+" · "+(e.duration==0?Lang.t("ui.particles.wholeRound"):seconds(e.duration)),x+16,cy+33,8,Theme.TEXT_3);
            hit(x+8,cy,w-16,46).left(()->select(e));cy+=54;
        }
        if(round().emitters.isEmpty())c.textMid(REG,Lang.t("ui.particles.empty"),x+w/2,listY+40,10,Theme.TEXT_3);
        popHitClip();c.unclip();emittersScroll.max=Math.max(0,round().emitters.size()*54-listH);emittersScroll.update(dt);
        panelScrollBar("particles.emitters.scroll",emittersScroll,x+w-5,listY,listH);
    }
    private void settings(float x,float y,float w,float h) {
        c.lens(x,y,w,h,14,Theme.lift(.025f),Theme.lensGlass(c.scale));hit(x,y,w,h).scroll(controlsScroll::scroll);
        c.clip(x+3,y+3,w-6,h-6);pushHitClip(x+3,y+3,w-6,h-6);
        float cy=y+23-controlsScroll.get(),start=cy;
        c.textC(MED,Lang.t("ui.particles.roundSettings"),x+16,cy,12,Theme.TEXT);cy+=20;
        var r=round();
        button("particles.hold",Lang.t(r.hold?"ui.particles.holdOn":"ui.particles.holdOff"),null,x+16,cy,w-32,27,r.hold,()->{r.hold=!r.hold;ParticleStudio.touch();});cy+=45;
        if(!r.hold)cy=number("roundDuration",Lang.t("ui.particles.roundDuration"),r.duration/20.0,.05,3600,.05,60,.05,x+16,cy,w-32,v->{r.duration=(int)Math.round(v*20);ParticleStudio.touch();});
        c.textC(REG,Lang.t("ui.particles.roundHint"),x+16,cy,8,Theme.TEXT_3);cy+=27;
        var e=emitter();
        if(e!=null) {
            c.textC(MED,c.ellipsize(MED,ParticleNames.display(e.particle),12,w-32),x+16,cy,12,Theme.TEXT);cy+=17;
            c.textC(REG,c.ellipsize(REG,e.particle,8,w-32),x+16,cy,8,Theme.TEXT_3);cy+=18;
            button("particles.change",Lang.t("ui.particles.changeType"),null,x+16,cy,(w-40)/2,26,false,()->{replaceType=true;libraryOpen=true;focused=search;});
            button("particles.shown",Lang.t(e.shown?"ui.particles.shown":"ui.particles.hidden"),null,x+24+(w-40)/2,cy,(w-40)/2,26,e.shown,()->{e.shown=!e.shown;module.changed(e);});cy+=39;
            c.textC(REG,Lang.t("ui.particles.options"),x+16,cy,11,Theme.TEXT_2);cy+=13;
            textField(options,Lang.t("ui.particles.optionsPlaceholder"),null,x+16,cy,w-32,31);cy+=40;
            button("particles.validate",Lang.t("ui.particles.validate"),null,x+16,cy,110,25,false,()->module.validate(e));
            button("particles.template",Lang.t("ui.particles.template"),null,x+138,cy,w-154,25,false,()->{e.options=ParticleEffects.defaultOptions(e.particle);options.setText(e.options);module.changed(e);});cy+=39;
            if(!e.error.isBlank()){
                for(String line:c.wrap(REG,e.error,8.5f,w-32)){c.textC(REG,line,x+16,cy,8.5f,0xFFFFB8AA);cy+=13;}cy+=10;
            }
            button("particles.anchor",Lang.t(e.fixed?"ui.particles.worldAnchor":"ui.particles.playerAnchor"),null,x+16,cy,w-32,27,e.fixed,()->{
                if(e.fixed)e.fixed=false;else if(mc.player!=null)e.pin(mc.player.position());module.changed(e);
            });cy+=36;
            if(e.fixed) {
                button("particles.pinHere",Lang.t("ui.particles.pinHere"),null,x+16,cy,(w-40)/2,26,false,()->{if(mc.player!=null)e.pin(mc.player.position());module.changed(e);});
                button("particles.pinLook",Lang.t("ui.particles.pinLook"),null,x+24+(w-40)/2,cy,(w-40)/2,26,false,()->pinLook(e));cy+=40;
                c.textC(REG,e.inWorld()?Lang.t("ui.particles.anchorValid"):Lang.t("ui.particles.anchorElsewhere"),x+16,cy,8,Theme.TEXT_3);cy+=22;
                cy=number("anchorX",Lang.t("ui.particles.anchorX"),e.anchorX,-30000000,30000000,(mc.player==null?e.anchorX:mc.player.getX())-4,(mc.player==null?e.anchorX:mc.player.getX())+4,.05,x+16,cy,w-32,v->{e.anchorX=v;module.changed(e);});
                cy=number("anchorY",Lang.t("ui.particles.anchorY"),e.anchorY,-2048,2048,(mc.player==null?e.anchorY:mc.player.getY())-4,(mc.player==null?e.anchorY:mc.player.getY())+4,.05,x+16,cy,w-32,v->{e.anchorY=v;module.changed(e);});
                cy=number("anchorZ",Lang.t("ui.particles.anchorZ"),e.anchorZ,-30000000,30000000,(mc.player==null?e.anchorZ:mc.player.getZ())-4,(mc.player==null?e.anchorZ:mc.player.getZ())+4,.05,x+16,cy,w-32,v->{e.anchorZ=v;module.changed(e);});
            } else {
                button("particles.bodyYaw",Lang.t(e.rotateWithPlayer?"ui.particles.rotateBodyOn":"ui.particles.rotateBodyOff"),null,x+16,cy,w-32,26,e.rotateWithPlayer,()->{e.rotateWithPlayer=!e.rotateWithPlayer;module.changed(e);});cy+=42;
            }
            cy=number("x","X · "+Lang.t("ui.particles.offset"),e.x,-1024,1024,-4,4,.05,x+16,cy,w-32,v->{e.x=v;module.changed(e);});
            cy=number("y","Y · "+Lang.t("ui.particles.offset"),e.y,-1024,1024,-4,4,.05,x+16,cy,w-32,v->{e.y=v;module.changed(e);});
            cy=number("z","Z · "+Lang.t("ui.particles.offset"),e.z,-1024,1024,-4,4,.05,x+16,cy,w-32,v->{e.z=v;module.changed(e);});
            cy=pathSettings(e,x+16,cy,w-32);
            c.textC(REG,Lang.t("ui.particles.symmetry"),x+16,cy,11,Theme.TEXT_2);cy+=14;
            String[] symmetry={"none","x","y","z","xy","xz","yz","xyz"};
            for(int i=0;i<symmetry.length;i++){String value=symmetry[i];button("particles.mirror."+i,i==0?Lang.t("ui.particles.none"):value.toUpperCase(Locale.ROOT),null,x+16+i%4*(w-32)/4,cy+i/4*29,(w-44)/4,25,e.symmetry.equals(value),()->{e.symmetry=value;module.changed(e);});}cy+=73;
            c.textC(REG,Lang.t("ui.particles.mirrorHint"),x+16,cy,8,Theme.TEXT_3);cy+=24;
            cy=number("pitch",Lang.t("ui.particles.pitch"),e.pitch,-360,360,1,x+16,cy,w-32,v->{e.pitch=v;module.changed(e);});
            cy=number("yaw",Lang.t("ui.particles.yaw"),e.yaw,-360,360,1,x+16,cy,w-32,v->{e.yaw=v;module.changed(e);});
            cy=number("roll",Lang.t("ui.particles.roll"),e.roll,-360,360,1,x+16,cy,w-32,v->{e.roll=v;module.changed(e);});
            c.textC(REG,Lang.t("ui.particles.angleHint"),x+16,cy,8,Theme.TEXT_3);cy+=25;
            cy=number("vx","VX · "+Lang.t("ui.particles.velocity"),e.vx,-4,4,-.25,.25,.005,x+16,cy,w-32,v->{e.vx=v;module.changed(e);});
            cy=number("vy","VY · "+Lang.t("ui.particles.velocity"),e.vy,-4,4,-.25,.25,.005,x+16,cy,w-32,v->{e.vy=v;module.changed(e);});
            cy=number("vz","VZ · "+Lang.t("ui.particles.velocity"),e.vz,-4,4,-.25,.25,.005,x+16,cy,w-32,v->{e.vz=v;module.changed(e);});
            cy=number("spread",Lang.t("ui.particles.spread"),e.spread,0,8,0,1,.01,x+16,cy,w-32,v->{e.spread=v;module.changed(e);});
            cy=number("scale",Lang.t("ui.particles.scale"),e.scale,.05,8,.05,x+16,cy,w-32,v->{e.scale=v;module.changed(e);});
            c.textC(REG,Lang.t("ui.particles.scaleHint"),x+16,cy,8,Theme.TEXT_3);cy+=26;
            cy=number("count",Lang.t("ui.particles.count"),e.count,1,100,1,x+16,cy,w-32,v->{e.count=(int)v;module.changed(e);});
            cy=number("every",Lang.t("ui.particles.interval"),e.every/20.0,.05,60,.05,5,.05,x+16,cy,w-32,v->{e.every=(int)Math.round(v*20);module.changed(e);});
            cy=number("start",Lang.t("ui.particles.start"),e.start/20.0,0,3600,0,30,.05,x+16,cy,w-32,v->{e.start=(int)Math.round(v*20);module.changed(e);});
            cy=number("duration",Lang.t("ui.particles.duration"),e.duration/20.0,0,3600,0,60,.05,x+16,cy,w-32,v->{e.duration=(int)Math.round(v*20);module.changed(e);});
            c.textC(REG,Lang.t("ui.particles.durationHint"),x+16,cy,8,Theme.TEXT_3);cy+=25;
            cy=number("lifetime",Lang.t("ui.particles.lifetime"),e.lifetime/20.0,0,60,0,10,.05,x+16,cy,w-32,v->{e.lifetime=(int)Math.round(v*20);module.changed(e);});
            for(String line:c.wrap(REG,Lang.t("ui.particles.lifetimeHint"),8,w-32)){c.textC(REG,line,x+16,cy,8,Theme.TEXT_3);cy+=13;}cy+=15;
            button("particles.copy",Lang.t("ui.particles.copy"),null,x+16,cy,(w-40)/2,27,false,()->{if(round().emitters.size()<ParticleStudio.MAX_EMITTERS&&ParticleStudio.total()<ParticleStudio.MAX_TOTAL){var copy=e.copy();round().emitters.add(copy);ParticleStudio.touch();select(copy);}});
            button("particles.delete",deleteLabel(e.id,Lang.t("ui.particles.delete")),Icon.TRASH,x+24+(w-40)/2,cy,(w-40)/2,27,false,()->confirmDelete(e.id,()->{module.removedEmitter(e.id);round().emitters.remove(e);selected="";if(!round().emitters.isEmpty())select(round().emitters.getFirst());}));cy+=44;
        } else {c.textC(REG,Lang.t("ui.particles.selectHint"),x+16,cy,10.5f,Theme.TEXT_3);cy+=35;}
        popHitClip();c.unclip();controlsScroll.max=Math.max(0,cy-start-h+40);controlsScroll.update(dt);
        panelScrollBar("particles.controls.scroll",controlsScroll,x+w-5,y+8,h-16);
    }
    private float pathSettings(ParticleStudio.Emitter e,float x,float y,float w) {
        var path=e.path;
        c.textC(MED,Lang.t("ui.particles.path.title"),x,y,12,Theme.TEXT);y+=16;
        for(int i=0;i<ParticlePath.KINDS.size();i++) {
            String kind=ParticlePath.KINDS.get(i);
            button("particles.path.kind."+kind,Lang.t("ui.particles.path.kind."+kind),null,x+i%4*(w+4)/4,y+i/4*29,(w-12)/4,25,path.kind.equals(kind),()->{path.kind=kind;module.changed(e);});
        }
        y+=((ParticlePath.KINDS.size()+3)/4)*29+12;
        if(path.kind.equals("none"))return y;
        c.textC(REG,Lang.t("ui.particles.path.clock"),x,y,11,Theme.TEXT_2);y+=14;
        for(int i=0;i<ParticlePath.CLOCKS.size();i++) {
            String clock=ParticlePath.CLOCKS.get(i);
            button("particles.path.clock."+clock,Lang.t("ui.particles.path.clock."+clock),null,x+i*(w+8)/2,y,(w-8)/2,25,path.clock.equals(clock),()->{path.clock=clock;module.changed(e);});
        }
        y+=36;
        for(String text:c.wrap(REG,Lang.t("ui.particles.path.clockHint"),8,w)){c.textC(REG,text,x,y,8,Theme.TEXT_3);y+=13;}y+=14;
        y=number("pathDuration",Lang.t("ui.particles.path.duration"),path.duration/20.0,.05,3600,.05,30,.05,x,y,w,v->{path.duration=Math.round(v*20);module.changed(e);});
        if(!path.kind.equals("custom")) {
            y=number("pathRadius",Lang.t("ui.particles.path.radius"),path.radius,0,16,0,4,.05,x,y,w,v->{path.radius=v;module.changed(e);});
            if(path.kind.equals("helix")||path.kind.equals("wave")||path.kind.equals("spiral")||path.kind.equals("bounce"))y=number("pathHeight",Lang.t("ui.particles.path.height"),path.height,-16,16,-4,4,.05,x,y,w,v->{path.height=v;module.changed(e);});
            c.textC(REG,Lang.t("ui.particles.path.plane"),x,y,11,Theme.TEXT_2);y+=13;
            for(int i=0;i<ParticlePath.PLANES.size();i++) {
                String plane=ParticlePath.PLANES.get(i);
                button("particles.path.plane."+plane,plane.toUpperCase(Locale.ROOT),null,x+i*(w+4)/3,y,(w-8)/3,25,path.plane.equals(plane),()->{path.plane=plane;module.changed(e);});
            }
            y+=40;
        }
        if(!path.kind.equals("custom")&&!path.kind.equals("line"))y=number("pathPhase",Lang.t("ui.particles.path.phase"),path.phase,0,360,1,x,y,w,v->{path.phase=v;module.changed(e);});
        for(int i=0;i<ParticlePath.LOOPS.size();i++) {
            String loop=ParticlePath.LOOPS.get(i);
            button("particles.path.loop."+loop,Lang.t("ui.particles.path.loop."+loop),null,x+i*(w+4)/3,y,(w-8)/3,25,path.loop.equals(loop),()->{path.loop=loop;module.changed(e);});
        }
        y+=36;
        button("particles.path.nativeMotion",Lang.t(path.nativeMotion?"ui.particles.path.nativeOn":"ui.particles.path.nativeOff"),null,x,y,w,27,path.nativeMotion,()->{path.nativeMotion=!path.nativeMotion;module.changed(e);});y+=40;
        if(!path.kind.equals("custom"))return y;
        c.textC(REG,Lang.t("ui.particles.path.points")+" · "+path.points.size()+" / 64",x,y,11,Theme.TEXT_2);y+=14;
        pathPointIndex=Math.max(0,Math.min(path.points.size()-1,pathPointIndex));
        for(int i=0;i<path.points.size();i++) {
            final int index=i;
            button("particles.path.point."+i,String.valueOf(i+1),null,x+i%8*(w+4)/8,y+i/8*27,(w-28)/8,23,pathPointIndex==i,()->pathPointIndex=index);
        }
        y+=((path.points.size()+7)/8)*27+8;
        button("particles.path.add",Lang.t("ui.particles.path.add"),Icon.PLUS,x,y,(w-8)/2,25,false,()->{
            if(path.points.size()>=64)return;
            var last=path.points.get(path.points.size()-1);path.points.add(new ParticlePath.Point(clamp(last.x+.5,-16,16),last.y,last.z));pathPointIndex=path.points.size()-1;module.changed(e);
        });
        button("particles.path.remove",Lang.t("ui.particles.path.remove"),Icon.TRASH,x+(w+8)/2,y,(w-8)/2,25,false,()->{
            if(path.points.size()<=2)return;path.points.remove(pathPointIndex);pathPointIndex=Math.min(pathPointIndex,path.points.size()-1);module.changed(e);
        });y+=32;
        button("particles.path.before",Lang.t("ui.particles.path.before"),null,x,y,(w-8)/2,25,false,()->reorderPathPoint(e,-1));
        button("particles.path.after",Lang.t("ui.particles.path.after"),null,x+(w+8)/2,y,(w-8)/2,25,false,()->reorderPathPoint(e,1));y+=32;
        button("particles.path.midpoint",Lang.t("ui.particles.path.midpoint"),null,x,y,w,25,false,()->{
            if(path.points.size()>=64)return;
            int first=pathPointIndex,second=first+1;
            if(second>=path.points.size()){if(ParticlePath.closed(path))second=0;else{first--;second=pathPointIndex;}}
            var a=path.points.get(first);var b=path.points.get(second);int insertion=first+1;
            path.points.add(insertion,new ParticlePath.Point((a.x+b.x)/2,(a.y+b.y)/2,(a.z+b.z)/2));pathPointIndex=insertion;module.changed(e);
        });y+=40;
        final int pointIndex=pathPointIndex;var point=path.points.get(pointIndex);String pointLabel=Lang.t("ui.particles.path.point")+" "+(pathPointIndex+1);
        y=number("pathPointX","X · "+pointLabel,point.x,-16,16,-4,4,.05,x,y,w,v->{e.path.points.get(pointIndex).x=v;module.changed(e);});
        y=number("pathPointY","Y · "+pointLabel,point.y,-16,16,-4,4,.05,x,y,w,v->{e.path.points.get(pointIndex).y=v;module.changed(e);});
        y=number("pathPointZ","Z · "+pointLabel,point.z,-16,16,-4,4,.05,x,y,w,v->{e.path.points.get(pointIndex).z=v;module.changed(e);});
        for(int i=0;i<ParticlePath.INTERPOLATIONS.size();i++) {
            String interpolation=ParticlePath.INTERPOLATIONS.get(i);
            button("particles.path.interpolation."+interpolation,Lang.t("ui.particles.path.interpolation."+interpolation),null,x+i*(w+8)/2,y,(w-8)/2,25,path.interpolation.equals(interpolation),()->{path.interpolation=interpolation;module.changed(e);});
        }
        y+=34;
        button("particles.path.edit",Lang.t(editPathPoints?"ui.particles.path.editOn":"ui.particles.path.editOff"),null,x,y,w,27,editPathPoints,()->editPathPoints=!editPathPoints);y+=40;
        for(String text:c.wrap(REG,Lang.t("ui.particles.path.editHint"),8,w)){c.textC(REG,text,x,y,8,Theme.TEXT_3);y+=13;}
        return y+19;
    }
    private void reorderPathPoint(ParticleStudio.Emitter e,int direction) {
        int destination=pathPointIndex+direction;if(destination<0||destination>=e.path.points.size())return;
        var point=e.path.points.remove(pathPointIndex);e.path.points.add(destination,point);pathPointIndex=destination;module.changed(e);
    }
    private float number(String id,String label,double value,double min,double max,double step,float x,float y,float w,DoubleConsumer setter) {
        return number(id,label,value,min,max,min,max,step,x,y,w,setter);
    }
    private float number(String id,String label,double value,double min,double max,double dragMin,double dragMax,double step,float x,float y,float w,DoubleConsumer setter) {
        c.textC(REG,label,x,y,11.5f,Theme.TEXT_2);String valueText=format(value);
        c.textRight(REG,valueText,x+w,y,11.5f,Theme.TEXT);
        hit(x+w-Math.max(50,c.textWidth(REG,valueText,11.5f)+8),y-10,Math.max(50,c.textWidth(REG,valueText,11.5f)+8),20).left(()->{
            editNumber(label,value,min,max,step,setter);
        });
        double lo=Math.max(min,dragMin),hi=Math.min(max,dragMax);
        double ratio=clamp((value-lo)/Math.max(.000001,hi-lo),0,1);float track=y+23;
        c.rrect(x,track-1.5f,w,3,1.5f,Anim.white(.14f));c.rrect(x,track-1.5f,(float)(w*Math.max(0,Math.min(1,ratio))),3,1.5f,Anim.white(.8f));c.circle(x+(float)(w*Math.max(0,Math.min(1,ratio))),track,4.5f,0xFFFFFFFF);
        hit(x-3,track-8,w+6,16).click(button->{if(button==1){editNumber(label,value,min,max,step,setter);return;}if(button!=0)return;drag=(u,v)->{double number=lo+Math.max(0,Math.min(1,(u-x)/w))*(hi-lo);if(step>0)number=min+Math.round((number-min)/step)*step;setter.accept(Math.max(lo,Math.min(hi,number)));};drag.drag(mx,my);});
        c.textC(REG,format(lo)+" … "+format(hi),x,y+37,7,Theme.TEXT_4);
        c.textRight(REG,c.ellipsize(REG,Lang.t("ui.number.hint"),7,w*.68f),x+w,y+37,7,Theme.TEXT_4);
        return y+48;
    }
    private void editNumber(String label,double value,double min,double max,double step,DoubleConsumer setter){
        drag=null;numberLabel=label;numberSetter=setter;numberMin=min;numberMax=max;numberStep=step;numberInput.setText(NumberInputValues.format(value));focused=numberInput;numberInput.allSelected=true;numericError="";
    }
    private void library(float h) {
        hoverBlocked=true;float x=177,y=76,w=706,ph=Math.max(200,h-135);
        c.rrect(0,0,W,h,20,Anim.black(.58f));hit(0,0,W,h).scroll(amount->{}).left(()->{libraryOpen=false;focused=null;});
        c.lens(x,y,w,ph,16,Theme.lift(.17f),Theme.windowGlass(c.scale));hit(x,y,w,ph).left(()->{});
        c.textC(BOLD,Lang.t("ui.particles.library")+" · "+registry.size(),x+20,y+23,13,Theme.TEXT);
        iconButton("particles.library.close",Icon.CLOSE,x+w-23,y+23,13,Theme.TEXT_2,()->{libraryOpen=false;focused=null;});
        textField(search,Lang.t("ui.particles.search"),Icon.SEARCH,x+18,y+43,w-36,31);
        List<ParticleEffects.ParticleChoice> filtered=registry.stream().filter(choice->ParticleNames.matches(choice.id(),search.text)).toList();
        float listY=y+84,listH=ph-117;hit(x+12,listY,w-24,listH).scroll(libraryScroll::scroll);
        c.clip(x+12,listY,w-24,listH);pushHitClip(x+12,listY,w-24,listH);
        int first=Math.max(0,(int)(libraryScroll.get()/48)-1),last=Math.min(filtered.size(),first+(int)(listH/48)+4);
        for(int i=first;i<last;i++) {
            var choice=filtered.get(i);float cy=listY+i*48-libraryScroll.get();
            c.rrect(x+16,cy,w-32,43,7,Anim.white(over(x+16,cy,w-32,43)?.16f:.045f));
            c.textC(REG,c.ellipsize(REG,ParticleNames.display(choice.id()),11.5f,w-170),x+27,cy+15,11.5f,Theme.TEXT);
            c.textC(REG,c.ellipsize(REG,choice.id(),8.5f,w-58),x+27,cy+31,8.5f,Theme.TEXT_3);
            c.textRight(REG,Lang.t(choice.parameters()?"ui.particles.parameters":"ui.particles.simple"),x+w-27,cy+15,8.5f,Theme.TEXT_3);
            hit(x+16,cy,w-32,43).left(()->{
                var e=replaceType?emitter():null;
                if(e!=null){e.particle=choice.id();e.options=ParticleEffects.defaultOptions(choice.id());module.changed(e);}
                else e=ParticleStudio.addEmitter(round(),choice.id(),ParticleEffects.defaultOptions(choice.id()));
                if(e!=null)select(e);libraryOpen=false;focused=null;
            });
        }
        popHitClip();c.unclip();libraryScroll.max=Math.max(0,filtered.size()*48-listH);libraryScroll.update(dt);
        panelScrollBar("particles.library.scroll",libraryScroll,x+w-9,listY,listH);
        c.textC(REG,c.ellipsize(REG,Lang.t("ui.particles.libraryHint"),8,w-36),x+18,y+ph-17,8,Theme.TEXT_3);
    }
    private void numericPopup(float h) {
        hoverBlocked=true;float x=W/2-190,y=h/2-80;
        c.rrect(0,0,W,h,20,Anim.black(.55f));hit(0,0,W,h).scroll(amount->{}).left(()->{numberSetter=null;focused=null;});
        c.lens(x,y,380,160,15,Theme.lift(.17f),Theme.windowGlass(c.scale));hit(x,y,380,160).left(()->{});
        c.textC(MED,numberLabel,x+18,y+23,12,Theme.TEXT);
        textField(numberInput,format(numberMin)+" … "+format(numberMax),null,x+18,y+43,344,32);
        c.textC(REG,c.ellipsize(REG,numericError.isBlank()?format(numberMin)+" … "+format(numberMax)+" · "+Lang.t("ui.number.step")+" "+NumberInputValues.format(numberStep):numericError,8.5f,344),x+18,y+90,8.5f,Theme.TEXT_3);
        button("particles.number.ok",Lang.t("ui.particles.apply"),null,x+18,y+111,166,29,true,this::applyNumber);
        button("particles.number.cancel",Lang.t("ui.particles.cancel"),null,x+196,y+111,166,29,false,()->{numberSetter=null;focused=null;});
    }
    private void applyNumber() {
        if(numberSetter==null)return;NumberInputValues.Result result=NumberInputValues.apply(numberInput.text,numberMin,numberMax,numberStep,numberSetter);
        if(!result.valid()){numericError=Lang.t(result.problem());return;}numberSetter=null;focused=null;
    }
    private void layoutPreview(float x,float y,float w,float h) {
        c.rrect(x,y,w,h,14,Anim.black(.14f));
        c.textC(MED,Lang.t("ui.particles.layoutPreview"),x+14,y+20,12,Theme.TEXT);
        iconButton("particles.preview.reset",Icon.REFRESH,x+w-20,y+20,11,Theme.TEXT_3,()->{
            previewYaw=-35;previewPitch=20;panX=panY=0;playerPreview.reset();originalPreview.close();double furthest=3.2;
            for(var candidate:round().emitters) {
                furthest=Math.max(furthest,candidate.offset().length());
                if(!candidate.path.kind.equals("none")) {
                    var guide=ParticlePath.copy(candidate.path);guide.phase=0;guide.loop="once";
                    for(int i=0;i<=64;i++)furthest=Math.max(furthest,candidate.offset().add(ParticlePath.sample(guide,guide.duration*i/64.0)).length());
                    if(guide.kind.equals("custom"))for(var node:guide.points)furthest=Math.max(furthest,candidate.offset().add(node.x,node.y,node.z).length());
                }
            }
            previewZoom=(float)Math.max(.001,Math.min(1,3.2/furthest));
        });
        c.textC(REG,c.ellipsize(REG,Lang.t("ui.particles.layoutHint"),8,w-28),x+14,y+40,8,Theme.TEXT_3);
        button("particles.originalPreview",Lang.t(module.previewOriginal()?"ui.particles.originalPreviewOn":"ui.particles.originalPreviewOff"),null,x+12,y+54,w-24,25,module.previewOriginal(),()->{module.toggleOriginalPreview();originalPreview.close();});
        originalPreview.update(round(),module.previewOriginal());
        float sceneY=y+89,sceneH=Math.max(20,h-153),cx=x+w/2+panX,cy=sceneY+sceneH*.58f+panY;
        float unit=44*previewZoom;Quaternionf rotation=new Quaternionf().rotationX((float)Math.toRadians(previewPitch)).rotateY((float)Math.toRadians(previewYaw));
        dots.clear();pathDots.clear();c.clip(x+2,sceneY,w-4,sceneH);pushHitClip(x+2,sceneY,w-4,sceneH);
        double step=gridStep(Math.max(w,sceneH)/Math.max(.001,unit)/16),extent=step*8;
        for(int i=-8;i<=8;i++) {double d=i*step;line3(new Vec3(d,0,-extent),new Vec3(d,0,extent),rotation,cx,cy,unit,0x18FFFFFF,1);line3(new Vec3(-extent,0,d),new Vec3(extent,0,d),rotation,cx,cy,unit,0x18FFFFFF,1);}
        var selectedEmitter=emitter();Vec3 reference=selectedEmitter==null?(mc.player==null?Vec3.ZERO:mc.player.position()):selectedEmitter.anchor();
        float bodyYaw=selectedEmitter!=null&&!selectedEmitter.fixed&&mc.player!=null?(float)Math.toRadians(mc.player.yBodyRot):0;
        playerPreview.draw(frameGraphics,c.px(x+2),c.py(sceneY),(w-4)*c.scale,sceneH*c.scale,(cx-x-2)*c.scale,(cy-sceneY)*c.scale,unit*c.scale,rotation,module.previewOriginal()?originalPreview:null,reference,bodyYaw);
        if(!playerPreview.ready())c.textMid(REG,Lang.t(playerPreview.statusKey()),x+w/2,sceneY+18,8,Theme.TEXT_3);
        axis(Vec3.ZERO,new Vec3(extent,0,0),"X",0xFFFF8888,rotation,cx,cy,unit);
        axis(Vec3.ZERO,new Vec3(0,extent,0),"Y",0xFF9BFFB5,rotation,cx,cy,unit);
        axis(Vec3.ZERO,new Vec3(0,0,extent),"Z",0xFF8EBEFF,rotation,cx,cy,unit);
        for(int i=1;i<=8;i++){
            double d=i*step;var p=project(new Vec3(d,0,0),rotation,cx,cy,unit);c.textC(REG,format(d),p.x,p.y+8,7,Theme.TEXT_3);
            var py=project(new Vec3(0,d,0),rotation,cx,cy,unit);c.textC(REG,format(d),py.x+5,py.y,7,Theme.TEXT_3);
            var pz=project(new Vec3(0,0,d),rotation,cx,cy,unit);c.textC(REG,format(d),pz.x,pz.y+8,7,Theme.TEXT_3);
        }
        if(selectedEmitter!=null&&selectedEmitter.shown&&!selectedEmitter.path.kind.equals("none"))drawPath(selectedEmitter,reference,rotation,cx,cy,unit);
        for(var e:round().emitters) {
            if(!e.shown)continue;boolean chosen=e.id.equals(selected);
            for(Vec3 offset:e.mirrored()) {
                Vec3 local=e.toWorld(offset).subtract(reference);
                if(selectedEmitter!=null&&!selectedEmitter.fixed&&mc.player!=null)local=local.yRot((float)Math.toRadians(mc.player.yBodyRot));
                var p=project(local,rotation,cx,cy,unit);if(p.x<x||p.x>x+w||p.y<sceneY||p.y>sceneY+sceneH)continue;
                // Keep draggable handles distinct without covering the real particle texture.
                c.circle(p.x,p.y,chosen?1.6f:1f,chosen?0xFFFFFFFF:0xFF7ADCF3);
                if(chosen){Vec3 velocity=e.velocity();if(selectedEmitter!=null&&!selectedEmitter.fixed&&mc.player!=null)velocity=velocity.yRot((float)Math.toRadians(mc.player.yBodyRot));line3(local,local.add(velocity.scale(5)),rotation,cx,cy,unit,0xFFFFE2A0,1.5f);c.textC(REG,format(e.pitch)+"° / "+format(e.yaw)+"° / "+format(e.roll)+"°",p.x+11,p.y,8,Theme.TEXT_2);}
                if(offset.equals(e.offset()))dots.add(new Dot(e.id,p.x,p.y));
            }
        }
        hit(x+2,sceneY,w-4,sceneH).scroll(amount->{previewZoom=Math.max(.001f,Math.min(32,previewZoom*(float)Math.pow(1.12,amount)));}).click(button->previewDrag(button,rotation,unit));
        popHitClip();c.unclip();
        float axisY=y+h-53;
        String[] axes={"free","x","y","z"};for(int i=0;i<4;i++){String axis=axes[i];button("particles.moveAxis."+i,i==0?Lang.t("ui.particles.free"):axis.toUpperCase(Locale.ROOT),null,x+10+i*(w-20)/4,axisY,(w-32)/4,25,moveAxis.equals(axis),()->moveAxis=axis);}
        c.textC(REG,c.ellipsize(REG,Lang.t("ui.particles.previewMouse"),7.5f,w-24),x+12,y+h-13,7.5f,Theme.TEXT_3);
        if(module.previewOriginal())c.textC(REG,c.ellipsize(REG,Lang.t("ui.particles.originalPreviewState",format(originalPreview.tick()/20.0),originalPreview.alive()),7.5f,w-24),x+12,sceneY+12,7.5f,Theme.TEXT_3);
    }
    private Vec3 previewLocal(ParticleStudio.Emitter e,Vec3 offset,Vec3 reference) {
        Vec3 local=e.toWorld(offset).subtract(reference);var selectedEmitter=emitter();
        return selectedEmitter!=null&&!selectedEmitter.fixed&&mc.player!=null?local.yRot((float)Math.toRadians(mc.player.yBodyRot)):local;
    }
    private void drawPath(ParticleStudio.Emitter e,Vec3 reference,Quaternionf rotation,float cx,float cy,float unit) {
        // Mirror the whole path, retaining distinct branches even on a symmetry plane.
        var guide=ParticlePath.copy(e.path);if(!ParticlePath.closed(guide))guide.loop="once";
        for(Vec3 sign:e.mirrorSigns()) {
            boolean original=sign.x==1&&sign.y==1&&sign.z==1;
            Vec3 origin=e.offset().add(ParticlePath.sample(guide,0)).multiply(sign.x,sign.y,sign.z);
            Vec3 last=previewLocal(e,origin,reference);
            for(int i=1;i<=64;i++) {
                Vec3 offset=e.offset().add(ParticlePath.sample(guide,guide.duration*i/64.0)).multiply(sign.x,sign.y,sign.z);
                Vec3 next=previewLocal(e,offset,reference);
                line3(last,next,rotation,cx,cy,unit,original?0xA063DFF7:0x5063DFF7,1);last=next;
            }
            if(!e.path.kind.equals("custom"))continue;
            for(int i=0;i<e.path.points.size();i++) {
                var node=e.path.points.get(i);Vec3 offset=e.offset().add(node.x,node.y,node.z).multiply(sign.x,sign.y,sign.z);
                var p=project(previewLocal(e,offset,reference),rotation,cx,cy,unit);
                boolean selectedPoint=original&&pathPointIndex==i;
                c.circle(p.x,p.y,original&&editPathPoints?(selectedPoint?3.2f:2.2f):1.2f,selectedPoint?0xFFFFE2A0:0x7063DFF7);
                if(original&&editPathPoints){c.textC(REG,String.valueOf(i+1),p.x+5,p.y-4,7,Theme.TEXT_2);pathDots.add(new PathDot(i,p.x,p.y));}
            }
        }
    }
    private void previewDrag(int button,Quaternionf rotation,float unit) {
        if(button!=0&&button!=1)return;float startX=mx,startY=my;
        if(button==0&&editPathPoints) {
            PathDot closest=null;double pointDistance=10*10;
            for(PathDot dot:pathDots){double d=(dot.x-mx)*(dot.x-mx)+(dot.y-my)*(dot.y-my);if(d<pointDistance){pointDistance=d;closest=dot;}}
            var e=emitter();
            if(closest!=null&&e!=null&&e.path.kind.equals("custom")) {
                pathPointIndex=closest.index;final int pointIndex=pathPointIndex;var point=e.path.points.get(pointIndex);
                double px=point.x,py=point.y,pz=point.z;Quaternionf inverse=new Quaternionf(rotation).invert();
                boolean bodyFrame=!e.fixed&&mc.player!=null;
                drag=(u,v)->{Vector3f delta=new Vector3f((u-startX)/unit,-(v-startY)/unit,0);inverse.transform(delta);Vec3 vector=new Vec3(delta.x,delta.y,delta.z);if(bodyFrame&&!e.rotateWithPlayer)vector=vector.yRot((float)Math.toRadians(-mc.player.yBodyRot));
                    if(moveAxis.equals("free")||moveAxis.equals("x"))e.path.points.get(pointIndex).x=clamp(px+vector.x,-16,16);
                    if(moveAxis.equals("free")||moveAxis.equals("y"))e.path.points.get(pointIndex).y=clamp(py+vector.y,-16,16);
                    if(moveAxis.equals("free")||moveAxis.equals("z"))e.path.points.get(pointIndex).z=clamp(pz+vector.z,-16,16);module.changed(e);
                };return;
            }
        }
        Dot nearest=null;double best=10*10;
        for(Dot dot:dots){double d=(dot.x-mx)*(dot.x-mx)+(dot.y-my)*(dot.y-my);if(d<best){best=d;nearest=dot;}}
        if(button==0&&nearest!=null) {
            String id=nearest.id;for(var candidate:round().emitters)if(candidate.id.equals(id))select(candidate);
            var e=emitter();if(e==null)return;
            if(hasAltDown()) {double yaw=e.yaw,pitch=e.pitch;drag=(u,v)->{e.yaw=clamp(yaw+(u-startX),-360,360);e.pitch=clamp(pitch+(v-startY),-360,360);module.changed(e);};return;}
            double sx=e.x,sy=e.y,sz=e.z;Quaternionf inverse=new Quaternionf(rotation).invert();
            var selectedEmitter=emitter();boolean bodyFrame=selectedEmitter!=null&&!selectedEmitter.fixed&&mc.player!=null;
            drag=(u,v)->{Vector3f delta=new Vector3f((u-startX)/unit,-(v-startY)/unit,0);inverse.transform(delta);Vec3 vector=new Vec3(delta.x,delta.y,delta.z);if(bodyFrame&&(!e.rotateWithPlayer||e.fixed))vector=vector.yRot((float)Math.toRadians(-mc.player.yBodyRot));move(e,sx,sy,sz,vector);};
        } else if(button==0) {
            float yaw=previewYaw,pitch=previewPitch;drag=(u,v)->{previewYaw=yaw+(u-startX)*.65f;previewPitch=Math.max(-85,Math.min(85,pitch+(v-startY)*.65f));};
        } else {float px=panX,py=panY;drag=(u,v)->{panX=Math.max(-4096,Math.min(4096,px+u-startX));panY=Math.max(-4096,Math.min(4096,py+v-startY));};}
    }
    private void move(ParticleStudio.Emitter e,double x,double y,double z,Vec3 delta) {
        if(moveAxis.equals("free")||moveAxis.equals("x"))e.x=clamp(x+delta.x,-1024,1024);
        if(moveAxis.equals("free")||moveAxis.equals("y"))e.y=clamp(y+delta.y,-1024,1024);
        if(moveAxis.equals("free")||moveAxis.equals("z"))e.z=clamp(z+delta.z,-1024,1024);
        module.changed(e);
    }
    private void renderWorld() {
        if(roundIndex!=module.currentRound()){roundIndex=module.currentRound();syncRound(false);}
        float scale=Math.max(.6f,Math.min(R2D.width()/1060f,R2D.height()/760f));c.set(scale,16,16);updateMouse();
        c.lens(0,0,500,98,12,Theme.lift(.07f),Theme.sheetGlass(c.scale));
        c.textC(BOLD,Lang.t("ui.particles.worldPreview"),15,21,13,Theme.TEXT);
        c.textC(REG,Lang.t("ui.particles.worldMouse"),15,43,8.5f,Theme.TEXT_3);
        button("particles.world.back",Lang.t("ui.particles.backEditor"),Icon.BACK,12,59,135,27,false,()->{worldMode=false;focused=null;});
        button("particles.world.next",Lang.t("ui.particles.nextRound"),null,157,59,130,27,false,()->{module.nextRound();roundIndex=module.currentRound();syncRound();});
        button("particles.world.stop",Lang.t(module.isEnabled()?"ui.particles.stop":"ui.particles.play"),Icon.POWER,297,59,95,27,module.isEnabled(),module::toggle);
        String[] axes={"free","x","y","z"};for(int i=0;i<4;i++){String axis=axes[i];button("particles.world.axis."+i,i==0?Lang.t("ui.particles.free"):axis.toUpperCase(Locale.ROOT),null,12+i*67,109,61,26,moveAxis.equals(axis),()->moveAxis=axis);}
        var e=emitter();
        if(e!=null){
            c.textC(REG,ParticleNames.display(e.particle),15,155,11,Theme.TEXT);
            c.textC(REG,"X "+format(e.x)+"   Y "+format(e.y)+"   Z "+format(e.z),15,178,10.5f,Theme.TEXT);
            c.textC(REG,format(e.pitch)+"° / "+format(e.yaw)+"° / "+format(e.roll)+"°",15,201,10.5f,Theme.TEXT);
            if(!e.error.isBlank()){float errorY=230;for(String line:c.wrap(REG,e.error,8.5f,470)){c.textC(REG,line,15,errorY,8.5f,0xFFFFB8AA);errorY+=14;}}
        }
        for(var p:module.projected()){
            float x=c.toU(p.x()),y=c.toV(p.y());c.circle(x,y,p.id().equals(selected)?1.6f:1f,0xDFFFFFFF);
        }
        c.textC(REG,Lang.t("ui.particles.worldExit"),15,(R2D.height()-32)/scale,8.5f,Theme.TEXT_2);
    }
    private boolean worldClick(int button) {
        if(mc.player==null)return true;
        if(button==1) {
            float sx=mx,sy=my,yaw=mc.player.getYRot(),pitch=mc.player.getXRot();
            drag=(u,v)->{mc.player.setYRot(yaw+(u-sx)*.25f);mc.player.setXRot(Mth.clamp(pitch+(v-sy)*.25f,-90,90));};return true;
        }
        if(button!=0)return true;
        ParticleEffects.Projected nearest=null;double best=10*10*c.scale*c.scale;
        for(var p:module.projected()) {double d=(p.x()-mousePxX())*(p.x()-mousePxX())+(p.y()-mousePxY())*(p.y()-mousePxY());if(d<best){best=d;nearest=p;}}
        if(nearest==null)return true;
        for(var candidate:round().emitters)if(candidate.id.equals(nearest.id()))select(candidate);
        var e=emitter();if(e==null)return true;float sx=mx,sy=my;
        if(hasAltDown()){double yaw=e.yaw,pitch=e.pitch;drag=(u,v)->{e.yaw=clamp(yaw+(u-sx),-360,360);e.pitch=clamp(pitch+(v-sy),-360,360);module.changed(e);};return true;}
        double ox=e.x,oy=e.y,oz=e.z;
        double distance=mc.gameRenderer.getMainCamera().position().distanceTo(e.toWorld(e.offset()));
        double radians=Math.toRadians(mc.player.getYRot()),units=Math.max(.002,distance*.0018*c.scale);
        drag=(u,v)->{
            Vec3 delta=new Vec3(Math.cos(radians)*(u-sx)*units,-(v-sy)*units,Math.sin(radians)*(u-sx)*units);
            if(!e.fixed&&e.rotateWithPlayer)delta=delta.yRot((float)Math.toRadians(mc.player.yBodyRot));move(e,ox,oy,oz,delta);
        };return true;
    }
    private void pinLook(ParticleStudio.Emitter e) {
        if(mc.player==null)return;
        Vec3 point=mc.hitResult==null?mc.player.getEyePosition().add(mc.player.getLookAngle().scale(4)):mc.hitResult.getLocation();
        e.pin(point);e.x=0;e.y=0;e.z=0;module.changed(e);
    }
    private Vector3f project(Vec3 point,Quaternionf rotation,float cx,float cy,float unit) {Vector3f v=new Vector3f((float)point.x,(float)point.y,(float)point.z);rotation.transform(v);return new Vector3f(cx+v.x*unit,cy-v.y*unit,v.z);}
    private void line3(Vec3 a,Vec3 b,Quaternionf rotation,float cx,float cy,float unit,int color,float width) {var p=project(a,rotation,cx,cy,unit);var q=project(b,rotation,cx,cy,unit);line(p.x,p.y,q.x,q.y,color,width);}
    private void axis(Vec3 a,Vec3 b,String label,int color,Quaternionf rotation,float cx,float cy,float unit) {line3(a,b,rotation,cx,cy,unit,color,1.5f);var p=project(b,rotation,cx,cy,unit);c.textC(MED,label,p.x+5,p.y,9,color);}
    private void line(float x,float y,float xx,float yy,int color,float width) {
        float dx=(xx-x)*c.scale,dy=(yy-y)*c.scale,length=(float)Math.sqrt(dx*dx+dy*dy);
        if(length<.01f)return;Matrix4f transform=new Matrix4f().translate(c.px(x),c.py(y),0).rotateZ((float)Math.atan2(dy,dx));
        R2D.withPixelTransform(transform,()->R2D.rect(0,-width*c.scale/2,length,width*c.scale,color));
    }
    private String deleteLabel(String key,String normal) {return deleteKey.equals(key)&&System.currentTimeMillis()<deleteUntil?Lang.t("ui.particles.confirmDelete"):normal;}
    private void confirmDelete(String key,Runnable action){if(deleteKey.equals(key)&&System.currentTimeMillis()<deleteUntil){action.run();deleteKey="";}else{deleteKey=key;deleteUntil=System.currentTimeMillis()+3000;}}
    private static double clamp(double v,double low,double high){return Math.max(low,Math.min(high,v));}
    private static double gridStep(double target){double power=Math.pow(10,Math.floor(Math.log10(Math.max(.0001,target))));double base=target/power;return (base<=1?1:base<=2?2:base<=5?5:10)*power;}
    private static String format(double value){return String.format(Locale.ROOT,"%.3f",value).replaceAll("0+$","").replaceAll("\\.$","");}
    private static String seconds(int ticks){return format(ticks/20.0)+"s";}
    @Override public boolean mouseClicked(double x,double y,int button){if(dispatchClick(x,y,button))return true;if(worldMode)return worldClick(button);focused=null;return true;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){if(dispatchScroll(x,y,vertical))return true;if(worldMode){var e=emitter();if(e!=null){e.scale=clamp(e.scale*Math.pow(1.12,vertical),.05,8);module.changed(e);}}return true;}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==GLFW.GLFW_KEY_ESCAPE){if(numberSetter!=null){numberSetter=null;focused=null;}else if(libraryOpen){libraryOpen=false;focused=null;}else if(worldMode)worldMode=false;else onClose();return true;}
        if(focused!=null&&focused.keyPressed(key,modifiers))return true;
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean charTyped(char character,int modifiers){return focused!=null&&focused.charTyped(character)||super.charTyped(character,modifiers);}
    @Override public void onClose(){ParticleStudio.flush(true);module.editing(false);mc.setScreen(parent);}
    @Override public void removed(){drag=null;module.editing(false);originalPreview.close();playerPreview.close();ParticleStudio.flush(true);super.removed();}
}
