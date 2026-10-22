package com.cheatprogram.hud;

import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.GradientText;
import com.cheatprogram.render.R2D;
import com.cheatprogram.render.PaletteText;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.GradientSweep;
import com.cheatprogram.util.Spring;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Independent list canvas keeps its size and animation from changing other HUD elements. */
public final class ModuleArrayList {
	public record Style(boolean right, float scale, boolean rail, boolean gradient, float phase,
			float band, float darkness, float blur, PaletteText.Style palette) { }
	private static final float FONT_SIZE = 9.5f, ROW_HEIGHT = 15.5f, GAP = 2, RAIL_WIDTH = 1.5f, RAIL_GAP = 3;
	private final Spring railHeight = new Spring(0);
	private final Map<Module, RowMotion> rowMotions = new IdentityHashMap<>();
	private static final class RowMotion {
		float x, y, width;
		RowMotion(float x, float y, float width) { this.x = x; this.y = y; this.width = width; }
		void approach(float x, float y, float width, float amount) {
			this.x += (x - this.x) * amount; this.y += (y - this.y) * amount; this.width += (width - this.width) * amount;
		}
	}

	public void render(HudContext base, float dt, Module owner, Style style) {
		Canvas canvas = new Canvas();
		canvas.set(base.c.scale * style.scale, 0, 0);
		List<Module> shown = new ArrayList<>();
		for (Module module : ModuleManager.all()) {
			if (module == owner || module.isOneShot()) continue;
			float target = module.isEnabled() && module.isDrawn() ? 1 : 0;
			float ease = 1 - (float) Math.exp(-dt * 12 * Anim.globalSpeed());
			module.listAnim += (target - module.listAnim) * ease;
			if (Math.abs(target - module.listAnim) < 0.002f) module.listAnim = target;
			if (module.listAnim > 0.005f) shown.add(module);
			else rowMotions.remove(module);
		}
		shown.sort((a, b) -> Float.compare(rowWidth(canvas, b), rowWidth(canvas, a)));
		float width = 60, totalH = 0;
		for (Module module : shown) {
			width = Math.max(width, rowWidth(canvas, module));
			totalH += ROW_HEIGHT * Anim.easeOutCubic(module.listAnim) + GAP;
		}
		// A fixed inter-row gap means subtracting precisely one trailing gap gives the last visible row bottom.
		float visibleHeight = Math.max(0, totalH - GAP);
		if (base.editing && shown.isEmpty()) visibleHeight = ROW_HEIGHT;
		railHeight.target = visibleHeight;
		float lineHeight = Math.max(0, railHeight.update(dt, 190, 17));
		if (shown.isEmpty() && !base.editing && (!style.rail || lineHeight < 0.02f)) return;
		float railSpace = style.rail ? RAIL_GAP + RAIL_WIDTH : 0;
		float boundsWidth = (width + railSpace) * style.scale;
		float boundsHeight = Math.max(visibleHeight, style.rail ? lineHeight : 0) * style.scale;
		float x = HudLayout.arraylistX(boundsWidth, base.width, style.right);
		float y = HudLayout.y("arraylist", Math.max(1, boundsHeight), base.height);
		canvas.set(base.c.scale * style.scale, base.c.px(x), base.c.py(y));
		HudContext local = new HudContext(canvas, base.width / style.scale, base.height / style.scale, base.partialTick, base.editing);
		float rowsX = style.right ? 0 : railSpace;
		float rowY = 0;
		float moveEase = 1 - (float) Math.exp(-Math.max(0, dt) * 20 * Anim.globalSpeed());
		for (Module module : shown) {
			float alpha = Anim.easeOutCubic(module.listAnim);
			float rowH = ROW_HEIGHT * alpha, rowW = rowWidth(canvas, module);
			float rowX = style.right ? rowsX + width - rowW + (1 - alpha) * (rowW + 10) : rowsX - (1 - alpha) * (rowW + 10);
			RowMotion motion = rowMotions.get(module);
			if (motion == null) { motion = new RowMotion(rowX, rowY, rowW); rowMotions.put(module, motion); }
			else motion.approach(rowX, rowY, rowW, moveEase);
			float drawX = motion.x, drawY = motion.y, drawW = motion.width;
			R2D.pushAlpha(alpha);
			try {
				local.panel(drawX, drawY, drawW, rowH, rowH / 2);
				canvas.clip(drawX, drawY, drawW, rowH);
				try { drawText(canvas, module, drawX + 8, drawY + rowH / 2, visibleHeight, style); }
				finally { canvas.unclip(); }
			} finally { R2D.popAlpha(); }
			rowY += rowH + GAP;
		}
		if (style.rail && lineHeight > 0.01f) {
			float railX = style.right ? width + RAIL_GAP : 0;
			float alpha = shown.isEmpty() && !base.editing ? Anim.clamp01(lineHeight / ROW_HEIGHT) : 1;
			canvas.shadow(railX - 0.5f, 0, RAIL_WIDTH + 1, lineHeight, RAIL_WIDTH / 2, 2, Anim.white(0.09f * alpha));
			canvas.rrect(railX, 0, RAIL_WIDTH, lineHeight, RAIL_WIDTH / 2, Anim.white(0.72f * alpha));
		}
		HudLayout.report("arraylist", x, y, boundsWidth, Math.max(1, boundsHeight));
	}

