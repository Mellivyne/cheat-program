package com.cheatprogram.render;

import net.minecraft.world.phys.Vec3;

/** Camera-basis placement shared by world planes; yaw, pitch and camera translation are all retained. */
public final class ViewSpaceAnchor {
    private ViewSpaceAnchor() { }
    public static Vec3 position(Vec3 origin,Vec3 right,Vec3 up,Vec3 forward,double x,double y,double distance) {
        return origin.add(right.scale(x)).add(up.scale(y)).add(forward.scale(distance));
    }
}
