package com.cheatprogram.util;

/** Movement threshold applies to accumulated travel, not velocity per game tick. */
public final class BackfillTrail {
    private double anchorX,anchorZ,x,z;
    private boolean anchored;
    public void reset(){anchored=false;x=z=0;}
    public void update(double nextX,double nextZ){
        if(!anchored){anchorX=nextX;anchorZ=nextZ;anchored=true;return;}
        double dx=nextX-anchorX,dz=nextZ-anchorZ,length=Math.hypot(dx,dz);
        if(length<.1)return;
        x=dx/length;z=dz/length;anchorX=nextX;anchorZ=nextZ;
    }
    public double x(){return x;}
    public double z(){return z;}
}
