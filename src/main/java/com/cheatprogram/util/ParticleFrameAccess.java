package com.cheatprogram.util;

import net.minecraft.world.phys.Vec3;

/** Implemented on Particle, used exclusively after this feature has claimed ownership. */
public interface ParticleFrameAccess {
    void cheatprogram$transformFrame(Vec3 before, Vec3 after, float yaw);
    void cheatprogram$captureOrigin();
    int cheatprogram$pathAge();
    void cheatprogram$advancePath();
    void cheatprogram$transformPath(Vec3 before, Vec3 after, float yaw, Vec3 path, boolean nativeMotion);
    void cheatprogram$restoreFrame();
}
