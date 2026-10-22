package com.cheatprogram.compat.gl;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.opengl.*;
import com.mojang.blaze3d.textures.*;
import org.lwjgl.opengl.*;
import java.util.*;

/** Uses GPU-owned textures and their managed FBO cache, including correct resize/destruction. */
public final class Framebuffers {
    private static final Map<RenderTarget,float[]> clearColors=new WeakHashMap<>();
    private Framebuffers(){}
    public static int color(RenderTarget target){return textureId(target.getColorTexture());}
    public static int depth(RenderTarget target){return textureId(target.getDepthTexture());}
    public static int textureId(GpuTexture texture){if(texture==null)return 0;if(texture instanceof GlTexture gl)return gl.glId();throw new IllegalStateException("This adapter requires Minecraft's OpenGL backend");}
    public static int framebuffer(RenderTarget target){
        if(!(target.getColorTexture() instanceof GlTexture gl))throw new IllegalStateException("OpenGL render target required");
        var backend=((com.cheatprogram.mixin.GlCommandEncoderCacheAccess.DeviceBackendAccess)(Object)com.mojang.blaze3d.systems.RenderSystem.getDevice()).cheatprogram$backend();
        if(!(backend instanceof com.cheatprogram.mixin.GlCommandEncoderCacheAccess.OpenGlBackendAccess device))throw new IllegalStateException("OpenGL render backend required");
        return device.cheatprogram$frameBufferCache().getFbo(device.cheatprogram$directStateAccess(),java.util.List.of(gl),(com.mojang.blaze3d.opengl.FrameBufferAttachment)target.getDepthTexture());
    }
    public static void bindWrite(RenderTarget target,boolean viewport){GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer(target));if(viewport)RenderSystem.viewport(0,0,target.width,target.height);}
    public static void setFilterMode(RenderTarget target,int mode){int previous=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);GlStateManager._bindTexture(color(target));GlStateManager._texParameter(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,mode);GlStateManager._texParameter(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,mode);GlStateManager._bindTexture(previous);}
    public static void setClearColor(RenderTarget target,float r,float g,float b,float a){clearColors.put(target,new float[]{r,g,b,a});}
    public static void clear(RenderTarget target){
        float[] c=clearColors.getOrDefault(target,new float[4]);
        var color=new org.joml.Vector4f(c[0],c[1],c[2],c[3]);
        var encoder=com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
        if(target.getDepthTexture()!=null)encoder.clearColorAndDepthTextures(target.getColorTexture(),color,target.getDepthTexture(),0.0);
        else encoder.clearColorTexture(target.getColorTexture(),color);
    }
}
