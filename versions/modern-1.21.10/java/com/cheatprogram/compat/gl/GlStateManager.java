package com.cheatprogram.compat.gl;

import org.lwjgl.opengl.*;

/** Routes state changes through Minecraft's OpenGL cache so native and custom passes agree. */
public final class GlStateManager {
    private GlStateManager() {}
    public enum SourceFactor { ZERO(0), ONE(1), SRC_COLOR(768), ONE_MINUS_SRC_COLOR(769), SRC_ALPHA(770), ONE_MINUS_SRC_ALPHA(771), DST_ALPHA(772), ONE_MINUS_DST_ALPHA(773), DST_COLOR(774), ONE_MINUS_DST_COLOR(775); public final int value; SourceFactor(int v){value=v;} }
    public enum DestFactor { ZERO(0), ONE(1), SRC_COLOR(768), ONE_MINUS_SRC_COLOR(769), SRC_ALPHA(770), ONE_MINUS_SRC_ALPHA(771), DST_ALPHA(772), ONE_MINUS_DST_ALPHA(773), DST_COLOR(774), ONE_MINUS_DST_COLOR(775); public final int value; DestFactor(int v){value=v;} }
    public static void _activeTexture(int unit){com.mojang.blaze3d.opengl.GlStateManager._activeTexture(unit);}
    public static void _bindTexture(int id){com.mojang.blaze3d.opengl.GlStateManager._bindTexture(id);}
    public static void _blendFuncSeparate(int a,int b,int c,int d){com.mojang.blaze3d.opengl.GlStateManager._blendFuncSeparate(a,b,c,d);}
    public static void _glBindBuffer(int target,int id){com.mojang.blaze3d.opengl.GlStateManager._glBindBuffer(target,id);}
    public static void _glBindFramebuffer(int target,int id){com.mojang.blaze3d.opengl.GlStateManager._glBindFramebuffer(target,id);}
    public static void _glBindVertexArray(int id){com.mojang.blaze3d.opengl.GlStateManager._glBindVertexArray(id);}
    public static void _glBlitFrameBuffer(int a,int b,int c,int d,int e,int f,int g,int h,int mask,int filter){com.mojang.blaze3d.opengl.GlStateManager._glBlitFrameBuffer(a,b,c,d,e,f,g,h,mask,filter);}
    public static void _glUseProgram(int id){com.mojang.blaze3d.opengl.GlStateManager._glUseProgram(id);RenderSystem.invalidateNativeState();}
    public static void _logicOp(int op){com.mojang.blaze3d.opengl.GlStateManager._logicOp(op);}
    public static void _pixelStore(int key,int value){com.mojang.blaze3d.opengl.GlStateManager._pixelStore(key,value);}
    public static void _texParameter(int target,int key,int value){com.mojang.blaze3d.opengl.GlStateManager._texParameter(target,key,value);}
}
