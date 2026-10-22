package com.cheatprogram.render;
import com.cheatprogram.module.visual.ModelWear;import com.cheatprogram.util.WearModelData;import com.mojang.blaze3d.ProjectionType;import com.mojang.blaze3d.pipeline.TextureTarget;import com.mojang.blaze3d.systems.RenderSystem;import com.mojang.blaze3d.vertex.*;import net.minecraft.client.Minecraft;import net.minecraft.client.gui.GuiGraphics;import net.minecraft.client.renderer.CoreShaders;import net.minecraft.client.renderer.FogParameters;import org.joml.Matrix4f;import org.lwjgl.opengl.GL11;
/** A private perspective framebuffer. Camera drags never touch the player's pose. */
public final class WearModelPreview implements AutoCloseable {
 private TextureTarget target;public float yaw=25,pitch=12,zoom=1,panX,panY;
 public void reset(){yaw=25;pitch=12;zoom=1;panX=panY=0;}
 public void draw(GuiGraphics graphics,float x,float y,float width,float height,ModelWear module){WearModelData.Model model=module.model();if(width<2||height<2)return;RenderStateSnapshot saved=new RenderStateSnapshot();try{
  graphics.flush();RenderSystem.disableScissor();int w=Math.max(1,Math.min(1536,(int)width)),h=Math.max(1,Math.min(1536,(int)height));if(target==null){target=new TextureTarget(w,h,true);target.setClearColor(0,0,0,0);}else if(target.width!=w||target.height!=h)target.resize(w,h);
  RenderSystem.depthMask(true);RenderSystem.colorMask(true,true,true,true);target.clear();target.bindWrite(true);RenderSystem.enableDepthTest();RenderSystem.depthFunc(GL11.GL_LEQUAL);RenderSystem.setShaderFog(FogParameters.NO_FOG);RenderSystem.getModelViewStack().identity();RenderSystem.setProjectionMatrix(new Matrix4f().setPerspective((float)Math.toRadians(45),w/(float)h,.02f,256),ProjectionType.PERSPECTIVE);
  Matrix4f view=new Matrix4f().translate(panX,-.9f-panY,-7).rotateX((float)Math.toRadians(pitch)).rotateY((float)Math.toRadians(yaw)).scale(zoom);
  reference(view);ParticlePlayerPreview.drawWearPlayer(view,target);target.bindWrite(true);RenderSystem.enableDepthTest();RenderSystem.depthMask(true);RenderSystem.depthFunc(GL11.GL_LEQUAL);RenderSystem.getModelViewStack().identity();if(model!=null)WearModelRenderer.draw(new Matrix4f(view).mul(module.transform(0)),model,(float)module.opacity.fraction());
  saved.restore();R2D.applyState();R2D.pushClip(x,y,width,height);try{saved.intersectClip();float scale=(float)Minecraft.getInstance().getWindow().getGuiScale();Matrix4f pose=R2D.canvasPose();BufferBuilder q=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);int color=R2D.fade(0xffffffff);q.addVertex(pose,x/scale,y/scale,0).setUv(0,1).setColor(color);q.addVertex(pose,x/scale,(y+height)/scale,0).setUv(0,0).setColor(color);q.addVertex(pose,(x+width)/scale,(y+height)/scale,0).setUv(1,0).setColor(color);q.addVertex(pose,(x+width)/scale,y/scale,0).setUv(1,1).setColor(color);RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);RenderSystem.setShaderTexture(0,target.getColorTextureId());BufferUploader.drawWithShader(q.buildOrThrow());}finally{R2D.popClip();}
 }finally{saved.restore();}}
 private static void reference(Matrix4f pose){RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.disableCull();RenderSystem.depthMask(false);BufferBuilder b=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
  quad(b,pose,-4,0,0,4,.008f,.015f,0x77ff7070);quad(b,pose,0,0,-4,.015f,.008f,4,0x777098ff);
  quad(b,pose,-.3f,0,0,.3f,1.8f,0,0x22cbd5e1);quad(b,pose,0,0,-.3f,0,1.8f,.3f,0x22cbd5e1);
  RenderSystem.setShader(CoreShaders.POSITION_COLOR);BufferUploader.drawWithShader(b.buildOrThrow());RenderSystem.depthMask(true);
 }
 private static void quad(BufferBuilder b,Matrix4f m,float x,float y,float z,float x1,float y1,float z1,int c){b.addVertex(m,x,y,z).setColor(c);b.addVertex(m,x,y1,z1).setColor(c);b.addVertex(m,x1,y1,z1).setColor(c);b.addVertex(m,x1,y,z).setColor(c);}
 @Override public void close(){if(target!=null){RenderStateSnapshot saved=new RenderStateSnapshot();try{target.destroyBuffers();target=null;}finally{saved.restore();}}}
}
