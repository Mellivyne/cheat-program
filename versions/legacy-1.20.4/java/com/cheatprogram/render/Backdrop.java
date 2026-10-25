package com.cheatprogram.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.cheatprogram.compat.legacy.LegacyRenderSystem;
import com.cheatprogram.compat.legacy.LegacyBufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.cheatprogram.compat.legacy.LegacyTesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/**
 * Captures what has been drawn to the main render target so far and blurs it with a dual Kawase
 * filter. Glass panels sample the result to show (and bend) whatever lies behind them.
 */
public final class Backdrop {
	private static final int MAX_LEVELS = 5;
	private static TextureTarget copy;
	private static final TextureTarget[] DOWN = new TextureTarget[MAX_LEVELS + 1];
	private static final TextureTarget[] UP = new TextureTarget[MAX_LEVELS + 1];
	private static int lastTexture = -1;

	private Backdrop() {
	}

	/** Texture produced by the last {@link #capture} call. */
	public static int texture() {
		return lastTexture;
	}

	/** Unblurred copy made by the last {@link #capture} call. */
	public static int sharpTexture() {
		return copy == null ? lastTexture : copy.getColorTextureId();
	}

	/**
	 * Copies the main target and blurs it.
	 *
	 * @param strength 0 = no blur, 1 = very strong
	 * @return GL texture id of the result
	 */
	public static int capture(float strength) {
		// Framebuffer blits and blur passes obey GL_SCISSOR_TEST too. Capturing under a content
		// clip left the rest of the reused texture stale, producing a horizontal split in the UI.
		boolean clipped = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		if (!clipped) return captureFullFrame(strength);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var box = stack.mallocInt(4);
			GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
			LegacyRenderSystem.disableScissor();
			try { return captureFullFrame(strength); }
			finally { LegacyRenderSystem.enableScissor(box.get(0), box.get(1), box.get(2), box.get(3)); }
		}
	}

	private static int captureFullFrame(float strength) {
		Minecraft mc = Minecraft.getInstance();
		RenderTarget main = mc.getMainRenderTarget();
		int w = main.width;
		int h = main.height;
		copy = ensure(copy, w, h);

		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId);
		GlStateManager._glBlitFrameBuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

		int result = copy.getColorTextureId();
		ShaderInstance down = Shaders.get(Shaders.BLUR_DOWN);
		ShaderInstance up = Shaders.get(Shaders.BLUR_UP);
		if (strength > 0.01f && down != null && up != null) {
			int levels = Math.max(1, Math.min(MAX_LEVELS, 1 + Math.round(strength * 4f)));
			float offset = 1.0f + strength * 1.75f;
			LegacyRenderSystem.disableBlend();
			LegacyRenderSystem.disableDepthTest();
			RenderTarget src = copy;
			for (int i = 1; i <= levels; i++) {
				DOWN[i] = ensure(DOWN[i], Math.max(1, w >> i), Math.max(1, h >> i));
				pass(Shaders.BLUR_DOWN, src, DOWN[i], offset);
				src = DOWN[i];
			}
			for (int i = levels - 1; i >= 1; i--) {
				UP[i] = ensure(UP[i], Math.max(1, w >> i), Math.max(1, h >> i));
				pass(Shaders.BLUR_UP, src, UP[i], offset);
				src = UP[i];
			}
			result = src.getColorTextureId();
			LegacyRenderSystem.enableBlend();
		}

		main.bindWrite(true);
		lastTexture = result;
		return result;
	}

	/** Draws a texture over the whole main target (used for the GUI backdrop). */
	public static void drawFullscreen(Shaders.Program program, int texture, float p0, float p1, float p2, float p3) {
		ShaderInstance shader = Shaders.get(program);
		if (shader == null) return;
		LegacyRenderSystem.setShader(shader);
		shader.setSampler("InSampler", texture);
		shader.safeGetUniform("Params").set(p0, p1, p2, p3);
		quad();
	}

	private static void pass(Shaders.Program program, RenderTarget src, RenderTarget dst, float offset) {
		ShaderInstance shader = Shaders.get(program);
		if (shader == null) return;
		dst.bindWrite(true);
		LegacyRenderSystem.setShader(shader);
		shader.setSampler("InSampler", src.getColorTextureId());
		shader.safeGetUniform("HalfPixel").set(0.5f / src.width, 0.5f / src.height);
		shader.safeGetUniform("Offset").set(offset);
		quad();
	}

	private static void quad() {
		LegacyBufferBuilder buffer = LegacyTesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
		buffer.addVertex(0.0f, 0.0f, 0.0f);
		buffer.addVertex(1.0f, 0.0f, 0.0f);
		buffer.addVertex(1.0f, 1.0f, 0.0f);
		buffer.addVertex(0.0f, 1.0f, 0.0f);
		com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(buffer.buildOrThrow());
	}

	private static TextureTarget ensure(TextureTarget target, int w, int h) {
		if (target == null) {
			target = new TextureTarget(w,h,false,net.minecraft.client.Minecraft.ON_OSX);
			target.setFilterMode(GL11.GL_LINEAR);
		} else if (target.width != w || target.height != h) {
			target.resize(w, h,Minecraft.ON_OSX);
			target.setFilterMode(GL11.GL_LINEAR);
		}
		return target;
	}
}
