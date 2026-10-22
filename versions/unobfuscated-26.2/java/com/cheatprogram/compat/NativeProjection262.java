package com.cheatprogram.compat;
import com.mojang.blaze3d.systems.RenderSystem;
import org.joml.Matrix4f;
/** Same reverse depth and clip-volume convention as native 26.2 Projection.getMatrix(). */
public final class NativeProjection262 {
    private NativeProjection262() { }
    public static boolean zeroToOne(){return RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();}
    public static float nearClipZ(){return 1;}
    public static float farClipZ(){return zeroToOne()?0:-1;}
    public static boolean visibleDepth(float depth){return Float.isFinite(depth)&&depth>=farClipZ()&&depth<=1;}
    public static Matrix4f perspective(float fov,float aspect,float near,float far){
        return new Matrix4f().setPerspective(fov,aspect,far,near,zeroToOne());
    }
    public static Matrix4f ortho(float left,float right,float bottom,float top,float near,float far){
        return new Matrix4f().setOrtho(left,right,bottom,top,far,near,zeroToOne());
    }
}
