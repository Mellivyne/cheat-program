package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.module.visual.BlueArchiveHalo;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.cheatprogram.compat.gl.GlStateManager;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.cheatprogram.compat.gl.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.cheatprogram.compat.gl.CompiledShaderProgram;
import com.cheatprogram.compat.gl.CoreShaders;
import com.cheatprogram.compat.gl.FogParameters;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** A small, isolated view of the active original halo; it never renders the live world. */
public final class HaloModelPreview implements AutoCloseable {
    private TextureTarget target;
    private String selected="";
    private boolean ready,failed;
    private double elapsed;
    private long lastFrame;
    public float yaw=-28,pitch=48,zoom=1,panX,panY;
    public void reset(){yaw=-28;pitch=48;zoom=1;panX=panY=0;}
    public boolean ready(){return ready&&!failed;}
    public String statusKey(){return failed?"ui.halo.previewFailed":HaloModelLibrary.statusKey();}

    /** x/y/width/height are framebuffer pixels, matching Canvas.px()/py(). */
    public void draw(GuiGraphicsExtractor graphics,float x,float y,float width,float height,BlueArchiveHalo module){
        ready=false;
        if(module==null||!module.isPreviewVisible()||width<=1||height<=1||!R2D.isActive())return;
        if(!selected.equals(module.characterId())){selected=module.characterId();failed=false;elapsed=0;lastFrame=0;}
        HaloModelLibrary.Model model=HaloModelLibrary.model(selected);
        if(model==null||failed)return;
        long now=System.nanoTime();
        if(lastFrame!=0&&!Minecraft.getInstance().isPaused())elapsed+=Math.max(0,Math.min(.15,(now-lastFrame)/1_000_000_000d));
        lastFrame=now;
        int pixelsX=Math.max(1,Math.min(1024,Math.round(width))),pixelsY=Math.max(1,Math.min(1024,Math.round(height)));
        SavedState saved=new SavedState();
        try{
            try{
                RenderSystem.disableScissor();
                if(target==null){target=new TextureTarget("Cheat Program offscreen",pixelsX,pixelsY,true,com.mojang.blaze3d.GpuFormat.RGBA8_UNORM);com.cheatprogram.compat.gl.Framebuffers.setClearColor(target, 0,0,0,0);com.cheatprogram.compat.gl.Framebuffers.setFilterMode(target, GL11.GL_LINEAR);}
                else if(target.width!=pixelsX||target.height!=pixelsY)target.resize(pixelsX,pixelsY);
                RenderSystem.depthMask(true);RenderSystem.colorMask(true,true,true,true);
                com.cheatprogram.compat.gl.Framebuffers.clear(target);com.cheatprogram.compat.gl.Framebuffers.bindWrite(target, true);
                RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11.GL_GEQUAL);RenderSystem.depthMask(false);
				RenderSystem.enableBlend();RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);RenderSystem.disableCull();
                RenderSystem.setShaderFog(FogParameters.NO_FOG);RenderSystem.setShaderColor(1,1,1,1);
                RenderSystem.getModelViewStack().identity();
                float aspect=pixelsX/(float)pixelsY;
                RenderSystem.setProjectionMatrix(com.cheatprogram.compat.NativeProjection262.ortho(-aspect,aspect,-1,1,-100,100),ProjectionType.ORTHOGRAPHIC);
                float fit=.78f*Math.min(1,aspect)/HaloModelRenderer.previewRadius(model);
                float viewZoom=Float.isFinite(zoom)?Math.max(.1f,Math.min(8,zoom)):1;
                float viewPitch=Float.isFinite(pitch)?pitch:48,viewYaw=Float.isFinite(yaw)?yaw:-28;
                Matrix4f view=new Matrix4f().translate(finite(panX),-finite(panY),0)
                        .rotateX((float)Math.toRadians(viewPitch%360)).rotateY((float)Math.toRadians((viewYaw-module.previewRotation(elapsed))%360)).scale(fit*viewZoom);
                HaloModelRenderer.drawPreview(view,model,module.previewSize(),module.previewOpacity(),module.previewBrightness(),
                        module.previewAnimationTime(elapsed),module.previewStyle(),module.previewThickness(),module.previewPixelResolution(),new Quaternionf());
            }finally{saved.restore();}
            // The sole draw to the real GUI is this clipped texture quad. The model stays in its FBO.
            R2D.applyState();R2D.pushClip(x,y,width,height);
            try{
                saved.intersectClip();
                float guiScale=(float)Minecraft.getInstance().getWindow().getGuiScale();Matrix4f pose=com.cheatprogram.compat.GuiBridge.pose(graphics);
				// FBO accumulation contains premultiplied RGB, so fade RGB and alpha together on composition.
				int fade=R2D.fade(0xffffffff)>>>24,color=fade<<24|fade<<16|fade<<8|fade;
                BufferBuilder quad=com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
                quad.addVertex(pose,x/guiScale,y/guiScale,0).setUv(0,1).setColor(color);
                quad.addVertex(pose,x/guiScale,(y+height)/guiScale,0).setUv(0,0).setColor(color);
                quad.addVertex(pose,(x+width)/guiScale,(y+height)/guiScale,0).setUv(1,0).setColor(color);
                quad.addVertex(pose,(x+width)/guiScale,y/guiScale,0).setUv(1,1).setColor(color);
				RenderSystem.blendFuncSeparate(GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA,GL11.GL_ONE,GL11.GL_ONE_MINUS_SRC_ALPHA);
				RenderSystem.setShaderColor(1,1,1,1);RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
                RenderSystem.setShaderTexture(0,com.cheatprogram.compat.gl.Framebuffers.color(target));BufferUploader.drawWithShader(quad.buildOrThrow());
                ready=true;
            }finally{R2D.popClip();}
        }catch(Exception error){failed=true;CheatProgram.LOGGER.warn("Halo settings preview failed",error);}
        finally{saved.restore();}
    }
    private static float finite(float number){return Float.isFinite(number)?Math.max(-20,Math.min(20,number)):0;}
    /** Only owns its private target. The shared character and uploaded textures remain cached. */
    @Override public void close(){
        if(target!=null){SavedState saved=new SavedState();try{target.destroyBuffers();target=null;}finally{saved.restore();}}
        ready=false;failed=false;selected="";lastFrame=0;elapsed=0;
    }

    private static final class SavedState{
        final int[] viewport=new int[4],scissor=new int[4],colorMask=new int[4];
        final int readFramebuffer=GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING),drawFramebuffer=GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final boolean clipped=GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE),blend=GL11.glIsEnabled(GL11.GL_BLEND);
        final boolean depthMask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final int depthFunction=GL11.glGetInteger(GL11.GL_DEPTH_FUNC),srcRgb=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dstRgb=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),srcAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        final Matrix4f projection=new Matrix4f(RenderSystem.getProjectionMatrix()),modelView=new Matrix4f(RenderSystem.getModelViewStack()),textureMatrix=new Matrix4f(RenderSystem.getTextureMatrix());
        final ProjectionType projectionType=RenderSystem.getProjectionType();
        final FogParameters fog=RenderSystem.getShaderFog();
        final float[] color=RenderSystem.getShaderColor().clone();
        final int texture0=RenderSystem.getShaderTexture(0),texture1=RenderSystem.getShaderTexture(1),texture2=RenderSystem.getShaderTexture(2);
        final CompiledShaderProgram shader=RenderSystem.getShader();
        SavedState(){GL11.glGetIntegerv(GL11.GL_VIEWPORT,viewport);GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,scissor);GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK,colorMask);}
        void intersectClip(){
            if(!clipped)return;int[] current=new int[4];GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX,current);
            int x=Math.max(current[0],scissor[0]),y=Math.max(current[1],scissor[1]);
            int right=Math.min(current[0]+current[2],scissor[0]+scissor[2]),top=Math.min(current[1]+current[3],scissor[1]+scissor[3]);
            RenderSystem.enableScissor(x,y,Math.max(0,right-x),Math.max(0,top-y));
        }
        void restore(){
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER,readFramebuffer);GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER,drawFramebuffer);RenderSystem.viewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            RenderSystem.setProjectionMatrix(projection,projectionType);RenderSystem.getModelViewStack().set(modelView);RenderSystem.setTextureMatrix(textureMatrix);
            RenderSystem.setShaderFog(fog);RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);
            RenderSystem.setShaderTexture(0,texture0);RenderSystem.setShaderTexture(1,texture1);RenderSystem.setShaderTexture(2,texture2);if(shader==null)RenderSystem.clearShader();else RenderSystem.setShader(shader);
            if(depth)RenderSystem.enableDepthTest();else RenderSystem.disableDepthTest();if(cull)RenderSystem.enableCull();else RenderSystem.disableCull();if(blend)RenderSystem.enableBlend();else RenderSystem.disableBlend();
            RenderSystem.depthFunc(depthFunction);RenderSystem.depthMask(depthMask);RenderSystem.blendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);RenderSystem.colorMask(colorMask[0]!=0,colorMask[1]!=0,colorMask[2]!=0,colorMask[3]!=0);
            if(clipped)RenderSystem.enableScissor(scissor[0],scissor[1],scissor[2],scissor[3]);else RenderSystem.disableScissor();
        }
    }
}
