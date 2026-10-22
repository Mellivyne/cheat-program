package com.cheatprogram.gui;

import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;

/**
 * Monochrome palette and liquid glass parameters derived from the appearance settings.
 * The main window is dark liquid glass; everything else is clear liquid glass with white text.
 */
public final class Theme {
	public static final int TEXT = 0xF8FFFFFF;
	public static final int TEXT_2 = 0xC8FFFFFF;
	public static final int TEXT_3 = 0x99FFFFFF;
	public static final int TEXT_4 = 0x66FFFFFF;
	public static final int ACCENT = 0xFFFFFFFF;

	private Theme() {
	}

	private static ClientSettings s() {
		return ClientSettings.INSTANCE;
	}

	/** 0 = solid, 1 = crystal clear. */
	public static float transparency() {
		return (float) s().transparency.fraction();
	}

	public static float backgroundBlur() {
		return (float) s().blur.fraction();
	}

	public static float frost() {
		return (float) s().frost.fraction();
	}

	private static float refraction() {
		return (float) s().refraction.fraction();
	}

	/** Dark tint of the main window (the only tinted glass). */
	public static float windowTint() {
		return Anim.lerp(0.70f, 0.16f, transparency());
	}

	/**
	 * Brightness lift of a clear glass element, scaled by the highlight setting.
	 * Clear glass has no tint at all; the lift is what makes its body faintly visible.
	 */
	public static float lift(float base) {
		return base * (0.55f + 0.9f * (float) s().highlight.fraction());
	}

	/** Blur for glass that floats over other interface content (drawer, menus, tooltips). */
	public static float overlayFrost() {
		return Math.max(frost(), 0.35f);
	}

	private static float haze() {
		return 0.04f * frost();
	}

	/** The main window; {@code px} is the pixel size of one unit. */
	public static R2D.GlassStyle windowGlass(float px) {
		float r = refraction();
		return new R2D.GlassStyle((6f + 34f * r) * px, (16f + 16f * r) * px, 0f, 8f * px, haze(), 1f);
	}

	/** Clear glass for elements inside the window: rows, pills, fields, buttons, panels. */
	public static R2D.GlassStyle lensGlass(float px) {
		float r = refraction();
		return new R2D.GlassStyle((2f + 7f * r) * px, (5f + 6f * r) * px, 0f, 1.2f * px, 0f, 1f);
	}

	/** Clear glass for floating pieces (HUD, popovers, the drawer). */
	public static R2D.GlassStyle chipGlass(float px) {
		float r = refraction();
		return new R2D.GlassStyle((3f + 10f * r) * px, (6f + 7f * r) * px, 0f, 2.5f * px, haze(), 1f);
	}

	public static R2D.GlassStyle sheetGlass(float px) {
		float r = refraction();
		return new R2D.GlassStyle((5f + 26f * r) * px, (12f + 14f * r) * px, 0f, 6f * px, haze(), 1f);
	}
}
