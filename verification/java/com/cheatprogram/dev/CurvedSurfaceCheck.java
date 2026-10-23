package com.cheatprogram.dev;

import com.cheatprogram.render.CastSurface;

/** Standalone regression for the geometry used by both texture rendering and input picking. */
public final class CurvedSurfaceCheck {
    public static void main(String[] args){run();}
    public static void run(){
        int checks=0;
        for(boolean vertical:new boolean[]{false,true})for(double angle:new double[]{-160,-60,-1,0,1,60,160})for(int segments:new int[]{8,48,128}){
            var s=new CastSurface(3,1.6875,angle,vertical,segments);
            for(double u:new double[]{0,.001,.17,.5,.79,.999,1})for(double v:new double[]{0,.001,.25,.5,.87,.999,1}){
                var p=s.point(u,v);var normal=s.normal(u,v);
                var hit=s.pick(p.add(normal.scale(.3)),normal.scale(-1),true);
                if(hit==null||Math.abs(hit.u()-u)>1e-7||Math.abs(hit.v()-v)>1e-7||Math.abs(hit.distance()-.3)>1e-7)throw new AssertionError("Surface/picking mismatch: "+vertical+" "+angle+" "+u+" "+v+" "+hit);
                checks++;
            }
            if(s.pick(new CastSurface.Point(0,0,8),new CastSurface.Point(0,0,1),true)!=null)throw new AssertionError("Picked behind ray");
        }
        var flat=new CastSurface(4,2,0,false,128);
        if(flat.segments()!=1||!flat.point(0,0).equals(new CastSurface.Point(-2,1,0)))throw new AssertionError("Flat compatibility");
        if(flat.pick(new CastSurface.Point(3,0,2),new CastSurface.Point(0,0,-1),true)!=null)throw new AssertionError("Picked outside frame");
        if(flat.pick(new CastSurface.Point(3,0,2),new CastSurface.Point(0,0,-1),false)==null)throw new AssertionError("Unbounded drag failed");
        var inward=new CastSurface(4,2,60,false,48);var outward=new CastSurface(4,2,-60,false,48);
        if(inward.point(0,.5).z()<=0||outward.point(0,.5).z()>=0)throw new AssertionError("Curve sign");
        System.out.println("CURVED_SURFACE_CHECK_PASS "+checks+" round trips; both axes/signs, flat, bounds and drag");
    }
}
