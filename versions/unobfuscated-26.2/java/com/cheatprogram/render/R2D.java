package com.cheatprogram.render;

import com.cheatprogram.compat.gl.GlStateManager;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.cheatprogram.compat.gl.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.cheatprogram.compat.gl.CompiledShaderProgram;
import com.cheatprogram.compat.gl.CoreShaders;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Immediate-mode 2D renderer working in framebuffer pixels. Every primitive is drawn right away,
 * so call order is paint order. Must be wrapped in {@link #begin}/{@link #end}.
 */
public final class R2D {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final Matrix4f POSE = new Matrix4f();
	private static final Deque<int[]> SCISSORS = new ArrayDeque<>();
	private static final float[] ALPHA_STACK = new float[64];
	private static float guiScale = 1f;
	private static int fbWidth = 1;
	private static int fbHeight = 1;
	private static float alpha = 1f;
	private static int alphaDepth;
	private static boolean active;

	private static GuiGraphicsExtractor graphics;
	/** White text gets a faint shadow so it stays readable on clear glass. */
	private static boolean textShadow = true;

	/**
	 * Parameters of a liquid glass surface. Lengths are in pixels. {@code feather} softens the edge
	 * so the glass fades into what is behind it, {@code haze} adds a milky veil and {@code clarity} is
	 * how much of the unblurred backdrop shows (and bends) along the rim.
	 */
	public record GlassStyle(float refraction, float bevel, float dispersion, float feather, float haze, float clarity) {
	}

	private R2D() {
	}

	public static void begin(GuiGraphicsExtractor g) {
		graphics = g;
		com.cheatprogram.compat.GuiBridge.flush(g);
		POSE.set(com.cheatprogram.compat.GuiBridge.pose(g));
		guiScale = (float) MC.getWindow().getGuiScale();
		fbWidth = MC.getWindow().getWidth();
		fbHeight = MC.getWindow().getHeight();
		alpha = 1f;
		alphaDepth = 0;
		SCISSORS.clear();
		// This canvas owns the full frame. A vanilla widget's stale scissor must not crop captures.
		RenderSystem.disableScissor();
		active = true;
		applyState();
	}

	/** Re-applies the blend/depth state, e.g. after a backdrop capture touched it. */
	public static void applyState() {
		RenderSystem.enableBlend();
		RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
				GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
		RenderSystem.disableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.disableCull();
		if (!SCISSORS.isEmpty()) applyScissor();
	}

	public static void end() {
		if (!SCISSORS.isEmpty()) {
			SCISSORS.clear();
			RenderSystem.disableScissor();
		}
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
		RenderSystem.depthMask(true);
		RenderSystem.enableDepthTest();
		RenderSystem.enableCull();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableBlend();
		active = false;
	}

	public static boolean isActive() {
		return active;
	}

	/** Snapshot for custom pixel-space meshes using the current canvas transform. */
	public static Matrix4f canvasPose() {
		return new Matrix4f(POSE);
	}

	/** Applies a framebuffer-pixel transform to both atlas text and Minecraft-font fallbacks. */
	public static void withPixelTransform(Matrix4f pixelTransform, Runnable action) {
		Matrix4f previous = new Matrix4f(POSE);
		Matrix4f guiTransform = new Matrix4f().scaling(1f / guiScale).mul(pixelTransform).scale(guiScale);
		POSE.mul(guiTransform);
		graphics.pose().pushMatrix(); graphics.pose().mul(com.cheatprogram.compat.GuiBridge.affine(guiTransform));
		try { action.run(); }
		finally { graphics.pose().popMatrix(); POSE.set(previous); }
	}

	public static int width() {
		return fbWidth;
	}

	public static int height() {
		return fbHeight;
	}

	// ---------------------------------------------------------------- alpha & clipping

	public static void pushAlpha(float a) {
		ALPHA_STACK[alphaDepth++] = alpha;
		alpha *= Math.max(0f, Math.min(1f, a));
	}

	public static void popAlpha() {
		alpha = ALPHA_STACK[--alphaDepth];
	}

	public static float alpha() {
		return alpha;
	}

	public static int fade(int argb) {
		if (alpha >= 0.999f) return argb;
		int a = Math.round((argb >>> 24) * alpha);
		return (a << 24) | (argb & 0xFFFFFF);
	}

	/** Restricts drawing to a rectangle (pixels, top-left origin), intersected with the current clip. */
	public static void pushClip(float x, float y, float w, float h) {
		int x0 = (int) Math.floor(x);
		int y0 = (int) Math.floor(y);
		int x1 = (int) Math.ceil(x + w);
		int y1 = (int) Math.ceil(y + h);
		int[] parent = SCISSORS.peek();
		if (parent != null) {
			x0 = Math.max(x0, parent[0]);
			y0 = Math.max(y0, parent[1]);
			x1 = Math.min(x1, parent[2]);
			y1 = Math.min(y1, parent[3]);
		}
		SCISSORS.push(new int[]{x0, y0, Math.max(x0, x1), Math.max(y0, y1)});
		applyScissor();
	}

	public static void popClip() {
		SCISSORS.pop();
		if (SCISSORS.isEmpty()) RenderSystem.disableScissor();
		else applyScissor();
	}

	private static void applyScissor() {
		int[] s = SCISSORS.peek();
		RenderSystem.enableScissor(s[0], fbHeight - s[3], s[2] - s[0], s[3] - s[1]);
	}

	// ---------------------------------------------------------------- primitives

	private static VertexConsumer vertex(BufferBuilder buffer, float x, float y) {
		return buffer.addVertex(POSE, x / guiScale, y / guiScale, 0f);
	}

	public static void rect(float x, float y, float w, float h, int color) {
		gradientV(x, y, w, h, color, color);
	}

	public static void gradientV(float x, float y, float w, float h, int top, int bottom) {
		flatQuad(x, y, w, h, fade(top), fade(top), fade(bottom), fade(bottom));
	}

	public static void gradientH(float x, float y, float w, float h, int left, int right) {
		flatQuad(x, y, w, h, fade(left), fade(right), fade(right), fade(left));
	}

	private static void flatQuad(float x, float y, float w, float h, int tl, int tr, int br, int bl) {
		if (w <= 0 || h <= 0 || ((tl | tr | br | bl) >>> 24) == 0) return;
		RenderSystem.setShader(CoreShaders.POSITION_COLOR);
		BufferBuilder b = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
		vertex(b, x, y).setColor(tl);
		vertex(b, x, y + h).setColor(bl);
		vertex(b, x + w, y + h).setColor(br);
		vertex(b, x + w, y).setColor(tr);
		BufferUploader.drawWithShader(b.buildOrThrow());
	}

	public static void rrect(float x, float y, float w, float h, float r, int color) {
		rrect(x, y, w, h, r, r, r, r, color, color, 0f, 0, 0);
	}

	public static void rrect(float x, float y, float w, float h, float r, int top, int bottom) {
		rrect(x, y, w, h, r, r, r, r, top, bottom, 0f, 0, 0);
	}

	/** Rounded rectangle with per-corner radii (tl, tr, br, bl), vertical fill gradient and an optional gradient border. */
	public static void rrect(float x, float y, float w, float h, float rtl, float rtr, float rbr, float rbl,
							 int fillTop, int fillBottom, float border, int borderTop, int borderBottom) {
		if (w <= 0.01f || h <= 0.01f) return;
		int ft = fade(fillTop);
		int fb = fade(fillBottom);
		int bt = fade(borderTop);
		int bb = fade(borderBottom);
		if (((ft | fb) >>> 24) == 0 && (border <= 0 || ((bt | bb) >>> 24) == 0)) return;
		CompiledShaderProgram shader = Shaders.get(Shaders.RRECT);
		if (shader == null) {
			flatQuad(x, y, w, h, ft, ft, fb, fb);
			return;
		}
		RenderSystem.setShader(shader);
		shader.safeGetUniform("Size").set(w, h);
		shader.safeGetUniform("Radius").set(rtl, rtr, rbr, rbl);
		shader.safeGetUniform("Params").set(border, 0f, 0f, 0f);
		setColor(shader, "BorderTop", bt);
		setColor(shader, "BorderBottom", bb);
		localQuad(x, y, w, h, 1.5f, ft, fb);
	}

	/** Soft drop shadow / glow around a rounded rectangle. */
	public static void shadow(float x, float y, float w, float h, float r, float softness, int color) {
		int c = fade(color);
		if ((c >>> 24) == 0 || softness <= 0) return;
		CompiledShaderProgram shader = Shaders.get(Shaders.RRECT);
		if (shader == null) return;
		RenderSystem.setShader(shader);
		shader.safeGetUniform("Size").set(w, h);
		shader.safeGetUniform("Radius").set(r, r, r, r);
		shader.safeGetUniform("Params").set(0f, softness, 0f, 0f);
		setColor(shader, "BorderTop", 0);
		setColor(shader, "BorderBottom", 0);
		localQuad(x, y, w, h, softness * 1.6f + 2f, c, c);
	}

	/**
	 * Liquid glass surface sampling the last {@link Backdrop#capture}. The tint colours carry
	 * the tint rgb in their colour channels and the tint strength in alpha.
	 */
	public static void glass(float x, float y, float w, float h, float r, int tintTop, int tintBottom, GlassStyle style) {
		if (w <= 0.01f || h <= 0.01f || alpha <= 0.001f) return;
		CompiledShaderProgram shader = Shaders.get(Shaders.GLASS);
		int texture = Backdrop.texture();
		if (shader == null || texture <= 0) {
			rrect(x, y, w, h, r, withAlpha(tintTop, 0.85f), withAlpha(tintBottom, 0.85f));
			return;
		}
		RenderSystem.setShader(shader);
		RenderSystem.setShaderTexture(0, texture);
		RenderSystem.setShaderTexture(1, Backdrop.sharpTexture());
		shader.safeGetUniform("Size").set(w, h);
		shader.safeGetUniform("Radius").set(r, r, r, r);
		shader.safeGetUniform("Glass").set(style.refraction(), style.bevel(), style.dispersion(), 0f);
		shader.safeGetUniform("Rim").set(style.feather(), 0f, style.haze(), style.clarity());
		RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
		localQuad(x, y, w, h, 1.5f, tintTop, tintBottom);
		RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
	}

	/** Clear notification surface whose injected liquid has its own curved, moving refraction front. */
	public static void liquidToggle(float x, float y, float w, float h, float r, float progress, float seconds, float lift, GlassStyle style) {
		if (w <= .01f || h <= .01f || alpha <= .001f) return;
		float fill = Math.max(0, Math.min(1, progress));
		CompiledShaderProgram shader = Shaders.get(Shaders.LIQUID_TOGGLE);
		int texture = Backdrop.texture();
		int white = withAlpha(0xFFFFFFFF, Math.max(.015f, Math.min(.2f, lift)));
		if (shader == null || texture <= 0) {
			glass(x, y, w, h, r, withAlpha(white, lift * .3f), withAlpha(white, lift * .3f), style);
			float filledWidth = w * fill;
			if (filledWidth > .5f) glass(x, y, filledWidth, h, Math.min(h * .5f, filledWidth * .5f), white, white,
					new GlassStyle(style.refraction() * 1.7f, style.bevel(), style.dispersion(), style.feather(), style.haze(), style.clarity()));
			return;
		}
		RenderSystem.setShader(shader);
		RenderSystem.setShaderTexture(0, texture);
		RenderSystem.setShaderTexture(1, Backdrop.sharpTexture());
		shader.safeGetUniform("Size").set(w, h);
		shader.safeGetUniform("Radius").set(r, r, r, r);
		shader.safeGetUniform("Glass").set(style.refraction(), style.bevel(), style.dispersion(), 0f);
		shader.safeGetUniform("Rim").set(style.feather(), 0f, style.haze(), style.clarity());
		shader.safeGetUniform("Flow").set(fill, Math.max(0, seconds));
		RenderSystem.setShaderColor(1, 1, 1, alpha);
		localQuad(x, y, w, h, 1.5f, white, white);
		RenderSystem.setShaderColor(1, 1, 1, 1);
	}

	private static void localQuad(float x, float y, float w, float h, float expand, int top, int bottom) {
		BufferBuilder b = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		float x0 = x - expand;
		float y0 = y - expand;
		float x1 = x + w + expand;
		float y1 = y + h + expand;
		vertex(b, x0, y0).setUv(-expand, -expand).setColor(top);
		vertex(b, x0, y1).setUv(-expand, h + expand).setColor(bottom);
		vertex(b, x1, y1).setUv(w + expand, h + expand).setColor(bottom);
		vertex(b, x1, y0).setUv(w + expand, -expand).setColor(top);
		BufferUploader.drawWithShader(b.buildOrThrow());
	}

	private static void setColor(CompiledShaderProgram shader, String name, int argb) {
		shader.safeGetUniform(name).set(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, (argb >>> 24) / 255f);
	}

	public static int withAlpha(int argb, float a) {
		return (Math.round(Math.max(0, Math.min(1, a)) * 255) << 24) | (argb & 0xFFFFFF);
	}

	// ---------------------------------------------------------------- text & icons

	public static float textWidth(Fonts.Face face, String text, float sizePx) {
		return Fonts.width(face, text, sizePx);
	}

	public static void setTextShadow(boolean enabled) {
		textShadow = enabled;
	}

	/** Draws text with its baseline at {@code baseline}; returns the advance width. */
	public static float text(Fonts.Face face, String text, float x, float baseline, float sizePx, int color) {
		if (text == null || text.isEmpty()) return 0;
		int c = fade(color);
		if ((c >>> 24) == 0) return Fonts.width(face, text, sizePx);
		if (Fonts.useVanilla()) return vanillaText(face, text, x, baseline, sizePx, c);
		int px = Fonts.pixelSize(sizePx);
		float startX = Math.round(x);
		float by = Math.round(baseline);
		BufferBuilder b = null;
		// Shadow pass first (same buffer, so it stays under the glyphs), then the text itself.
		int passes = textShadow && (c & 0xFFFFFF) > 0x808080 ? 2 : 1;
		float width = 0;
		for (int pass = passes - 1; pass >= 0; pass--) {
			boolean shadow = pass == 1;
			int col = shadow ? ((Math.round((c >>> 24) * 0.42f)) << 24) : c;
			float off = shadow ? Math.max(1, Math.round(px / 14f)) : 0;
			float pen = startX;
			for (int i = 0; i < text.length(); ) {
				int cp = text.codePointAt(i);
				i += Character.charCount(cp);
				Fonts.Glyph glyph = Fonts.glyph(face, px, cp);
				if (glyph.vanilla != null) {
					if (!shadow) {
						if (b != null) { drawTextured(b); b = null; }
						vanillaText(face, glyph.vanilla, pen, by, px, c);
					}
					pen += glyph.advance;
					continue;
				}
				Atlas.Region region = Fonts.region(glyph);
				if (region != null) {
					if (b == null) b = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
					texQuad(b, Math.round(pen) + glyph.offsetX, by + glyph.offsetY + off, region, col);
				}
				pen += glyph.advance;
			}
			width = pen - startX;
		}
		if (b != null) drawTextured(b);
		return width;
	}

	/** Per-code-point RGB, with one shared opacity. Atlas glyphs remain batched; font choice and pose are unchanged. */
	public static float textColors(Fonts.Face face, String text, float x, float baseline, float sizePx, int opacity,
								 java.util.function.IntUnaryOperator rgbAtCodePoint) {
		if (text == null || text.isEmpty()) return 0;
		int alpha = fade(Math.max(0, Math.min(255, opacity)) << 24 | 0xFFFFFF) >>> 24;
		if (alpha == 0) return Fonts.width(face, text, sizePx);
		if (Fonts.useVanilla()) {
			var component = net.minecraft.network.chat.Component.empty();
			for (int offset = 0, ordinal = 0; offset < text.length(); ordinal++) {
				int cp = text.codePointAt(offset);
				int rgb = rgbAtCodePoint.applyAsInt(ordinal) & 0xFFFFFF;
				component.append(Fonts.vanillaComponent(face, new String(Character.toChars(cp))).copy()
						.withStyle(style -> style.withColor(rgb)));
				offset += Character.charCount(cp);
			}
			float k = Fonts.pixelSize(sizePx) / 9f;
			var pose = graphics.pose();
			pose.pushMatrix();
			try {
				pose.translate(x / guiScale, (baseline - 7f * k) / guiScale);
				pose.scale(k / guiScale, k / guiScale);
				com.cheatprogram.compat.GuiBridge.drawString(graphics, MC.font, component, 0, 0, alpha << 24 | 0xFFFFFF, textShadow);
			} finally { pose.popMatrix(); }
			com.cheatprogram.compat.GuiBridge.flush(graphics);
			applyState();
			return Fonts.width(face, text, sizePx);
		}
		int px = Fonts.pixelSize(sizePx);
		float startX = Math.round(x), by = Math.round(baseline), width = 0;
		BufferBuilder buffer = null;
		for (int pass = textShadow ? 1 : 0; pass >= 0; pass--) {
			boolean shadow = pass == 1;
			float off = shadow ? Math.max(1, Math.round(px / 14f)) : 0;
			float pen = startX;
			for (int offset = 0, ordinal = 0; offset < text.length(); ordinal++) {
				int cp = text.codePointAt(offset);
				offset += Character.charCount(cp);
				int color = alpha << 24 | rgbAtCodePoint.applyAsInt(ordinal) & 0xFFFFFF;
				Fonts.Glyph glyph = Fonts.glyph(face, px, cp);
				if (glyph.vanilla != null) {
					if (!shadow) {
						if (buffer != null) { drawTextured(buffer); buffer = null; }
						vanillaText(face, glyph.vanilla, pen, by, px, color);
					}
					pen += glyph.advance;
					continue;
				}
				Atlas.Region region = Fonts.region(glyph);
				if (region != null) {
					if (buffer == null) buffer = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
					texQuad(buffer, Math.round(pen) + glyph.offsetX, by + glyph.offsetY + off, region,
							shadow ? Math.round(alpha * .42f) << 24 : color);
				}
				pen += glyph.advance;
			}
			width = pen - startX;
		}
		if (buffer != null) drawTextured(buffer);
		return width;
	}

	/** Fallback when Java2D is missing from the runtime: Minecraft's own font, scaled to size. */
	public static float textSpatialColors(Fonts.Face face, String text, float x, float baseline, float sizePx, int opacity,
			java.util.function.DoubleToIntFunction rgbAtPixel) {
		if (text == null || text.isEmpty()) return 0;
		int alpha = fade(Math.max(0, Math.min(255, opacity)) << 24 | 0xFFFFFF) >>> 24;
		if (alpha == 0) return Fonts.width(face, text, sizePx);
		if (Fonts.useVanilla()) return vanillaSpatialText(face, text, x, baseline, sizePx, alpha, rgbAtPixel);
		int px = Fonts.pixelSize(sizePx);
		float startX = Math.round(x), by = Math.round(baseline), width = 0;
		BufferBuilder buffer = null;
		for (int pass = textShadow ? 1 : 0; pass >= 0; pass--) {
			boolean shadow = pass == 1;
			float off = shadow ? Math.max(1, Math.round(px / 14f)) : 0;
			float pen = startX;
			for (int offset = 0; offset < text.length();) {
				int cp = text.codePointAt(offset);
				offset += Character.charCount(cp);
				Fonts.Glyph glyph = Fonts.glyph(face, px, cp);
				if (glyph.vanilla != null) {
					if (!shadow) {
						if (buffer != null) { drawTextured(buffer); buffer = null; }
						vanillaSpatialText(face, glyph.vanilla, pen, by, px, alpha, rgbAtPixel);
					}
					pen += glyph.advance;
					continue;
				}
				Atlas.Region region = Fonts.region(glyph);
				if (region != null) {
					if (buffer == null) buffer = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
					float left = Math.round(pen) + glyph.offsetX, top = by + glyph.offsetY + off;
					if (shadow) texQuad(buffer, left, top, region, Math.round(alpha * .42f) << 24);
					else {
						// Short strips retain the palette's curvature across a wide glyph; all strips share one batch.
						int strips = Math.max(1, (int) Math.ceil(region.w() / 2f));
						for (int strip = 0; strip < strips; strip++) {
							float a = (float) strip / strips, z = (float) (strip + 1) / strips;
							float x0 = left + region.w() * a, x1 = left + region.w() * z;
							float u0 = region.u0() + (region.u1() - region.u0()) * a, u1 = region.u0() + (region.u1() - region.u0()) * z;
							int c0 = alpha << 24 | rgbAtPixel.applyAsInt(x0) & 0xFFFFFF, c1 = alpha << 24 | rgbAtPixel.applyAsInt(x1) & 0xFFFFFF;
							vertex(buffer, x0, top).setUv(u0, region.v0()).setColor(c0);
							vertex(buffer, x0, top + region.h()).setUv(u0, region.v1()).setColor(c0);
							vertex(buffer, x1, top + region.h()).setUv(u1, region.v1()).setColor(c1);
							vertex(buffer, x1, top).setUv(u1, region.v0()).setColor(c1);
						}
					}
				}
				pen += glyph.advance;
			}
			width = pen - startX;
		}
		if (buffer != null) drawTextured(buffer);
		return width;
	}

	private static float vanillaSpatialText(Fonts.Face face, String text, float x, float baseline, float sizePx, int alpha,
			java.util.function.DoubleToIntFunction rgbAtPixel) {
		float k = Fonts.pixelSize(sizePx) / 9f;
		Matrix4f matrix = new Matrix4f(com.cheatprogram.compat.GuiBridge.pose(graphics))
				.translate(x / guiScale, (baseline - 7f * k) / guiScale, 0f).scale(k / guiScale, k / guiScale, 1f);
		Matrix4f inverse = new Matrix4f(matrix).invert();
		com.cheatprogram.compat.GuiBridge.drawSpecial(graphics, buffers -> com.cheatprogram.compat.GuiBridge.drawInBatch(MC.font,Fonts.vanillaComponent(face, text), 0, 0, alpha << 24 | 0xFFFFFF,
				textShadow, matrix, type -> new SpatialColorVertex(buffers.getBuffer(type), inverse, x, k, rgbAtPixel),
				net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, 15728880));
		applyState();
		return Fonts.vanillaWidth(face, text, sizePx);
	}

	/** Recolors the actual vanilla glyph vertices; its shaping, bold face and font fallback stay native. */
	private static final class SpatialColorVertex implements VertexConsumer {
		private final VertexConsumer target;
		private final Matrix4f inverse;
		private final float origin, scale;
		private final java.util.function.DoubleToIntFunction colors;
		private float pixel;
		private SpatialColorVertex(VertexConsumer target, Matrix4f inverse, float origin, float scale, java.util.function.DoubleToIntFunction colors) {
			this.target = target; this.inverse = inverse; this.origin = origin; this.scale = scale; this.colors = colors;
		}
		@Override public VertexConsumer addVertex(float x, float y, float z) {
			pixel = origin + (inverse.m00() * x + inverse.m10() * y + inverse.m20() * z + inverse.m30()) * scale;
			target.addVertex(x, y, z); return this;
		}
		@Override public VertexConsumer setColor(int r, int g, int b, int a) {
			int rgb = colors.applyAsInt(pixel);
			target.setColor(r * (rgb >> 16 & 255) / 255, g * (rgb >> 8 & 255) / 255, b * (rgb & 255) / 255, a); return this;
		}
		@Override public VertexConsumer setUv(float u, float v) { target.setUv(u, v); return this; }
		@Override public VertexConsumer setUv1(int u, int v) { target.setUv1(u, v); return this; }
		@Override public VertexConsumer setUv2(int u, int v) { target.setUv2(u, v); return this; }
		@Override public VertexConsumer setNormal(float x, float y, float z) { target.setNormal(x, y, z); return this; }
        @Override public VertexConsumer setLineWidth(float width) { target.setLineWidth(width); return this; }
        @Override public VertexConsumer setColor(int argb) { return setColor(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24); }
	}

	/** Fallback when Java2D is missing from the runtime: Minecraft's own font, scaled to size. */
	private static float vanillaText(Fonts.Face face, String text, float x, float baseline, float sizePx, int color) {
		float k = Fonts.pixelSize(sizePx) / 9f;
		var pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(x / guiScale, (baseline - 7f * k) / guiScale);
		pose.scale(k / guiScale, k / guiScale);
		com.cheatprogram.compat.GuiBridge.drawString(graphics, MC.font, Fonts.vanillaComponent(face, text), 0, 0, color, textShadow);
		pose.popMatrix();
		com.cheatprogram.compat.GuiBridge.flush(graphics);
		applyState();
		return Fonts.vanillaWidth(face, text, sizePx);
	}

	/** Draws text vertically centred on {@code centerY}. */
	public static float textCentered(Fonts.Face face, String text, float x, float centerY, float sizePx, int color) {
		return text(face, text, x, centerY + Fonts.pixelSize(sizePx) * 0.36f, sizePx, color);
	}

	public static void icon(Icon icon, float x, float y, float sizePx, int color) {
		int c = fade(color);
		if ((c >>> 24) == 0 || !Fonts.available()) return;
		int px = Math.max(6, Math.round(sizePx));
		Atlas.Region region = Icons.region(icon, px);
		if (region == null) return;
		BufferBuilder b = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		texQuad(b, Math.round(x) - Icons.PAD, Math.round(y) - Icons.PAD, region, c);
		drawTextured(b);
	}

	public static void iconCentered(Icon icon, float cx, float cy, float sizePx, int color) {
		int px = Math.max(6, Math.round(sizePx));
		icon(icon, cx - px / 2f, cy - px / 2f, px, color);
	}

	/** Draws part of a registered texture (u/v in 0..1), e.g. a player's skin face. */
	public static void texture(net.minecraft.resources.Identifier location, float x, float y, float w, float h,
							   float u0, float v0, float u1, float v1, int color) {
		int c = fade(color);
		if ((c >>> 24) == 0) return;
		BufferBuilder b = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		vertex(b, x, y).setUv(u0, v0).setColor(c);
		vertex(b, x, y + h).setUv(u0, v1).setColor(c);
		vertex(b, x + w, y + h).setUv(u1, v1).setColor(c);
		vertex(b, x + w, y).setUv(u1, v0).setColor(c);
		RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
		RenderSystem.setShaderTexture(0, location);
		BufferUploader.drawWithShader(b.buildOrThrow());
	}

	private static void texQuad(BufferBuilder b, float x, float y, Atlas.Region r, int color) {
		vertex(b, x, y).setUv(r.u0(), r.v0()).setColor(color);
		vertex(b, x, y + r.h()).setUv(r.u0(), r.v1()).setColor(color);
		vertex(b, x + r.w(), y + r.h()).setUv(r.u1(), r.v1()).setColor(color);
		vertex(b, x + r.w(), y).setUv(r.u1(), r.v0()).setColor(color);
	}

	private static void drawTextured(BufferBuilder b) {
		RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
		RenderSystem.setShaderTexture(0, Fonts.ATLAS.id());
		BufferUploader.drawWithShader(b.buildOrThrow());
	}
}
