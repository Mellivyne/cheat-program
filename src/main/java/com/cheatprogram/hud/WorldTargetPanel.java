package com.cheatprogram.hud;

import com.cheatprogram.core.Lang;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.HudWorldPanel;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Projects one shared window layout; all backgrounds sample one capture before labels and skins. */
public final class WorldTargetPanel {
	public record Info(String name, String distance, float health, float maxHealth, boolean player, ResourceLocation skin, Integer food) { }
	public record Plate(HudWorldPanel.Projection projection, Info info) { }
	private record Window(Canvas canvas, Info info, float x, float y, float right, float bottom) { }
	private WorldTargetPanel() { }

	public static void draw(List<Plate> plates, float width, float height, float offsetX, float offsetY) {
		List<Window> windows = new ArrayList<>();
		for (Plate plate : plates) {
			var projection = plate.projection;
			if (System.nanoTime() - projection.time() > 250_000_000L) continue;
			float scale = projection.scale(), x = projection.x() + offsetX * scale - width * scale / 2,
					y = projection.y() + offsetY * scale - height * scale;
			float right = x + width * scale, bottom = y + height * scale;
			if (x < 0 || y < 0 || right > R2D.width() || bottom > R2D.height()) continue;
			boolean overlaps = false;
			for (Window window : windows) if (x < window.right + 4 && right > window.x - 4 && y < window.bottom + 4 && bottom > window.y - 4) { overlaps = true; break; }
			if (overlaps) continue; // The nearest accepted plate wins; never stack unreadable target windows.
			Canvas canvas = new Canvas(); canvas.set(scale, x, y);
			windows.add(new Window(canvas, plate.info, x, y, right, bottom));
		}
		if (windows.isEmpty()) return;
		Backdrop.capture(Theme.frost()); R2D.applyState();
		for (Window window : windows) {
			Canvas canvas = window.canvas;
			canvas.shadow(0, 2, width, height, 10, 8, Anim.black(0.16f));
			canvas.lens(0, 0, width, height, 10, Theme.lift(0.07f), Theme.chipGlass(canvas.scale));
		}
		for (Window window : windows) contents(window.canvas, 0, 0, width, height, window.info);
	}

	public static void contents(Canvas canvas, float x, float y, float width, float height, Info info) {
		float textX = x + 10;
		if (info.player && info.skin != null) {
			float avatar = Math.min(32, height - 18), ax = x + 9, ay = y + 9;
			canvas.rrect(ax - 1, ay - 1, avatar + 2, avatar + 2, 7, Anim.white(0.14f));
			R2D.texture(info.skin, canvas.px(ax), canvas.py(ay), avatar * canvas.scale, avatar * canvas.scale,
					8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f, 0xFFFFFFFF);
			R2D.texture(info.skin, canvas.px(ax), canvas.py(ay), avatar * canvas.scale, avatar * canvas.scale,
					40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f, 0xFFFFFFFF);
			textX = ax + avatar + 9;
		}
		float right = x + width - 10, textWidth = right - textX;
		canvas.textC(Fonts.Face.MEDIUM, canvas.ellipsize(Fonts.Face.MEDIUM, info.name, 10, Math.max(10, textWidth - 31)), textX, y + 12, 10, Theme.TEXT);
		canvas.textRight(Fonts.Face.REGULAR, info.distance, right, y + 12, 8, Theme.TEXT_3);
		float hpY = info.player ? Math.max(22, height * 0.40f) : Math.max(25, height * 0.58f);
		canvas.textC(Fonts.Face.REGULAR, String.format(Locale.ROOT, "%.1f / %.1f", info.health, info.maxHealth), textX, y + hpY, 8.5f, Theme.TEXT_2);
		float hpLine = info.player ? hpY + 5 : height - 9;
		float fraction = info.maxHealth > 0 ? Math.max(0, Math.min(1, info.health / info.maxHealth)) : 0;
		canvas.rrect(textX, y + hpLine, textWidth, 1.4f, 0.7f, Anim.white(0.11f));
		if (fraction > 0) canvas.rrect(textX, y + hpLine, textWidth * fraction, 1.4f, 0.7f, Anim.white(0.96f));
		if (info.player) {
			float foodY = Math.max(hpLine + 7, height - 13), foodLine = Math.max(foodY + 5, height - 7);
			String food = info.food == null ? Lang.t("targethud.food_unknown") : Lang.t("targethud.food_value", info.food);
			canvas.textC(Fonts.Face.REGULAR, canvas.ellipsize(Fonts.Face.REGULAR, food, 8, textWidth), textX, y + foodY, 8, Theme.TEXT_3);
			if (info.food != null) {
				canvas.rrect(textX, y + foodLine, textWidth, 1.3f, 0.65f, Anim.black(0.10f));
				float foodFraction = Math.max(0, Math.min(1, info.food / 20f));
				if (foodFraction > 0) canvas.rrect(textX, y + foodLine, textWidth * foodFraction, 1.3f, 0.65f, Anim.black(0.92f));
			}
		}
	}
}
