package com.cheatprogram.render;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a resolution independent unit space onto framebuffer pixels (px = origin + u * scale)
 * and forwards drawing calls to {@link R2D}. The GUI and HUD lay themselves out in units.
 */
public final class Canvas {
	public float scale = 1f;
	public float originX;
	public float originY;
	private final float[] stack = new float[96];
	private int depth;

	public void set(float scale, float originX, float originY) {
		this.scale = scale;
		this.originX = originX;
		this.originY = originY;
		depth = 0;
	}

	public void push() {
		stack[depth++] = scale;
		stack[depth++] = originX;
		stack[depth++] = originY;
	}

	public void pop() {
		originY = stack[--depth];
		originX = stack[--depth];
		scale = stack[--depth];
	}

	public void translate(float du, float dv) {
		originX += du * scale;
		originY += dv * scale;
	}

	/** Scales around a point given in units. */
	public void scaleAround(float k, float cu, float cv) {
		float px = originX + cu * scale;
		float py = originY + cv * scale;
		scale *= k;
		originX = px - cu * scale;
		originY = py - cv * scale;
	}

	public float px(float u) {
		return originX + u * scale;
	}

	public float py(float v) {
		return originY + v * scale;
	}

	public float toU(float pixelX) {
		return (pixelX - originX) / scale;
	}

	public float toV(float pixelY) {
		return (pixelY - originY) / scale;
	}

	// ---------------------------------------------------------------- shapes

	public void rect(float x, float y, float w, float h, int color) {
		R2D.rect(px(x), py(y), w * scale, h * scale, color);
	}

	public void gradientH(float x, float y, float w, float h, int left, int right) {
		R2D.gradientH(px(x), py(y), w * scale, h * scale, left, right);
	}

	public void gradientV(float x, float y, float w, float h, int top, int bottom) {
		R2D.gradientV(px(x), py(y), w * scale, h * scale, top, bottom);
	}

	public void rrect(float x, float y, float w, float h, float r, int color) {
		R2D.rrect(px(x), py(y), w * scale, h * scale, r * scale, color);
	}

	public void rrect(float x, float y, float w, float h, float r, int top, int bottom) {
		R2D.rrect(px(x), py(y), w * scale, h * scale, r * scale, top, bottom);
	}

	public void rrect4(float x, float y, float w, float h, float tl, float tr, float br, float bl, int fillTop, int fillBottom, float borderPx, int borderTop, int borderBottom) {
		R2D.rrect(px(x), py(y), w * scale, h * scale, tl * scale, tr * scale, br * scale, bl * scale, fillTop, fillBottom, borderPx, borderTop, borderBottom);
	}

	public void shadow(float x, float y, float w, float h, float r, float softness, int color) {
		R2D.shadow(px(x), py(y), w * scale, h * scale, r * scale, softness * scale, color);
	}

	public void circle(float cx, float cy, float radius, int color) {
		rrect(cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
	}

	public void glass(float x, float y, float w, float h, float r, int tintTop, int tintBottom, R2D.GlassStyle style) {
		R2D.glass(px(x), py(y), w * scale, h * scale, r * scale, tintTop, tintBottom, style);
	}

	/**
	 * Clear liquid glass element: no tint, only a faint white lift and the lens at its rim.
	 * Samples the last backdrop capture.
	 */
	public void lens(float x, float y, float w, float h, float r, float lift, R2D.GlassStyle style) {
		int top = (Math.round(Math.min(1f, lift * 1.2f) * 255) << 24) | 0xFFFFFF;
		int bottom = (Math.round(Math.min(1f, lift * 0.8f) * 255) << 24) | 0xFFFFFF;
		R2D.glass(px(x), py(y), w * scale, h * scale, r * scale, top, bottom, style);
	}

	public void clip(float x, float y, float w, float h) {
		R2D.pushClip(px(x), py(y), w * scale, h * scale);
	}

	public void unclip() {
		R2D.popClip();
	}

	// ---------------------------------------------------------------- text

	public float textWidth(Fonts.Face face, String text, float size) {
		return R2D.textWidth(face, text, size * scale) / scale;
	}

	public float text(Fonts.Face face, String text, float x, float baseline, float size, int color) {
		return R2D.text(face, text, px(x), py(baseline), size * scale, color) / scale;
	}

	/** Left aligned, vertically centred on cy. Returns the width in units. */
	public float textC(Fonts.Face face, String text, float x, float cy, float size, int color) {
		return R2D.textCentered(face, text, px(x), py(cy), size * scale, color) / scale;
	}

	public void textRight(Fonts.Face face, String text, float right, float cy, float size, int color) {
		textC(face, text, right - textWidth(face, text, size), cy, size, color);
	}

	public void textMid(Fonts.Face face, String text, float cx, float cy, float size, int color) {
		textC(face, text, cx - textWidth(face, text, size) / 2f, cy, size, color);
	}

	public String ellipsize(Fonts.Face face, String text, float size, float maxWidth) {
		return Fonts.ellipsize(face, text, size * scale, maxWidth * scale);
	}

	/** Breaks text into lines no wider than maxWidth; CJK text may break anywhere, Latin text at spaces. */
	public List<String> wrap(Fonts.Face face, String text, float size, float maxWidth) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		int lastSpace = -1;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == '\n') {
				lines.add(line.toString());
				line.setLength(0);
				lastSpace = -1;
				continue;
			}
			line.appendCodePoint(cp);
			if (cp == ' ') lastSpace = line.length() - 1;
			if (textWidth(face, line.toString(), size) > maxWidth && line.length() > 1) {
				if (lastSpace > 0 && cp < 0x2E80) {
					lines.add(line.substring(0, lastSpace));
					String rest = line.substring(lastSpace + 1);
					line.setLength(0);
					line.append(rest);
				} else {
					int cut = line.length() - Character.charCount(cp);
					lines.add(line.substring(0, cut));
					String rest = line.substring(cut);
					line.setLength(0);
					line.append(rest.stripLeading());
				}
				lastSpace = -1;
			}
		}
		if (!line.isEmpty()) lines.add(line.toString());
		return lines;
	}

	// ---------------------------------------------------------------- icons

	public void icon(Icon icon, float cx, float cy, float size, int color) {
		R2D.iconCentered(icon, px(cx), py(cy), size * scale, color);
	}
}