	private static float rowWidth(Canvas canvas, Module module) {
		float width = canvas.textWidth(Fonts.Face.MEDIUM, module.name(), FONT_SIZE);
		String suffix = module.suffix();
		if (!suffix.isEmpty()) width += 4 + canvas.textWidth(Fonts.Face.REGULAR, suffix, FONT_SIZE);
		return width + 16;
	}
	private static void drawText(Canvas canvas, Module module, float x, float centerY, float height, Style style) {
		String suffix = module.suffix();
		if (style.palette.colored()) {
			float brightness = 1, blur = 0;
			if (style.gradient) {
				float clarity = PaletteText.flowingClarity(1 - centerY / Math.max(ROW_HEIGHT, height), style.phase, style.band);
				brightness = GradientSweep.brightness(clarity, style.darkness);
				blur = GradientSweep.blur(clarity, style.blur);
			}
			float start = x;
			float span = canvas.textWidth(Fonts.Face.MEDIUM, module.name(), FONT_SIZE);
			if (!suffix.isEmpty()) span += 4 + canvas.textWidth(Fonts.Face.REGULAR, suffix, FONT_SIZE);
			x += PaletteText.drawFlowing(canvas, Fonts.Face.MEDIUM, module.name(), x, centerY, FONT_SIZE, Theme.TEXT,
					style.palette, start, span, brightness, blur);
			if (!suffix.isEmpty()) PaletteText.drawFlowing(canvas, Fonts.Face.REGULAR, suffix, x + 4, centerY, FONT_SIZE, Theme.TEXT_3,
					style.palette, start, span, brightness, blur);
		} else if (style.gradient) {
			float up = 1 - centerY / Math.max(ROW_HEIGHT, height);
			float clarity = PaletteText.flowingClarity(up, style.phase, style.band);
			float brightness = GradientSweep.brightness(clarity, style.darkness);
			float blur = GradientSweep.blur(clarity, style.blur);
			x += GradientText.draw(canvas, Fonts.Face.MEDIUM, module.name(), x, centerY, FONT_SIZE, Theme.TEXT, brightness, blur);
			if (!suffix.isEmpty()) GradientText.draw(canvas, Fonts.Face.REGULAR, suffix, x + 4, centerY, FONT_SIZE, Theme.TEXT_3, brightness, blur);
		} else {
			x += canvas.textC(Fonts.Face.MEDIUM, module.name(), x, centerY, FONT_SIZE, Theme.TEXT);
			if (!suffix.isEmpty()) canvas.textC(Fonts.Face.REGULAR, suffix, x + 4, centerY, FONT_SIZE, Theme.TEXT_3);
		}
	}
}
