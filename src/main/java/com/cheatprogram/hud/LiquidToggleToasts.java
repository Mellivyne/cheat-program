package com.cheatprogram.hud;

import com.cheatprogram.core.Notifications;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.MotionCurve;
import com.cheatprogram.util.Spring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Video-style bottom-right toggle stack. Notification text is drawn only after every glass surface. */
public final class LiquidToggleToasts {
	private static final float ROW_HEIGHT = 21, GAP = 4, MARGIN = 12, NAME_SIZE = 9, DETAIL_SIZE = 8.5f;
	private static final Map<Notifications.Entry, Spring> POSITIONS = new IdentityHashMap<>();
	private record Draw(Notifications.Entry entry, float x, float y, float width, float visibility, float progress, float seconds, String title, String detail) { }

	private LiquidToggleToasts() { }

	/** Caller routes MODULE notifications here and keeps INFO/music/island rendering in their existing paths. */
	public static void render(HudContext hud, float dt, List<Notifications.Entry> entries) {
		render(hud, dt, entries, 1f);
	}

	/** Scales this stack's injection entrance without extending the shared notification lifetime. */
	public static void render(HudContext hud, float dt, List<Notifications.Entry> entries, float timeScale) {
		if (entries == null || entries.isEmpty()) { clear(); return; }
		float timing = Float.isFinite(timeScale) ? Math.max(.5f, Math.min(2.5f, timeScale)) : 1f;
		float entranceMs = 280f * timing;
		long now = System.currentTimeMillis();
		ArrayList<Notifications.Entry> live = new ArrayList<>();
		for (Notifications.Entry entry : entries) {
			if (entry.kind == Notifications.Kind.MODULE && now - entry.created < Notifications.DURATION_MS + Notifications.FADE_MS) live.add(entry);
		}
		live.sort(Comparator.comparingLong(entry -> entry.created));
		int count = Math.min(Notifications.MAX_MODULE_ENTRIES, Math.max(0, (int) ((hud.height - MARGIN * 2 + GAP) / (ROW_HEIGHT + GAP))));
		while (live.size() > count) live.removeFirst();
		POSITIONS.keySet().removeIf(entry -> !live.contains(entry));
		if (live.isEmpty()) return;
		Canvas c = hud.c;
		float step = Float.isFinite(dt) ? Math.max(0, Math.min(.1f, dt)) : 0;
		ArrayList<Draw> draws = new ArrayList<>();
		for (int index = 0; index < live.size(); index++) {
			Notifications.Entry entry = live.get(index);
			long age = Math.max(0, now - entry.created);
			float exit = age <= Notifications.DURATION_MS ? 0 : Math.min(1, (age - Notifications.DURATION_MS) / (float) Notifications.FADE_MS);
			float visibility = Math.min(Anim.easeOutCubic(age / entranceMs), 1 - MotionCurve.smootherStep(exit));
			String detail = singleLine(entry.detail), title = singleLine(entry.title);
			float detailWidth = c.textWidth(Fonts.Face.REGULAR, detail, DETAIL_SIZE);
			float natural = c.textWidth(Fonts.Face.MEDIUM, title, NAME_SIZE) + detailWidth + 25;
			float width = Math.max(1, Math.min(hud.width - MARGIN * 2, Math.max(112, Math.min(218, natural))));
			detail = c.ellipsize(Fonts.Face.REGULAR, detail, DETAIL_SIZE, Math.max(0, width * .42f));
			detailWidth = c.textWidth(Fonts.Face.REGULAR, detail, DETAIL_SIZE);
			title = c.ellipsize(Fonts.Face.MEDIUM, title, NAME_SIZE, Math.max(0, width - detailWidth - 25));
			int rank = live.size() - 1 - index; // Newest is at the bottom, older entries move upward.
			float targetY = hud.height - MARGIN - ROW_HEIGHT - rank * (ROW_HEIGHT + GAP);
			Spring y = POSITIONS.computeIfAbsent(entry, ignored -> new Spring(targetY + 4));
			y.target = targetY;
			y.update(step, 290, 25);
			float enter = Anim.easeOutBack(Math.min(1, age / entranceMs));
			float x = hud.width - MARGIN - width + (1 - enter + exit * exit) * (width + MARGIN + 3);
			// Time-warp the fill instead of lengthening its clock: even the slowest setting finishes before fade-out.
			float lifetime = Anim.clamp01(age / (float) Notifications.DURATION_MS);
			float fill = MotionCurve.smootherStep((float) Math.pow(lifetime, timing));
			draws.add(new Draw(entry, x, y.value, width, visibility, fill, age / (1000f * timing), title, detail));
		}
		// Capture once before any of this stack's surfaces or labels exist in the framebuffer.
		Backdrop.capture(Theme.frost());
		R2D.applyState();
		c.clip(0, 0, hud.width, hud.height);
		try {
			for (Draw draw : draws) {
				R2D.pushAlpha(draw.visibility());
				try {
					c.shadow(draw.x(), draw.y() + 1, draw.width(), ROW_HEIGHT, 3.5f, 7, Anim.black(.15f));
					R2D.liquidToggle(c.px(draw.x()), c.py(draw.y()), draw.width() * c.scale, ROW_HEIGHT * c.scale, 3.5f * c.scale,
							draw.progress(), draw.seconds(), Theme.lift(.085f), Theme.chipGlass(c.scale));
				} finally { R2D.popAlpha(); }
			}
			// No backdrop capture is allowed after this point: the text remains sharp and is never refracted twice.
			for (Draw draw : draws) {
				R2D.pushAlpha(draw.visibility());
				try {
					float cy = draw.y() + ROW_HEIGHT * .5f;
					float nameWidth = c.textC(Fonts.Face.MEDIUM, draw.title(), draw.x() + 10, cy, NAME_SIZE, Theme.TEXT);
					c.textC(Fonts.Face.REGULAR, draw.detail(), draw.x() + 14 + nameWidth, cy, DETAIL_SIZE, Theme.TEXT_2);
				} finally { R2D.popAlpha(); }
			}
		} finally { c.unclip(); }
	}

	public static void clear() { POSITIONS.clear(); }
	public static void reset() { clear(); }

	private static String singleLine(String text) {
		String value = text == null ? "" : text.replaceAll("[\\x00-\\x1f\\x7f]", " ").strip();
		return value.length() > 192 ? value.substring(0, 192) : value;
	}
}
