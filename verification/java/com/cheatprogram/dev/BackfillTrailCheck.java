package com.cheatprogram.dev;
import com.cheatprogram.util.BackfillTrail;
public final class BackfillTrailCheck {
    public static void main(String[] args){
        var trail=new BackfillTrail();trail.update(0,0);
        for(int i=1;i<=40;i++)trail.update(i*.01,0);
        if(trail.x()<.99||Math.abs(trail.z())>.001)throw new AssertionError("Slow movement lost heading");
        for(int i=1;i<=40;i++)trail.update(.4,i*.01);
        if(trail.z()<.99||Math.abs(trail.x())>.001)throw new AssertionError("Slow corner lost heading");
        for(int i=0;i<10;i++)trail.update(.4,.4);
        if(trail.z()<.99)throw new AssertionError("Stopping lost heading");
        trail.reset();trail.update(1000,1000);
        if(trail.x()!=0||trail.z()!=0)throw new AssertionError("Correction retained heading");
        System.out.println("BACKFILL_TRAIL_PASS slow-walk crouch corner stop correction");
    }
}
