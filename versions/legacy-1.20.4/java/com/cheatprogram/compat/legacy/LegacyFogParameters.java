package com.cheatprogram.compat.legacy;
import com.mojang.blaze3d.shaders.FogShape;
public record LegacyFogParameters(float start,float end,FogShape shape,float red,float green,float blue,float alpha){
    public static final LegacyFogParameters NO_FOG=new LegacyFogParameters(Float.MAX_VALUE,Float.MAX_VALUE,FogShape.SPHERE,0,0,0,0);
}
