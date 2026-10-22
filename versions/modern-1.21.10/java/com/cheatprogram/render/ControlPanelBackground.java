package com.cheatprogram.render;

import com.cheatprogram.module.visual.ControlPanel;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.cheatprogram.compat.gl.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/** Rounded, refracted cover image shared by the main panel and its full settings pages. */
public final class ControlPanelBackground {
	private static final Shaders.Program PROGRAM=new Shaders.Program("panel_background",DefaultVertexFormat.POSITION_TEX_COLOR);
	private ControlPanelBackground() { }
	public static boolean draw(GuiGraphics graphics,Canvas c,ControlPanel panel,float w,float h,float radius) {
		if(panel==null||!panel.isEnabled()) return false;
		ControlPanelTextures.Image image=ControlPanelTextures.image(panel.imagePath());
		if(image==null) return false;
		return drawPreview(graphics,c,panel,image.texture(),image.width(),image.height(),panel.crop(),0,0,w,h,radius);
	}
	public static boolean drawPreview(GuiGraphics graphics,Canvas c,ControlPanel panel,ResourceLocation texture,
			int sourceWidth,int sourceHeight,ControlPanel.Crop selection,float x,float y,float w,float h,float radius) {
		if(panel==null||texture==null||sourceWidth<=0||sourceHeight<=0||w<=0||h<=0) return false;
		ControlPanel.Crop crop=selection==null?ControlPanel.Crop.FULL:selection;
		// The image shares the caller's opening animation; there is no second, delayed image fade.
		float alpha=panel.opacity()*R2D.alpha();
		if(alpha<.001f) return false;
		var shader=Shaders.get(PROGRAM);
		if(shader==null) return false;
		float width=w*c.scale,height=h*c.scale;
		float cover=Math.max(width/(sourceWidth*(float)crop.width()),height/(sourceHeight*(float)crop.height()))*panel.zoom();
		float u=width/(cover*sourceWidth),v=height/(cover*sourceHeight);
		RenderSystem.setShader(shader); RenderSystem.setShaderTexture(0,texture);
		shader.safeGetUniform("Size").set(width,height);
		shader.safeGetUniform("Radius").set(radius*c.scale);
		shader.safeGetUniform("ImageTransform").set(u,v,(float)crop.x()+((float)crop.width()-u)*(.5f+.5f*panel.imageX()),
				(float)crop.y()+((float)crop.height()-v)*(.5f+.5f*panel.imageY()));
		shader.safeGetUniform("CropBounds").set((float)crop.x(),(float)crop.y(),(float)(crop.x()+crop.width()),(float)(crop.y()+crop.height()));
		shader.safeGetUniform("Glass").set((12+38*panel.refraction())*c.scale,Math.max(8,28*c.scale),panel.frost(),panel.refraction());
		shader.safeGetUniform("ImageTone").set(panel.brightness(),alpha,Math.max(.5f,c.scale),0);
		RenderSystem.setShaderColor(1,1,1,1);
		float guiScale=(float)Minecraft.getInstance().getWindow().getGuiScale();
		Matrix4f pose=com.cheatprogram.compat.GuiBridge.pose(graphics);
		BufferBuilder b=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
		vertex(b,pose,c.px(x)/guiScale,c.py(y)/guiScale,0,0);
		vertex(b,pose,c.px(x)/guiScale,c.py(y+h)/guiScale,0,height);
		vertex(b,pose,c.px(x+w)/guiScale,c.py(y+h)/guiScale,width,height);
		vertex(b,pose,c.px(x+w)/guiScale,c.py(y)/guiScale,width,0);
		BufferUploader.drawWithShader(b.buildOrThrow());
		RenderSystem.setShaderColor(1,1,1,1);
		return true;
	}
	private static void vertex(BufferBuilder b,Matrix4f pose,float x,float y,float u,float v) { b.addVertex(pose,x,y,0).setUv(u,v).setColor(0xFFFFFFFF); }
}
