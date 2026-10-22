package com.cheatprogram.compat.gl;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.opengl.GlTexture;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.lwjgl.system.MemoryStack;
import net.minecraft.resources.Identifier;
import org.joml.*;
import org.lwjgl.opengl.*;

/** Immediate custom drawing adapter for the OpenGL backend used by Minecraft 1.21.10. */
public final class RenderSystem {
    private static CompiledShaderProgram shader;
    private static ProjectionMatrixBuffer projection;
    private static GpuBuffer noFog;
    private static final float[] color={1,1,1,1};
    private static float glintAlpha=1;
    private static final Matrix4f textureMatrix=new Matrix4f();
    private static float shaderLineWidth=1;
    private RenderSystem() {}
    public static boolean isOnRenderThread(){return com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread();}
    public static void assertOnRenderThread(){com.mojang.blaze3d.systems.RenderSystem.assertOnRenderThread();}
    public static void recordRenderCall(Runnable action){Minecraft.getInstance().execute(action);}
    public static void enableBlend(){com.mojang.blaze3d.opengl.GlStateManager._enableBlend(0);}
    public static void disableBlend(){com.mojang.blaze3d.opengl.GlStateManager._disableBlend(0);}
    public static void defaultBlendFunc(){blendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ZERO);}
    public static void blendFunc(int src,int dst){blendFuncSeparate(src,dst,src,dst);}
    public static void blendFuncSeparate(int a,int b,int c,int d){GlStateManager._blendFuncSeparate(a,b,c,d);}
    public static void blendFuncSeparate(GlStateManager.SourceFactor a,GlStateManager.DestFactor b,GlStateManager.SourceFactor c,GlStateManager.DestFactor d){blendFuncSeparate(a.value,b.value,c.value,d.value);}
    public static void enableDepthTest(){com.mojang.blaze3d.opengl.GlStateManager._enableDepthTest();}
    public static void disableDepthTest(){com.mojang.blaze3d.opengl.GlStateManager._disableDepthTest();}
    public static void depthFunc(int value){com.mojang.blaze3d.opengl.GlStateManager._depthFunc(value);}
    public static void depthMask(boolean value){com.mojang.blaze3d.opengl.GlStateManager._depthMask(value);}
    public static void enableCull(){com.mojang.blaze3d.opengl.GlStateManager._enableCull();}
    public static void disableCull(){com.mojang.blaze3d.opengl.GlStateManager._disableCull();}
    public static void enablePolygonOffset(){com.mojang.blaze3d.opengl.GlStateManager._enablePolygonOffset();}
    public static void disablePolygonOffset(){com.mojang.blaze3d.opengl.GlStateManager._disablePolygonOffset();}
    public static void polygonOffset(float a,float b){com.mojang.blaze3d.opengl.GlStateManager._polygonOffset(a,b);}
    public static void enableColorLogicOp(){com.mojang.blaze3d.opengl.GlStateManager._enableColorLogicOp();}
    public static void disableColorLogicOp(){com.mojang.blaze3d.opengl.GlStateManager._disableColorLogicOp();}
    public static void colorMask(boolean a,boolean b,boolean c,boolean d){com.mojang.blaze3d.opengl.GlStateManager._colorMask((a?1:0)|(b?2:0)|(c?4:0)|(d?8:0));}
    public static void viewport(int x,int y,int w,int h){com.mojang.blaze3d.opengl.GlStateManager._viewport(x,y,w,h);}
    public static void enableScissor(int x,int y,int w,int h){com.mojang.blaze3d.opengl.GlStateManager._enableScissorTest();com.mojang.blaze3d.opengl.GlStateManager._scissorBox(x,y,w,h);}
    public static void disableScissor(){com.mojang.blaze3d.opengl.GlStateManager._disableScissorTest();}
    public static void activeTexture(int unit){GlStateManager._activeTexture(unit);}
    public static int getShaderTexture(int unit){int old=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);activeTexture(GL13.GL_TEXTURE0+unit);int result=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);activeTexture(old);return result;}
    public static void setShaderTexture(int unit,int id){int old=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);activeTexture(GL13.GL_TEXTURE0+unit);GlStateManager._bindTexture(id);GL33.glBindSampler(unit,0);activeTexture(old);}
    public static void setShaderTexture(int unit,Identifier image){var owner=Minecraft.getInstance().getTextureManager().getTexture(image);var texture=owner.getTexture();if(!(texture instanceof GlTexture gl))throw new IllegalStateException("Expected OpenGL texture");setShaderTexture(unit,gl.glId());if(!(owner.getSampler() instanceof com.mojang.blaze3d.opengl.GlSampler sampler))throw new IllegalStateException("Expected OpenGL sampler");GL33.glBindSampler(unit,sampler.getId());}
    public static Matrix4f getProjectionMatrix(){return UniformCapture.projection(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrixBuffer());}
    public static Matrix4fStack getModelViewStack(){return com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();}
    public static Matrix4f getTextureMatrix(){return textureMatrix;}
    public static ProjectionType getProjectionType(){return com.mojang.blaze3d.systems.RenderSystem.getProjectionType();}
    public static void setProjectionMatrix(Matrix4f matrix,ProjectionType type){if(projection==null)projection=new ProjectionMatrixBuffer("Cheat Program custom pass");
        GpuBufferSlice slice=projection.getBuffer(matrix);UniformCapture.projection(slice,matrix);
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(slice,type);}
    public static void setTextureMatrix(Matrix4f matrix){textureMatrix.set(matrix);}
    public static FogParameters getShaderFog(){return new FogParameters(com.mojang.blaze3d.systems.RenderSystem.getShaderFog());}
    public static void setShaderFog(FogParameters fog){com.mojang.blaze3d.systems.RenderSystem.setShaderFog(fog.buffer()==null?noFog():fog.buffer());}
    public static float[] getShaderColor(){return color;}
    public static void setShaderColor(float a,float b,float c,float d){color[0]=a;color[1]=b;color[2]=c;color[3]=d;}
    public static float getShaderLineWidth(){return shaderLineWidth;}
    public static void lineWidth(float width){shaderLineWidth=width;GL11.glLineWidth(width);}
    public static float getShaderGlintAlpha(){return glintAlpha;}
    public static void setShaderGlintAlpha(float alpha){glintAlpha=alpha;}
    public static GpuBufferSlice getShaderLights(){return com.mojang.blaze3d.systems.RenderSystem.getShaderLights();}
    public static void setShaderLights(GpuBufferSlice lights){com.mojang.blaze3d.systems.RenderSystem.setShaderLights(lights);}
    public static void setupShaderLights(CompiledShaderProgram program){UniformCapture.applyLights(getShaderLights(),program);}
    public static CompiledShaderProgram getShader(){return shader;}
    public static void clearShader(){shader=null;GlStateManager._glUseProgram(0);}
    public static void setShader(CompiledShaderProgram program){shader=program;}
    public static void setShader(CoreShaders key){shader=program(key);}
    public static CompiledShaderProgram program(CoreShaders key){return key.program();}
    private static GpuBufferSlice noFog(){
        if(noFog==null){
            try(MemoryStack stack=MemoryStack.stackPush()){
                var data=Std140Builder.onStack(stack,FogRenderer.FOG_UBO_SIZE)
                    .putVec4(new Vector4f()).putFloat(Float.MAX_VALUE).putFloat(Float.MAX_VALUE)
                    .putFloat(Float.MAX_VALUE).putFloat(Float.MAX_VALUE).putFloat(Float.MAX_VALUE).putFloat(Float.MAX_VALUE).get();
                noFog=com.mojang.blaze3d.systems.RenderSystem.getDevice().createBuffer(()->"Cheat Program no fog",128,data);
            }
        }
        return noFog.slice();
    }

    /** Changes the real platform-owned texture sampler, preserving its address modes. */
    public static void setTextureFilter(net.minecraft.client.renderer.texture.AbstractTexture texture,boolean linear,boolean mipmaps){
        var old=texture.getSampler();
        var filter=linear?com.mojang.blaze3d.textures.FilterMode.LINEAR:com.mojang.blaze3d.textures.FilterMode.NEAREST;
        var sampler=com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getSampler(old.getAddressModeU(),old.getAddressModeV(),filter,filter,mipmaps);
        ((com.cheatprogram.mixin.RenderPipelineAccess.TextureSamplerAccess)texture).cheatprogram$sampler(sampler);
        textureParameters(texture,sampler);
    }
    /** Clamp the real sampler without losing a texture's filtering or mipmap choice. */
    public static void setTextureClamp(net.minecraft.client.renderer.texture.AbstractTexture texture,boolean clamp){
        var old=texture.getSampler();
        var address=clamp?com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE:com.mojang.blaze3d.textures.AddressMode.REPEAT;
        var sampler=com.mojang.blaze3d.systems.RenderSystem.getSamplerCache().getSampler(address,address,old.getMinFilter(),old.getMagFilter(),old.getMaxLod().isEmpty());
        ((com.cheatprogram.mixin.RenderPipelineAccess.TextureSamplerAccess)texture).cheatprogram$sampler(sampler);
        textureParameters(texture,sampler);
    }
    private static void textureParameters(net.minecraft.client.renderer.texture.AbstractTexture texture,com.mojang.blaze3d.textures.GpuSampler sampler){
        int unit=GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE),old=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if(!(texture.getTexture() instanceof GlTexture image))throw new IllegalStateException("Custom texture rendering requires the OpenGL backend");
        GlStateManager._bindTexture(image.glId());
        int minimum=sampler.getMinFilter()==com.mojang.blaze3d.textures.FilterMode.LINEAR?GL11.GL_LINEAR:GL11.GL_NEAREST;
        if(sampler.getMaxLod().isEmpty())minimum=sampler.getMinFilter()==com.mojang.blaze3d.textures.FilterMode.LINEAR?GL11.GL_LINEAR_MIPMAP_LINEAR:GL11.GL_NEAREST_MIPMAP_LINEAR;
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MIN_FILTER,minimum);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_MAG_FILTER,sampler.getMagFilter()==com.mojang.blaze3d.textures.FilterMode.LINEAR?GL11.GL_LINEAR:GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_S,sampler.getAddressModeU()==com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE?GL12.GL_CLAMP_TO_EDGE:GL11.GL_REPEAT);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D,GL11.GL_TEXTURE_WRAP_T,sampler.getAddressModeV()==com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE?GL12.GL_CLAMP_TO_EDGE:GL11.GL_REPEAT);
        GlStateManager._bindTexture(old);activeTexture(unit);
    }
    public static void shutdown(){
        if(projection!=null){projection.close();projection=null;}
        if(noFog!=null){noFog.close();noFog=null;}
        UniformCapture.clear();
    }
    public static void invalidateNativeState(){
        var encoder=((com.cheatprogram.mixin.GlCommandEncoderCacheAccess.DeviceBackendAccess)(Object)com.mojang.blaze3d.systems.RenderSystem.getDevice()).cheatprogram$backend().createCommandEncoder();
        if(encoder instanceof com.cheatprogram.mixin.GlCommandEncoderCacheAccess cache){cache.cheatprogram$lastPipeline(null);cache.cheatprogram$lastProgram(null);}
    }

}
