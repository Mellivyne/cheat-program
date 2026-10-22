package com.cheatprogram.mixin;

import com.cheatprogram.util.ParticleFrameAccess;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Carry both interpolation endpoints; vanilla physics and unrelated particles remain intact. */
@Mixin(Particle.class)
public abstract class ParticleEffectsFrameMixin implements ParticleFrameAccess {
    @Shadow protected double x, y, z, xo, yo, zo, xd, yd, zd;
    @Shadow public abstract void setPos(double x, double y, double z);
    @Shadow public abstract AABB getBoundingBox();
    @Shadow public abstract void setBoundingBox(AABB box);
    @Unique private double[] cheatprogram$snapshot;
    @Unique private AABB cheatprogram$box;
    @Unique private double cheatprogram$originX,cheatprogram$originY,cheatprogram$originZ;
    @Unique private int cheatprogram$pathTicks;

    @Override public void cheatprogram$captureOrigin() { cheatprogram$originX=x;cheatprogram$originY=y;cheatprogram$originZ=z;cheatprogram$pathTicks=0; }
    @Override public int cheatprogram$pathAge() { return cheatprogram$pathTicks; }
    @Override public void cheatprogram$advancePath() { if(cheatprogram$pathTicks<Integer.MAX_VALUE)cheatprogram$pathTicks++; }
    @Override public void cheatprogram$transformPath(Vec3 before, Vec3 after, float yaw, Vec3 path, boolean nativeMotion) {
        // Both endpoints receive the exact sub-tick path offset. The native renderer still
        // interpolates its own drift, while nonlinear paths do not become 20 Hz polylines.
        cheatprogram$transformFrame(before,after,yaw);
        double cosine=Math.cos(yaw),sine=Math.sin(yaw);
        double dx=path.x*cosine+path.z*sine,dz=path.z*cosine-path.x*sine;
        if(!nativeMotion) {
            double px=cheatprogram$originX-before.x,pz=cheatprogram$originZ-before.z;
            setPos(after.x+px*cosine+pz*sine,after.y+cheatprogram$originY-before.y,after.z+pz*cosine-px*sine);
            xo=x;yo=y;zo=z;
        }
        setPos(x+dx,y+path.y,z+dz);xo+=dx;yo+=path.y;zo+=dz;
    }

    @Override public void cheatprogram$transformFrame(Vec3 before, Vec3 after, float yaw) {
        cheatprogram$restoreFrame();
        if(cheatprogram$snapshot==null)cheatprogram$snapshot=new double[9];
        cheatprogram$snapshot[0]=x;cheatprogram$snapshot[1]=y;cheatprogram$snapshot[2]=z;
        cheatprogram$snapshot[3]=xo;cheatprogram$snapshot[4]=yo;cheatprogram$snapshot[5]=zo;
        cheatprogram$snapshot[6]=xd;cheatprogram$snapshot[7]=yd;cheatprogram$snapshot[8]=zd;
        cheatprogram$box=getBoundingBox();
        // Match Vec3.yRot without allocating temporary vectors for every particle every frame.
        double cosine=Math.cos(yaw),sine=Math.sin(yaw);
        double px=x-before.x,pz=z-before.z,oldX=xo-before.x,oldZ=zo-before.z;
        setPos(after.x+px*cosine+pz*sine,after.y+y-before.y,after.z+pz*cosine-px*sine);
        xo=after.x+oldX*cosine+oldZ*sine;yo=after.y+yo-before.y;zo=after.z+oldZ*cosine-oldX*sine;
        double velocityX=xd,velocityZ=zd;
        xd=velocityX*cosine+velocityZ*sine;zd=velocityZ*cosine-velocityX*sine;
    }
    @Override public void cheatprogram$restoreFrame() {
        if(cheatprogram$box==null)return;
        x=cheatprogram$snapshot[0];y=cheatprogram$snapshot[1];z=cheatprogram$snapshot[2];
        xo=cheatprogram$snapshot[3];yo=cheatprogram$snapshot[4];zo=cheatprogram$snapshot[5];
        xd=cheatprogram$snapshot[6];yd=cheatprogram$snapshot[7];zd=cheatprogram$snapshot[8];
        setBoundingBox(cheatprogram$box);cheatprogram$box=null;
    }
}
