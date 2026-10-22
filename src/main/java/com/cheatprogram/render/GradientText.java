package com.cheatprogram.render;

/** Five weighted glyph samples soften text spatially without a framebuffer capture per row. */
public final class GradientText {
	private GradientText() {
	}

	/** Returns the ordinary text advance; the blur never changes layout or font size. */
	public static float draw(Canvas c, Fonts.Face face, String text, float x, float cy, float size,
							 int color, float brightness, float blur) {
		float light = Math.max(0, Math.min(1, brightness));
		int dimmed = (color & 0xFF000000)
				| (Math.round(((color >> 16) & 255) * light) << 16)
				| (Math.round(((color >> 8) & 255) * light) << 8)
				| Math.round((color & 255) * light);
		// Fade in the side samples as pixel-sized offsets become visible, keeping the clear edge smooth.
		float mix = Math.max(0, Math.min(1, (blur * c.scale - 0.35f) / 0.85f));
		mix = mix * mix * (3 - 2 * mix);
		if (blur <= 0 || mix == 0) return c.textC(face, text, x, cy, size, dimmed);
		float radius = Math.min(2.5f, blur);
		int outer = weight(dimmed, 0.15f * mix);
		if ((outer >>> 24) != 0) {
			c.textC(face, text, x - radius, cy, size, outer);
			c.textC(face, text, x + radius, cy, size, outer);
			c.textC(face, text, x, cy - radius, size, outer);
			c.textC(face, text, x, cy + radius, size, outer);
		}
		return c.textC(face, text, x, cy, size, weight(dimmed, 1 - 0.6f * mix));
	}

	private static int weight(int color, float value) {
		return (Math.round((color >>> 24) * value) << 24) | (color & 0xFFFFFF);
	}
}
