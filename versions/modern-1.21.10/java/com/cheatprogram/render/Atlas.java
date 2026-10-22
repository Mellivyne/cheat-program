package com.cheatprogram.render;

import com.cheatprogram.compat.gl.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/** A single RGBA texture that glyphs and icons are packed into with a simple shelf packer. */
final class Atlas {
	static final int SIZE = 2048;
	private static final int GAP = 2;

	private int textureId = -1;
	private int penX = GAP;
	private int penY = GAP;
	private int rowHeight;
	/** Incremented whenever the atlas is wiped, so cached regions can be invalidated. */
	int generation;

	record Region(int x, int y, int w, int h, float u0, float v0, float u1, float v1) {
	}

	int id() {
		if (textureId == -1) create();
		return textureId;
	}

	private void create() {
		textureId = com.mojang.blaze3d.opengl.GlStateManager._genTexture();
		GlStateManager._bindTexture(textureId);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		ByteBuffer empty = MemoryUtil.memCalloc(SIZE * SIZE * 4);
		try {
			resetUnpack();
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SIZE, SIZE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, empty);
		} finally {
			MemoryUtil.memFree(empty);
		}
		GlStateManager._bindTexture(0);
	}

	/** Forgets every packed region; the texture is overwritten as new regions are added. */
	void clear() {
		penX = GAP;
		penY = GAP;
		rowHeight = 0;
		generation++;
		if (textureId != -1) {
			com.mojang.blaze3d.opengl.GlStateManager._deleteTexture(textureId);
			textureId = -1;
		}
	}

	/** Uploads an image whose alpha channel is used as coverage. Returns null when the atlas is full. */
	Region add(BufferedImage image, float gamma) {
		int w = image.getWidth();
		int h = image.getHeight();
		if (w > SIZE - GAP * 2 || h > SIZE - GAP * 2) return null;
		if (penX + w + GAP > SIZE) {
			penX = GAP;
			penY += rowHeight + GAP;
			rowHeight = 0;
		}
		if (penY + h + GAP > SIZE) return null;
		int x = penX;
		int y = penY;
		penX += w + GAP;
		rowHeight = Math.max(rowHeight, h);

		int[] argb = image.getRGB(0, 0, w, h, null, 0, w);
		ByteBuffer buffer = MemoryUtil.memAlloc(w * h * 4);
		try {
			for (int pixel : argb) {
				int a = pixel >>> 24;
				if (gamma != 1f && a > 0 && a < 255) a = Math.round((float) Math.pow(a / 255.0, gamma) * 255f);
				buffer.put((byte) 255).put((byte) 255).put((byte) 255).put((byte) a);
			}
			buffer.flip();
			GlStateManager._bindTexture(id());
			resetUnpack();
			GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
			GlStateManager._bindTexture(0);
		} finally {
			MemoryUtil.memFree(buffer);
		}
		return new Region(x, y, w, h, x / (float) SIZE, y / (float) SIZE, (x + w) / (float) SIZE, (y + h) / (float) SIZE);
	}

	private static void resetUnpack() {
		GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
		GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
	}
}
