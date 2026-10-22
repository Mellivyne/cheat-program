package com.cheatprogram.util;

/** Tracks the descent already reported to the server, without clearing the player's fall distance. */
public final class FallPacketGuard {
    private boolean initialized;
    private double lastY, descent;
    public void reset(){initialized=false;lastY=descent=0;}
    public void rebase(double y,double alreadyFallen){
        initialized=Double.isFinite(y);lastY=y;
        descent=Double.isFinite(alreadyFallen)?Math.max(0,alreadyFallen):Double.POSITIVE_INFINITY;
    }
    /** A late ground report can itself deal damage: never inject one into an already dangerous fall. */
    public boolean position(double y,boolean realGround,boolean requested,double safeDistance){
        if(!Double.isFinite(y)){reset();return false;}
        if(!initialized){rebase(y,Double.POSITIVE_INFINITY);return false;}
        double drop=lastY-y;lastY=y;
        if(realGround){descent=0;return false;}
        if(drop<=1e-7)return false;
        boolean safe=Double.isFinite(safeDistance)&&safeDistance>0&&descent<safeDistance;
        if(requested&&safe){descent=0;return true;}
        descent+=drop;
        return false;
    }
    public void naturalReset(){descent=0;}
    public double descent(){return descent;}
}
