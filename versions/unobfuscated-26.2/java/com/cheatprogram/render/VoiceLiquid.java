package com.cheatprogram.render;

import com.cheatprogram.compat.gl.BufferUploader;

import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import com.cheatprogram.compat.gl.CompiledShaderProgram;
import org.joml.Matrix4f;

/** One procedural glass draw over the HUD's existing captured backdrop. No private FBO or textures. */
public final class VoiceLiquid {
    private static final Shaders.Program PROGRAM=new Shaders.Program("voice_liquid",DefaultVertexFormat.POSITION_TEX_COLOR);
    private VoiceLiquid() { }
    public static void draw(Canvas c,float x,float y,float width,float height,float phase,float amplitude,float result) {
        if(width<1||height<1||R2D.alpha()<.001f)return;
        RenderSystem.assertOnRenderThread();
        CompiledShaderProgram shader=Shaders.get(PROGRAM);
        if(shader==null||Backdrop.sharpTexture()<=0){
            c.lens(x,y,width,height,height/2,.06f,new R2D.GlassStyle(18*c.scale,12*c.scale,0,1.2f*c.scale,0,1));
            c.rrect(x+2,y+2,Math.max(1,width-4),Math.max(1,height*.43f),Math.min(height*.22f,16),0xED000000);return;
        }
        float px=c.px(x),py=c.py(y),w=width*c.scale,h=height*c.scale,pad=3*c.scale;
        float gui=(float)Minecraft.getInstance().getWindow().getGuiScale();Matrix4f pose=R2D.canvasPose();
        float[] color=RenderSystem.getShaderColor().clone();CompiledShaderProgram previous=RenderSystem.getShader();
        int texture0=RenderSystem.getShaderTexture(0),texture1=RenderSystem.getShaderTexture(1);
        try {
            RenderSystem.setShader(shader);RenderSystem.setShaderTexture(0,Backdrop.texture());RenderSystem.setShaderTexture(1,Backdrop.sharpTexture());
            shader.safeGetUniform("ScreenSize").set((float)R2D.width(),(float)R2D.height());
            shader.safeGetUniform("Size").set(w,h);
            shader.safeGetUniform("Flow").set(phase,amplitude,Math.max(0,Math.min(1,result)),c.scale);
            RenderSystem.setShaderColor(1,1,1,R2D.alpha());
            BufferBuilder b=com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
            b.addVertex(pose,(px-pad)/gui,(py-pad)/gui,0).setUv(-pad,-pad).setColor(-1);
            b.addVertex(pose,(px-pad)/gui,(py+h+pad)/gui,0).setUv(-pad,h+pad).setColor(-1);
            b.addVertex(pose,(px+w+pad)/gui,(py+h+pad)/gui,0).setUv(w+pad,h+pad).setColor(-1);
            b.addVertex(pose,(px+w+pad)/gui,(py-pad)/gui,0).setUv(w+pad,-pad).setColor(-1);
            BufferUploader.drawWithShader(b.buildOrThrow());
        } finally {
            RenderSystem.setShaderTexture(0,texture0);RenderSystem.setShaderTexture(1,texture1);
            RenderSystem.setShaderColor(color[0],color[1],color[2],color[3]);
            if(previous==null)RenderSystem.clearShader();else RenderSystem.setShader(previous);
        }
    }
    /** Called on the render thread at shutdown; backdrop storage belongs to the shared pipeline. */
    public static void shutdown(){if(PROGRAM.compiled!=null){PROGRAM.compiled.close();PROGRAM.compiled=null;}PROGRAM.failed=false;}
}
