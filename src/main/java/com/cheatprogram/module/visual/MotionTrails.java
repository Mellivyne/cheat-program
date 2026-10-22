package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.render.WorldLines;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

public final class MotionTrails extends Module {
    private final NumberSetting lifetime=add(new NumberSetting("lifetime",1500,200,6000,100,"ms"));
    private final NumberSetting interval=add(new NumberSetting("interval",2,1,10,1,"ticks"));
    private final NumberSetting width=add(new NumberSetting("width",2,1,8,.5));
    private final NumberSetting red=add(new NumberSetting("red",210,0,255,1));
    private final NumberSetting green=add(new NumberSetting("green",240,0,255,1));
    private final NumberSetting blue=add(new NumberSetting("blue",255,0,255,1));
    private final BoolSetting afterimages=add(new BoolSetting("afterimages",true));
    private final NumberSetting imageSpacing=add(new NumberSetting("imageSpacing",5,2,20,1)).visibleWhen(afterimages::on);
    private final BoolSetting glow=add(new BoolSetting("glow",true));
    private final BoolSetting firstPerson=add(new BoolSetting("firstPerson",false));
    private final BoolSetting throughWalls=add(new BoolSetting("throughWalls",false));
    private record Sample(Vec3 position,float yaw,int tick) { }
    private final ArrayDeque<Sample> samples=new ArrayDeque<>();
    private Object world;
    private int tick;
    public MotionTrails(){super("motiontrails",Category.VISUAL);}
    @Override public void onDisable(){samples.clear();world=null;}
    public void checkLifecycle(){if(world!=mc.level||!isEnabled()){samples.clear();world=null;}}
    @Override public void onTick(){
        if(world!=mc.level){samples.clear();world=mc.level;tick=0;}
        tick++;
        int keep=(int)(lifetime.val()/50);
        while(!samples.isEmpty()&&tick-samples.getFirst().tick>keep)samples.removeFirst();
        if(!mc.player.isAlive()||mc.player.isPassenger())return;
        Vec3 position=mc.player.position();
        Sample last=samples.peekLast();
        if(last!=null&&last.position.distanceToSqr(position)>100){samples.clear();last=null;}
        if(tick%interval.intVal()==0&&(last==null||last.position.distanceToSqr(position)>.0009)){
            samples.addLast(new Sample(position,mc.player.yBodyRot,tick));
            while(samples.size()>128)samples.removeFirst();
        }
    }
    @Override public void onRender3D(Render3D renderer){
        if(world!=mc.level||!firstPerson.on()&&mc.options.getCameraType().isFirstPerson()&&!Freecam.isActive())return;
        List<WorldLines.Segment> lines=new ArrayList<>();
        Sample previous=null;int index=0;
        for(Sample sample:samples){
            float age=(tick-sample.tick+renderer.partialTick)/(float)(lifetime.val()/50);
            float opacity=Math.max(0,1-age);opacity*=opacity;
            int color=(Math.round(opacity*180)<<24)|(red.intVal()<<16)|(green.intVal()<<8)|blue.intVal();
            if(previous!=null){
                lines.add(new WorldLines.Segment(previous.position.add(0,.12,0),sample.position.add(0,.12,0),color));
                lines.add(new WorldLines.Segment(previous.position.add(0,.9,0),sample.position.add(0,.9,0),
                        (color&0xffffff)|(Math.round((color>>>24)*.4f)<<24)));
            }
            if(afterimages.on()&&index++%imageSpacing.intVal()==0) silhouette(lines,sample,color);
            previous=sample;
        }
        if(glow.on())WorldLines.draw(renderer,lines.stream().map(s->new WorldLines.Segment(s.from(),s.to(),(s.color()&0xffffff)|((s.color()>>>24)/5<<24))).toList(),width.floatVal()+4,throughWalls.on());
        WorldLines.draw(renderer,lines,width.floatVal(),throughWalls.on());
    }
    private void silhouette(List<WorldLines.Segment> lines,Sample sample,int color){
        Vec3 right=new Vec3(Math.cos(Math.toRadians(sample.yaw)),0,Math.sin(Math.toRadians(sample.yaw)));
        Vec3 base=sample.position,hip=base.add(0,.8,0),shoulder=base.add(0,1.4,0),head=base.add(0,1.75,0);
        lines.add(new WorldLines.Segment(hip,shoulder,color));
        lines.add(new WorldLines.Segment(shoulder,head,color));
        for(int sign:new int[]{-1,1}){
            Vec3 arm=shoulder.add(right.scale(sign*.3)),leg=base.add(right.scale(sign*.18));
            lines.add(new WorldLines.Segment(shoulder,arm,color));
            lines.add(new WorldLines.Segment(arm,arm.add(0,-.55,0),color));
            lines.add(new WorldLines.Segment(hip,leg,color));
        }
    }
}
