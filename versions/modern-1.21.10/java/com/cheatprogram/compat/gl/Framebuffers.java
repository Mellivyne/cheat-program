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
        if(!(target.getColorTexture() instanceof GlTexture gl)||!(com.mojang.blaze3d.systems.RenderSystem.getDevice() instanceof GlDevice device))throw new IllegalStateException("OpenGL render target required");
        return gl.getFbo(device.directStateAccess(),target.getDepthTexture());
    }
    public static void bindWrite(RenderTarget target,boolean viewport){GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER,framebuffer(target));if(viewport)RenderSystem.viewport(0,0,target.width,target.height);}
    public static void setFilterMode(RenderTarget target,int mode){target.setFilterMode(mode==GL11.GL_LINEAR?FilterMode.LINEAR:FilterMode.NEAREST);}
    public static void setClearColor(RenderTarget target,float r,float g,float b,float a){clearColors.put(target,new float[]{r,g,b,a});}
    public static void clear(RenderTarget target){
        float[] c=clearColors.getOrDefault(target,new float[4]);
        int color=(((int)(c[3]*255)&255)<<24)|(((int)(c[0]*255)&255)<<16)|(((int)(c[1]*255)&255)<<8)|((int)(c[2]*255)&255);
        var encoder=com.mojang.blaze3d.systems.RenderSystem.getDevice().createCommandEncoder();
        if(target.getDepthTexture()!=null)encoder.clearColorAndDepthTextures(target.getColorTexture(),color,target.getDepthTexture(),1.0);
        else encoder.clearColorTexture(target.getColorTexture(),color);
    }
}
