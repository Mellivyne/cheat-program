package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.cheatprogram.compat.gl.GlStateManager;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.cheatprogram.compat.gl.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import com.cheatprogram.compat.gl.CompiledShaderProgram;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Transforms snapshots after the real, interactive GUI has painted at its ordinary coordinates.
 * Backdrop.capture must continue to target main: this class never redirects GUI drawing into an FBO.
 * All positions and lengths passed to prepare are framebuffer pixels, with a top-left origin.
 */
public final class PanelTransitionRenderer implements AutoCloseable {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final Shaders.Program PROGRAM = new Shaders.Program("panel_transition", DefaultVertexFormat.POSITION);
	private TextureTarget underlay, current, previous;
	private int fbWidth, fbHeight, textureWidth, textureHeight;
	private float x, y, width, height, header, radius, strength;
	private int openingMode, closingMode, pageMode;
	private float enter = 0, exit = 0, page = 1, closeFrom = 1;
	private boolean initialized, closing, ready, failed, captured, hasCurrent, hasPrevious, freezePrevious;

	/** Call each frame before update/capture. A false result means the caller should use its original animation. */
	public boolean prepare(int fbW, int fbH, float x, float y, float w, float h, float header, float radius,
			String opening, String closing, String page, float strength) {
		captured = false; ready = false;
		if (failed || fbW < 1 || fbH < 1 || w <= 0 || h <= 0) return false;
		NativeState state = new NativeState();
		try {
			if (Shaders.get(PROGRAM) == null) return false;
			// A transition must sample the same pixel grid as the ordinary GUI. Downscaling
			// snapshots made its refractive rim jump between two different edge resolutions.
			int tw = fbW, th = fbH;
			boolean resized = fbWidth != fbW || fbHeight != fbH || textureWidth != tw || textureHeight != th;
			underlay = ensure(underlay, tw, th); current = ensure(current, tw, th);
			if (resized) { hasCurrent = hasPrevious = freezePrevious = false; this.page = 1; }
			fbWidth = fbW; fbHeight = fbH; textureWidth = tw; textureHeight = th;
			this.x = x; this.y = y; width = w; height = h;
			this.header = Math.max(0, Math.min(h, header)); this.radius = Math.max(0, Math.min(Math.min(w, h) / 2, radius));
			this.strength = Float.isFinite(strength) ? Math.max(0, Math.min(3, strength)) : 1;
			openingMode = globalMode(opening); closingMode = globalMode(closing); pageMode = pageMode(page);
			ready = true; return true;
		} catch (RuntimeException failure) { fail(failure); return false; }
		finally { state.restore(); }
	}
	/** settled is true when enabling this feature while an already-open GUI is visible. */
	public void update(boolean closing, boolean settled, float dt, float openMs, float closeMs, float pageMs) {
		float seconds = Float.isFinite(dt) ? Math.max(0, Math.min(.25f, dt)) : 0;
		if (!initialized) { initialized = true; enter = settled ? 1 : 0; }
		if (closing && !this.closing) { closeFrom = enter; exit = 0; }
		else if (!closing && this.closing) { enter = Math.max(0, closeFrom * (1 - exit)); exit = 0; }
		this.closing = closing;
		if (!ready) { enter = 1; if (closing) exit = 1; this.page = 1; return; }
		if (closing) exit = Math.min(1, exit + seconds * 1000 / duration(closeMs));
		else enter = Math.min(1, enter + seconds * 1000 / duration(openMs));
		if (this.page < 1) this.page = Math.min(1, this.page + seconds * 1000 / duration(pageMs));
	}
	private static float duration(float value) { return Float.isFinite(value) ? Math.max(1, value) : 250; }
	public boolean closingFinished() { return closing && (!ready || exit >= 1); }
	public boolean busy() { return ready && (closing || enter < 1 || page < 1); }
	/** Call when the page changes. The last completed identity frame becomes the immutable old page. */
	public void pageChanged() {
		if (!ready || !hasCurrent || pageMode == 6 || closing) return;
		freezePrevious = true; page = 0;
	}
	/** Call after the fullscreen GUI backdrop, before any panel primitive. Does not touch Backdrop's caches. */
	public void captureUnderlay() {
		if (!ready || !busy()) return;
		NativeState state = new NativeState();
		try {
			RenderTarget main = MC.gameRenderer.mainRenderTarget();
			if (main.width != fbWidth || main.height != fbHeight) return;
			RenderSystem.disableScissor(); copy(main, underlay); captured = true;
		} catch (RuntimeException failure) { fail(failure); }
		finally { state.restore(); }
	}
	/** Flush pending GuiGraphicsExtractor vertices before calling. Call even while settled to maintain page snapshots. */
	public void composite() {
		if (!ready) return;
		NativeState state = new NativeState();
		try {
			RenderTarget main = MC.gameRenderer.mainRenderTarget();
			if (main.width != fbWidth || main.height != fbHeight) return;
			RenderSystem.disableScissor();
			if (freezePrevious && hasCurrent) {
				// The old-page texture is only needed after the first animated page change.
				previous = ensure(previous, textureWidth, textureHeight);
				copy(current, previous); hasPrevious = true; freezePrevious = false;
			}
			copy(main, current); hasCurrent = true;
			// At rest the original full-resolution frame is left exactly as drawn. The cached copy
			// is sampled only while an animation is active, not every ordinary GUI frame.
			if (!captured || !busy()) return;
			CompiledShaderProgram shader = Shaders.get(PROGRAM); if (shader == null) return;
			com.cheatprogram.compat.gl.Framebuffers.bindWrite(main, true);
			RenderSystem.disableBlend(); RenderSystem.disableDepthTest(); RenderSystem.depthMask(false); RenderSystem.disableCull();
			RenderSystem.colorMask(true, true, true, true); RenderSystem.setShaderColor(1, 1, 1, 1);
			RenderSystem.setShader(shader);
			shader.bindSampler("Underlay", com.cheatprogram.compat.gl.Framebuffers.color(underlay));
			shader.bindSampler("Current", com.cheatprogram.compat.gl.Framebuffers.color(current));
			shader.bindSampler("Previous", com.cheatprogram.compat.gl.Framebuffers.color((hasPrevious ? previous : current)));
			shader.safeGetUniform("Screen").set((float) fbWidth, (float) fbHeight);
			shader.safeGetUniform("Panel").set(x, y, width, height);
			// ClickGui's real shadow uses 44 logical pixels at a 26-pixel corner radius.
			// The shader carries that captured shadow with the surface instead of drawing another.
			shader.safeGetUniform("Shape").set(header, radius, strength, radius * (44f / 26f));
			shader.safeGetUniform("Progress").set(closing ? closeFrom * (1 - exit) : enter, page, closing || enter < 1 ? 1f : 0f,
					page < 1 && hasPrevious ? 1f : 0f);
			shader.safeGetUniform("Modes").set((float) (closing ? closingMode : openingMode), (float) pageMode, closing ? 1f : 0f, 0f);
			boolean global = closing || enter < 1;
			// Page changes repaint only the inset content rectangle. The original header, glass
			// outline, rounded corners, and shadow are never erased or resampled during a turn.
			float inset = Math.max(2f, radius * .45f);
			int pad = (int) Math.ceil(96 + Math.max(width, height) * .6 * strength + radius * 2);
			int sx = Math.max(0, (int) Math.floor(global ? x - pad : x + inset));
			int ex = Math.min(fbWidth, (int) Math.ceil(global ? x + width + pad : x + width - inset));
			int sy = Math.max(0, (int) Math.floor(global ? y - pad : y + header + inset));
			int ey = Math.min(fbHeight, (int) Math.ceil(global ? y + height + pad : y + height - inset));
			RenderSystem.enableScissor(sx, fbHeight - ey, Math.max(0, ex - sx), Math.max(0, ey - sy));
			quad();
		} catch (RuntimeException failure) {
			// A failure must never leave a removed panel or a foreign draw framebuffer behind.
			try { if (hasCurrent) { RenderSystem.disableScissor(); copy(current, MC.gameRenderer.mainRenderTarget()); } }
			catch (RuntimeException ignored) { }
			fail(failure);
		} finally { captured = false; state.restore(); }
	}
	private void fail(RuntimeException failure) {
		ready = false;
		if (!failed) CheatProgram.LOGGER.warn("Control panel transition unavailable; restoring original GUI animation", failure);
		failed = true;
	}
	private static TextureTarget ensure(TextureTarget target, int width, int height) {
		if (target == null) { target = new TextureTarget("Cheat Program offscreen",width,height,false,com.mojang.blaze3d.GpuFormat.RGBA8_UNORM); com.cheatprogram.compat.gl.Framebuffers.setFilterMode(target, GL11.GL_LINEAR); }
		else if (target.width != width || target.height != height) { target.resize(width, height); com.cheatprogram.compat.gl.Framebuffers.setFilterMode(target, GL11.GL_LINEAR); }
		return target;
	}
	private static void copy(RenderTarget source, RenderTarget destination) {
		GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, com.cheatprogram.compat.gl.Framebuffers.framebuffer(source));
		GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, com.cheatprogram.compat.gl.Framebuffers.framebuffer(destination));
		GlStateManager._glBlitFrameBuffer(0, 0, source.width, source.height, 0, 0, destination.width, destination.height,
				GL11.GL_COLOR_BUFFER_BIT, source.width == destination.width && source.height == destination.height ? GL11.GL_NEAREST : GL11.GL_LINEAR);
	}
	private static void quad() {
		BufferBuilder buffer = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION);
		buffer.addVertex(0, 0, 0); buffer.addVertex(1, 0, 0); buffer.addVertex(1, 1, 0); buffer.addVertex(0, 1, 0);
		BufferUploader.drawWithShader(buffer.buildOrThrow());
	}
	private static int globalMode(String mode) {
		if (mode == null) return 0;
		return switch (mode) { case "panel_smooth" -> 1; case "panel_sticky" -> 2; case "panel_flip" -> 3;
			case "panel_rise" -> 4; case "panel_zoom" -> 5; case "panel_curtain" -> 6; default -> 0; };
	}
	private static int pageMode(String mode) {
		if (mode == null) return 0;
		return switch (mode) { case "panel_slide" -> 1; case "panel_crossfade" -> 2; case "panel_flip" -> 3;
			case "panel_sticky" -> 4; case "panel_liquid" -> 5; case "panel_none" -> 6; default -> 0; };
	}
	@Override public void close() {
		if(underlay==null&&current==null&&previous==null&&!initialized){ready=false;return;}
		NativeState state = new NativeState();
		try {
			if (underlay != null) underlay.destroyBuffers(); if (current != null) current.destroyBuffers(); if (previous != null) previous.destroyBuffers();
			underlay = current = previous = null;
			ready = captured = hasCurrent = hasPrevious = freezePrevious = initialized = closing = false;
			fbWidth = fbHeight = textureWidth = textureHeight = 0; enter = exit = 0; page = closeFrom = 1;
		} finally { state.restore(); }
	}
	/** Uses cached Minecraft wrappers on restoration, so native state and renderer caches stay in sync. */
	private static final class NativeState {
		final int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		final int[] viewport = new int[4], scissorBox = new int[4], colorMask = new int[4], bound = new int[3];
		final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
				srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
		final int blendRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), blendAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
		final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
				cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		final CompiledShaderProgram shader = RenderSystem.getShader();
		final int texture0 = RenderSystem.getShaderTexture(0), texture1 = RenderSystem.getShaderTexture(1), texture2 = RenderSystem.getShaderTexture(2);
		final float[] color = RenderSystem.getShaderColor().clone();
		NativeState() {
			GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport); GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox); GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorMask);
			for (int unit = 0; unit < 3; unit++) { GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit); bound[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D); }
			GlStateManager._activeTexture(activeTexture);
		}
		void restore() {
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read); GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
			RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
			RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha); GL20.glBlendEquationSeparate(blendRgb, blendAlpha);
			if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
			if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
			if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
			RenderSystem.depthMask(depthMask); RenderSystem.colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
			if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]); else RenderSystem.disableScissor();
			RenderSystem.setShaderTexture(0, texture0); RenderSystem.setShaderTexture(1, texture1); RenderSystem.setShaderTexture(2, texture2);
			RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
			if (shader == null) RenderSystem.clearShader(); else RenderSystem.setShader(shader); GlStateManager._glUseProgram(program);
			for (int unit = 0; unit < 3; unit++) { GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit); GlStateManager._bindTexture(bound[unit]); }
			GlStateManager._activeTexture(activeTexture);
		}
	}
}
