package com.cheatprogram.util;

import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Bounded, deterministic local paths shared by world particles and the native preview. */
public final class ParticlePath {
    public static final List<String> KINDS=List.of("none","line","circle","helix","figure8","wave","spiral","bounce","custom");
    public static final List<String> CLOCKS=List.of("emitter","particle");
    public static final List<String> LOOPS=List.of("loop","pingpong","once");
    public static final List<String> PLANES=List.of("xz","xy","yz");
    public static final List<String> INTERPOLATIONS=List.of("linear","smooth");
    public static final int MAX_POINTS=64;
    private ParticlePath() { }
    public static final class Point {
        public double x,y,z;
        public Point() { }
        public Point(double x,double y,double z) { this.x=x;this.y=y;this.z=z; }
    }
    public static final class Config {
        public String kind="none",loop="loop",plane="xz",interpolation="smooth",clock="emitter";
        public double duration=100,radius=1,height=1,phase;
        public boolean nativeMotion;
        public List<Point> points=new ArrayList<>(List.of(new Point(0,0,0),new Point(1,0,0),new Point(1,1,0),new Point(0,1,0)));
    }
    public static Config sanitize(Config c) {
        if(c==null)c=new Config();
        c.kind=choice(c.kind,KINDS,"none");c.clock=choice(c.clock,CLOCKS,"emitter");c.loop=choice(c.loop,LOOPS,"loop");
        c.plane=choice(c.plane,PLANES,"xz");c.interpolation=choice(c.interpolation,INTERPOLATIONS,"smooth");
        c.duration=bound(c.duration,1,72000,100);c.radius=bound(c.radius,0,16,1);
        c.height=bound(c.height,-16,16,1);c.phase=bound(c.phase,0,360,0);
        if(c.points==null)c.points=new ArrayList<>();
        var clean=new ArrayList<Point>();
        for(Point p:c.points) { if(p==null)continue;p.x=bound(p.x,-16,16,0);p.y=bound(p.y,-16,16,0);p.z=bound(p.z,-16,16,0);clean.add(p);if(clean.size()==MAX_POINTS)break; }
        while(clean.size()<2)clean.add(new Point(clean.size(),0,0));
        c.points=clean;return c;
    }
    public static Config copy(Config original) {
        Config c=new Config();if(original==null)return c;
        c.kind=original.kind;c.clock=original.clock;c.loop=original.loop;c.plane=original.plane;c.interpolation=original.interpolation;
        c.duration=original.duration;c.radius=original.radius;c.height=original.height;c.phase=original.phase;c.nativeMotion=original.nativeMotion;
        c.points=new ArrayList<>();if(original.points!=null)for(Point p:original.points)if(p!=null)c.points.add(new Point(p.x,p.y,p.z));
        return sanitize(c);
    }
    public static boolean closed(Config c) {
        return c!=null&&("circle".equals(c.kind)||"figure8".equals(c.kind)||"custom".equals(c.kind)&&"loop".equals(c.loop));
    }
    /** Birth coordinates and render clocks use the same previous-tick interpolation convention. */
    public static Vec3 atBirth(Config c,double emitterTicks) {
        return sample(c,c!=null&&"emitter".equals(c.clock)?Math.max(0,emitterTicks-1):0);
    }
    public static Vec3 atRender(Config c,double emitterTicks,int particleTicks,float partial) {
        double tick=c!=null&&"emitter".equals(c.clock)?emitterTicks:particleTicks;
        return sample(c,Math.max(0,tick-1+Math.max(0,Math.min(1,partial))));
    }
    public static Vec3 sample(Config c,double ageTicks) {
        if(c==null||"none".equals(c.kind))return Vec3.ZERO;
        double duration=bound(c.duration,1,72000,100),age=Double.isFinite(ageTicks)?Math.max(0,ageTicks):0;
        double t=age/duration;
        if("pingpong".equals(c.loop)) { t=t%2;if(t>1)t=2-t; }
        else if("once".equals(c.loop))t=Math.min(1,t);
        else t=t-Math.floor(t);
        // Phase rotates periodic shapes; line/custom paths use their authored endpoints.
        double phase=bound(c.phase,0,360,0)/360;
        double angle=(t+phase)*Math.PI*2,r=bound(c.radius,0,16,1),h=bound(c.height,-16,16,1);
        if("custom".equals(c.kind))return custom(c,t);
        double a=0,b=0,n=0;
        switch(c.kind) {
            case "line" -> a=r*t;
            case "circle" -> { a=r*Math.cos(angle);b=r*Math.sin(angle); }
            case "helix" -> { a=r*Math.cos(angle);b=r*Math.sin(angle);n=h*t; }
            case "figure8" -> { a=r*Math.sin(angle);b=r*Math.sin(2*angle)*.5; }
            case "wave" -> { a=r*t;b=h*Math.sin(angle); }
            case "spiral" -> { a=r*t*Math.cos((t*3+phase)*Math.PI*2);b=r*t*Math.sin((t*3+phase)*Math.PI*2);n=h*t; }
            case "bounce" -> { a=r*t;n=h*4*t*(1-t); }
            default -> { return Vec3.ZERO; }
        }
        return switch(c.plane==null?"xz":c.plane) {
            case "xy" -> new Vec3(a,b,n);
            case "yz" -> new Vec3(n,a,b);
            default -> new Vec3(a,n,b);
        };
    }
    private static Vec3 custom(Config c,double t) {
        List<Point> points=c.points;if(points==null||points.size()<2)return Vec3.ZERO;
        int count=Math.min(MAX_POINTS,points.size());boolean closed="loop".equals(c.loop);
        double p=t*(closed?count:count-1);int i=Math.min((int)p,closed?count-1:count-2);double f=p-i;
        Point b=point(points,i,count,closed),d=point(points,i+1,count,closed);
        if(b==null||d==null)return Vec3.ZERO;
        if(!"smooth".equals(c.interpolation))return new Vec3(mix(b.x,d.x,f),mix(b.y,d.y,f),mix(b.z,d.z,f));
        Point a=point(points,i-1,count,closed),e=point(points,i+2,count,closed);if(a==null)a=b;if(e==null)e=d;
        return new Vec3(cat(a.x,b.x,d.x,e.x,f),cat(a.y,b.y,d.y,e.y,f),cat(a.z,b.z,d.z,e.z,f));
    }
    private static Point point(List<Point> p,int i,int count,boolean closed) { return p.get(closed?Math.floorMod(i,count):Math.max(0,Math.min(count-1,i))); }
    private static double cat(double a,double b,double c,double d,double t) {
        a=bound(a,-16,16,0);b=bound(b,-16,16,0);c=bound(c,-16,16,0);d=bound(d,-16,16,0);
        return Math.max(-16,Math.min(16,.5*((2*b)+(-a+c)*t+(2*a-5*b+4*c-d)*t*t+(-a+3*b-3*c+d)*t*t*t)));
    }
    private static double mix(double a,double b,double f) { return bound(a,-16,16,0)*(1-f)+bound(b,-16,16,0)*f; }
    private static double bound(double v,double min,double max,double fallback) { return Double.isFinite(v)?Math.max(min,Math.min(max,v)):fallback; }
    private static String choice(String v,List<String> choices,String fallback) { return v!=null&&choices.contains(v)?v:fallback; }
}
