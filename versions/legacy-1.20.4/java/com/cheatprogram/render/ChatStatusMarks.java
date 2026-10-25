package com.cheatprogram.render;

import com.cheatprogram.compat.legacy.LegacyRenderSystem;
import com.cheatprogram.compat.legacy.LegacyBufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.cheatprogram.compat.legacy.LegacyTesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.cheatprogram.compat.legacy.LegacyCoreShaders;
import org.joml.Matrix4f;

/** Telegram-style double ticks made from round-ended vector strokes, never a font character. */
public final class ChatStatusMarks {
	private ChatStatusMarks() { }
	public static void draw(GuiGraphics graphics, Canvas c, float x, float y, float size, int color) {
		LegacyRenderSystem.setShader(LegacyCoreShaders.POSITION_COLOR);
		LegacyBufferBuilder buffer = LegacyTesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		Matrix4f pose = graphics.pose().last().pose();
		float guiScale = (float) Minecraft.getInstance().getWindow().getGuiScale();
		float scale = c.scale * size / 16 / guiScale;
		float ox = c.px(x) / guiScale, oy = c.py(y) / guiScale;
		int fill = R2D.fade(color);
		stroke(buffer, pose, ox, oy, scale, 1, 7, 4, 10, fill);
		stroke(buffer, pose, ox, oy, scale, 4, 10, 12, 2, fill);
		stroke(buffer, pose, ox, oy, scale, 7.5f, 9, 8.5f, 10, fill);
		stroke(buffer, pose, ox, oy, scale, 8.5f, 10, 16.5f, 2, fill);
		com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(buffer.buildOrThrow());
	}
	private static void stroke(LegacyBufferBuilder b, Matrix4f pose, float ox, float oy, float s,
			float x0, float y0, float x1, float y1, int color) {
		float dx = x1 - x0, dy = y1 - y0, length = (float) Math.hypot(dx, dy), radius = .72f;
		float nx = -dy / length * radius, ny = dx / length * radius;
		vertex(b, pose, ox, oy, s, x0 + nx, y0 + ny, color); vertex(b, pose, ox, oy, s, x0 - nx, y0 - ny, color);
		vertex(b, pose, ox, oy, s, x1 - nx, y1 - ny, color); vertex(b, pose, ox, oy, s, x1 + nx, y1 + ny, color);
		cap(b, pose, ox, oy, s, x0, y0, radius, color); cap(b, pose, ox, oy, s, x1, y1, radius, color);
	}
	private static void cap(LegacyBufferBuilder b, Matrix4f pose, float ox, float oy, float s, float x, float y, float radius, int color) {
		for (int i = 0; i < 8; i++) {
			double a = i * Math.PI / 4, next = (i + 1) * Math.PI / 4;
			vertex(b, pose, ox, oy, s, x, y, color);
			vertex(b, pose, ox, oy, s, x + (float) Math.cos(a) * radius, y + (float) Math.sin(a) * radius, color);
			vertex(b, pose, ox, oy, s, x + (float) Math.cos(next) * radius, y + (float) Math.sin(next) * radius, color);
			vertex(b, pose, ox, oy, s, x, y, color);
		}
	}
	private static void vertex(LegacyBufferBuilder b, Matrix4f pose, float ox, float oy, float s, float x, float y, int color) {
		b.addVertex(pose, ox + x * s, oy + y * s, 0).setColor(color);
	}
}
