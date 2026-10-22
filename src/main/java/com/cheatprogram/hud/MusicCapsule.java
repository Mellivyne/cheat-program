package com.cheatprogram.hud;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.media.MusicService;
import com.cheatprogram.module.visual.Hud;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;

import java.util.Locale;
import java.util.Objects;

/** A media announcement which opens at the left, rests for two seconds and joins the island. */
public final class MusicCapsule {
	private static final float POP = 0.5f;
	private static final float HOLD = 2f;
	private static final float ABSORB = 0.85f;
	private static final float SIZE = 9f;
	private static final float ICON_SIZE = 9.5f;
	private static final float POPUP_H = 22f;
	private static float devAge = Float.NaN;
	private static DevState devState = new DevState("none", "", 0, 0, 0, 0, false, "");
	private static volatile int previewRequests;

	private MusicService.Track track;
	private MusicService.Track slotTrack;
	private MusicService.Track committedTrack;
	private long changedAt;
	private float age;
	private final Anim reserve = new Anim(0);
	private float reveal;
	private String title = "";
	private float itemWidth;
	private String animation = "music_elastic";
	private float popSeconds = POP;
	private float absorbSeconds = ABSORB;
	private int seenPreviewRequest;
	private boolean previewing;
	private MusicService.Track previewTrack;

	/** Local choreography only: this never changes the Windows session or its playback state. */
	public static void requestPreview() { previewRequests++; }

	public void update(Canvas c, float dt, float availableTitleWidth) {
		MusicService.Track realTrack = MusicService.current();
		if (seenPreviewRequest != previewRequests) {
			seenPreviewRequest = previewRequests;
			previewing = true;
			previewTrack = realTrack == null
					? new MusicService.Track("local-music-animation-preview", Lang.t("ui.music.previewSource"), Lang.t("ui.music.previewTitle"), "", true, "netease")
					: new MusicService.Track(realTrack.id(), realTrack.source(), realTrack.title(), realTrack.artist(), realTrack.playing(), realTrack.provider(),
						realTrack.position(), realTrack.duration(), realTrack.album(), realTrack.sampledNanos(), realTrack.timelineAvailable());
			track = null; // Force a fresh announcement even when the real song has not changed.
		}
		if (previewing && track != null && (System.nanoTime() - changedAt) / 1_000_000_000f >= popSeconds + HOLD + absorbSeconds) {
			previewing = false;
			previewTrack = null;
			// Restore the real dock and its detection baseline without announcing the same real song again.
			track = slotTrack = committedTrack = realTrack;
			changedAt = System.nanoTime() - (long) ((popSeconds + HOLD + absorbSeconds + 1) * 1_000_000_000L);
		}
		MusicService.Track next = previewing ? previewTrack : realTrack;
		if (!sameSong(track, next)) {
			Hud hud = ModuleManager.get(Hud.class);
			animation = hud == null ? "music_elastic" : hud.musicAnimation();
			float configuredTiming = hud == null ? 1 : hud.musicAnimationScale();
			float timing = Float.isFinite(configuredTiming) ? Math.max(0.5f, Math.min(2, configuredTiming)) : 1;
			popSeconds = POP * timing;
			absorbSeconds = ABSORB * timing;
			// Cancelling a flight must not make its uncommitted title appear in the island.
			if (next != null) slotTrack = committedTrack;
			else committedTrack = null;
			track = next;
			changedAt = System.nanoTime();
		}
		// Timeline and playing state continue to refresh without replaying the announcement.
		track = next;
		if (track == null) {
			reserve.to(0, dt, 16f);
			reveal = reserve.value;
			if (reserve.value < 0.002f) slotTrack = null;
			age = 0;
		} else {
			age = Float.isNaN(devAge) ? (System.nanoTime() - changedAt) / 1_000_000_000f : devAge;
			boolean joining = age >= popSeconds + HOLD;
			if (joining) slotTrack = track;
			if (age >= popSeconds + HOLD + absorbSeconds) committedTrack = track;
			reserve.to(slotTrack != null ? 1 : 0, dt, 18f);
			float joinProgress = (age - popSeconds - HOLD) / absorbSeconds;
			reveal = joining ? smooth(joinProgress, animation.equals("music_fade") ? 0.35f : 0.84f, 1f) : slotTrack == null ? 0 : 1;
		}
		if (slotTrack != null) {
			title = c.ellipsize(Fonts.Face.MEDIUM, slotTrack.title(), SIZE, Math.max(24, Math.min(160, availableTitleWidth)));
			itemWidth = SIZE + 4 + c.textWidth(Fonts.Face.MEDIUM, title, SIZE);
		} else {
			title = "";
			itemWidth = 0;
		}
	}

	private static boolean sameSong(MusicService.Track a, MusicService.Track b) {
		return a == b || a != null && b != null && Objects.equals(a.id(), b.id())
				&& Objects.equals(a.source(), b.source()) && Objects.equals(a.title(), b.title());
	}

	public boolean active() {
		return track != null || slotTrack != null;
	}

	/** Includes the separator separating music from the original idle information. */
	public float idleWidth() {
		return slotTrack == null ? 0 : (itemWidth + 13) * reserve.value;
	}

	public float rowWidth() {
		return slotTrack == null ? 0 : (itemWidth + 18) * reserve.value;
	}

	public float rowHeight() {
		return 22 * reserve.value;
	}

	public boolean absorbing() {
		return track != null && age >= popSeconds + HOLD && age < popSeconds + HOLD + absorbSeconds;
	}

	public void drawSlot(Canvas c, float x, float cy, boolean separator) {
		if (slotTrack == null || reveal < 0.002f || reserve.value < 0.002f) return;
		R2D.pushAlpha(reveal * reserve.value);
		try {
			c.clip(x - 1, cy - 10, Math.max(1, (itemWidth + (separator ? 13 : 0)) * reserve.value), 20);
			try {
				c.icon(icon(slotTrack), x + SIZE / 2, cy, ICON_SIZE, Theme.TEXT_2);
				float tx = x + SIZE + 4;
				c.textC(Fonts.Face.MEDIUM, title, tx, cy, SIZE, Theme.TEXT);
				if (separator) c.circle(x + itemWidth + 6.5f, cy, 1.2f, Anim.white(0.45f));
			} finally { c.unclip(); }
		} finally { R2D.popAlpha(); }
	}

	public void drawAnnouncement(HudContext ctx, float islandX, float islandY, float islandW, float islandH, float slotX, float slotCy) {
		if (track == null || age >= popSeconds + HOLD + absorbSeconds) {
			publish(track == null ? "none" : "docked", 0, 0, 0, 0);
			return;
		}
		Canvas c = ctx.c;
		String source = c.ellipsize(Fonts.Face.MEDIUM, track.source().isBlank() ? Lang.t("ui.music.player") : track.source(), SIZE, 84);
		float sourceW = c.textWidth(Fonts.Face.MEDIUM, source, SIZE);
		float popupLimit = Math.min(330, ctx.width - 20);
		String song = c.ellipsize(Fonts.Face.MEDIUM, track.title(), SIZE, Math.max(38, popupLimit - sourceW - 49));
		float songW = c.textWidth(Fonts.Face.MEDIUM, song, SIZE);
		float popupW = 43 + sourceW + songW;
		float baseX = 8;
		float baseY = islandY;
		float x = baseX, y = baseY, w = popupW, h = POPUP_H;
		float alpha = 1;
		float sourceAlpha = 1;
		float textAlpha = 1;
		float splitGap = 0, splitRise = 0, waveReveal = 1;
		float iconX = Float.NaN, iconY = Float.NaN;
		float trail = -1;
		boolean wave = false, split = false, flipEdge = false;
		float fly = 0;
		float morph = 0;
		String phase = "hold";
		if (age < popSeconds) {
			phase = "pop";
			float t = Anim.clamp01(age / popSeconds);
			float ease = Anim.easeOutCubic(t);
			alpha = smooth(t, 0, 0.28f);
			switch (animation) {
				case "music_slide" -> x -= (1 - ease) * (popupW + baseX + 10);
				case "music_fade" -> {
					alpha = smooth(t, 0, 1);
				}
				case "music_droplet" -> {
					float spring = Anim.easeOutBack(t);
					w = Anim.lerp(POPUP_H, popupW, spring);
					h *= 1.35f - 0.35f * spring;
					y -= (h - POPUP_H) * 0.5f;
					textAlpha = smooth(t, 0.3f, 0.85f);
				}
				case "music_split" -> {
					split = true;
					splitGap = (1 - Anim.easeOutBack(t)) * 70;
					splitRise = (1 - ease) * 17;
					textAlpha = smooth(t, 0.4f, 0.9f);
				}
				case "music_flip" -> {
					float face = (float) Math.cos((1 - ease) * Math.PI * 0.5);
					w = Math.max(2, popupW * face);
					h *= 1 + 0.22f * (1 - face);
					x += (popupW - w) * 0.5f;
					y -= (h - POPUP_H) * 0.5f;
					textAlpha = smooth(face, 0.45f, 0.92f);
					flipEdge = face < 0.15f;
				}
				case "music_wave" -> {
					wave = true;
					waveReveal = ease;
					textAlpha = smooth(t, 0.12f, 0.8f);
				}
				case "music_orbit" -> {
					w = Anim.lerp(POPUP_H, popupW, ease);
					float radius = 28 * (1 - ease);
					float angle = ease * (float) Math.PI * 4;
					iconX = baseX + 13 + (float) Math.sin(angle) * radius;
					iconY = baseY + POPUP_H * 0.5f - (float) Math.cos(angle) * radius;
					textAlpha = smooth(t, 0.48f, 0.95f);
					trail = t;
				}
				default -> {
					float spring = Anim.easeOutBack(t);
					w *= 0.2f + spring * 0.8f;
					h *= 0.28f + spring * 0.72f;
					x += (popupW - w) * 0.5f;
					y -= (1 - spring) * 28;
					textAlpha = smooth(t, 0.25f, 0.8f);
				}
			}
		} else if (age >= popSeconds + HOLD) {
			phase = "absorb";
			fly = Anim.clamp01((age - popSeconds - HOLD) / absorbSeconds);
			boolean fluid = animation.equals("music_elastic") || animation.equals("music_droplet");
			float travel = fluid ? Anim.clamp01((fly - 0.12f) / 0.88f) : fly;
			float movement = easeInOut(travel);
			morph = movement;
			float anticipation = fluid ? (float) Math.sin(Math.PI * Math.min(1, fly / 0.12f)) : 0;
			float stretch = (float) Math.sin(Math.PI * travel);
			float dockW = Math.max(25, itemWidth + 16);
			w = Anim.lerp(popupW, dockW, movement);
			h = POPUP_H;
			float cx = Anim.lerp(baseX + popupW / 2, slotX + dockW / 2 - 8, movement) - anticipation * 4;
			float cy = Anim.lerp(baseY + POPUP_H / 2, slotCy, movement);
			switch (animation) {
				case "music_droplet" -> {
					w = Anim.lerp(popupW, POPUP_H, smooth(fly, 0, 0.48f));
					w *= 1 + 0.85f * stretch;
					h *= 1 - 0.4f * stretch;
					cy -= 24 * stretch;
					float targetIconX = Anim.lerp(baseX + 13, slotX + SIZE / 2, movement);
					cx = targetIconX + (popupW * 0.5f - 13) * (1 - smooth(fly, 0, 0.48f));
					iconX = targetIconX;
					iconY = cy;
					textAlpha = 1 - smooth(fly, 0.08f, 0.38f);
				}
				case "music_fade" -> {
					// A true dissolve stays at its source; only the destination materialises.
					w = popupW;
					cx = baseX + popupW * 0.5f;
					cy = baseY + POPUP_H * 0.5f;
					alpha = 1 - smooth(fly, 0, 0.72f);
				}
				case "music_split" -> {
					split = true;
					splitGap = 32 * stretch;
					splitRise = 16 * stretch;
					textAlpha = 1 - smooth(fly, 0.08f, 0.42f);
				}
				case "music_flip" -> {
					float face = Math.abs((float) Math.cos(Math.PI * 2 * movement));
					w = Math.max(2, w * face);
					h *= 1 + 0.18f * (1 - face);
					cy -= 20 * stretch;
					textAlpha = smooth(face, 0.45f, 0.92f);
					flipEdge = face < 0.15f;
				}
				case "music_wave" -> {
					wave = true;
					waveReveal = 1 - smooth(fly, 0.42f, 1);
					cy += (float) Math.sin(Math.PI * 2 * movement) * stretch * 20;
					w *= 1 + 0.12f * (float) Math.sin(Math.PI * 4 * movement) * stretch;
					textAlpha = waveReveal;
				}
				case "music_orbit" -> {
					float[] orbit = orbitPoint(baseX + 13, baseY + POPUP_H * 0.5f, slotX + SIZE / 2, slotCy, movement);
					iconX = orbit[0];
					cx = iconX + (popupW * 0.5f - 13) * (1 - smooth(fly, 0, 0.3f));
					cy = iconY = orbit[1];
					w = Anim.lerp(popupW, POPUP_H, smooth(fly, 0, 0.3f));
					h = POPUP_H * (1 - 0.28f * stretch);
					textAlpha = 1 - smooth(fly, 0, 0.25f);
					trail = movement;
				}
				case "music_slide" -> { }
				default -> {
					w *= 1 + 0.26f * stretch + 0.07f * anticipation;
					h *= 1 - 0.3f * stretch - 0.12f * anticipation;
					cy += 14 * stretch + 4 * anticipation;
				}
			}
			x = cx - w / 2;
			y = cy - h / 2;
			sourceAlpha = 1 - smooth(fly, 0.12f, 0.48f);
			if (!animation.equals("music_fade")) alpha = 1 - smooth(fly, 0.84f, 1);
		}
		R2D.pushAlpha(alpha);
		try {
			if (split && (Math.abs(splitGap) > 0.6f || Math.abs(splitRise) > 0.6f)) {
				float half = w * 0.5f;
				ctx.panel(x - splitGap * 0.5f, y - splitRise, half, h, Math.min(h * 0.5f, half * 0.5f));
				ctx.panel(x + half + splitGap * 0.5f, y + splitRise, half, h, Math.min(h * 0.5f, half * 0.5f));
			} else if (wave) drawWaveSurface(ctx, x, y, w, h, waveReveal, age);
			else ctx.panel(x, y, w, h, Math.min(h * 0.5f, w * 0.5f));
			if (flipEdge) c.rrect(x + w * 0.5f - 0.6f, y + 1, 1.2f, h - 2, 0.6f, Anim.white(0.8f));
			if (trail >= 0) drawOrbitTrail(c, baseX, baseY, slotX, slotCy, trail, phase.equals("absorb"));
			float ix = Float.isNaN(iconX) ? x + Anim.lerp(13, 12.5f, morph) : iconX;
			float cy = y + h * 0.5f;
			// Moving orbit/drop icons stay outside the label clip; text is a single sharp overlay.
			if (!Float.isNaN(iconX)) c.icon(icon(track), ix, iconY, ICON_SIZE, Theme.TEXT_2);
			c.clip(x + 4, y, Math.max(1, w - 8) * (wave ? waveReveal : 1), h);
			try {
				R2D.pushAlpha(textAlpha);
				try {
					if (Float.isNaN(iconX)) c.icon(icon(track), ix, cy, ICON_SIZE, Theme.TEXT_2);
					float tx = x + Anim.lerp(23, 21, morph);
					if (sourceAlpha > 0.002f) {
						R2D.pushAlpha(sourceAlpha);
						try {
							c.textC(Fonts.Face.MEDIUM, source, tx, cy, SIZE, Theme.TEXT_2);
							c.circle(tx + sourceW + 6, cy, 1.1f, Anim.white(0.4f));
						} finally { R2D.popAlpha(); }
					}
					float songX = tx + (sourceW + 12) * sourceAlpha;
					String movingSong = c.ellipsize(Fonts.Face.MEDIUM, track.title(), SIZE, Math.max(1, x + w - 8 - songX));
					c.textC(Fonts.Face.MEDIUM, movingSong, songX, cy, SIZE, Theme.TEXT);
				} finally { R2D.popAlpha(); }
			} finally { c.unclip(); }
		} finally { R2D.popAlpha(); }
		publish(phase, x, y, w, h);
	}

	/** One glass card revealed by eight independently curved slices, rather than eight stacked labels. */
	private static void drawWaveSurface(HudContext ctx, float x, float y, float w, float h, float progress, float seconds) {
		if (progress >= 0.999f) { ctx.panel(x, y, w, h, h * 0.5f); return; }
		if (progress <= 0) return;
		Canvas c = ctx.c;
		float ripple = (float) Math.sin(Math.PI * progress);
		for (int row = 0; row < 8; row++) {
			float front = Anim.clamp01(progress + 0.075f * ripple * (float) Math.sin(row * 0.9f - seconds * 8));
			if (front <= 0) continue;
			float top = y + row * h / 8;
			c.clip(x - 1, top, w * front + 1, h / 8 + 0.1f);
			try { ctx.panel(x, y, w, h, h * 0.5f); }
			finally { c.unclip(); }
			c.circle(x + w * front, top + h / 16, 0.55f, Anim.white(0.38f * ripple));
		}
	}

	private static float[] orbitPoint(float sx, float sy, float dx, float dy, float progress) {
		float t = Anim.clamp01(progress), arch = (float) Math.sin(Math.PI * t);
		return new float[]{Anim.lerp(sx, dx, t) + (float) Math.sin(Math.PI * 2 * t) * arch * 15,
				Anim.lerp(sy, dy, t) + arch * 32};
	}

	private static void drawOrbitTrail(Canvas c, float baseX, float baseY, float slotX, float slotCy, float progress, boolean absorbing) {
		for (int dot = 7; dot >= 1; dot--) {
			float before = progress - dot * 0.026f;
			if (before < 0) continue;
			float x, y;
			if (absorbing) {
				float[] point = orbitPoint(baseX + 13, baseY + POPUP_H * 0.5f, slotX + SIZE / 2, slotCy, before);
				x = point[0]; y = point[1];
			} else {
				float eased = Anim.easeOutCubic(before), radius = 28 * (1 - eased), angle = eased * (float) Math.PI * 4;
				x = baseX + 13 + (float) Math.sin(angle) * radius;
				y = baseY + POPUP_H * 0.5f - (float) Math.cos(angle) * radius;
			}
			c.circle(x, y, 0.7f + (8 - dot) * 0.11f, Anim.white((8 - dot) * 0.032f));
		}
	}

	private static float smooth(float value, float start, float end) {
		float t = Anim.clamp01((value - start) / (end - start));
		return t * t * (3 - 2 * t);
	}

	private static float easeInOut(float value) {
		return value < 0.5f ? 4 * value * value * value
				: 1 - (float) Math.pow(-2 * value + 2, 3) / 2;
	}

	private static Icon icon(MusicService.Track track) {
		String provider = (track.provider() + " " + track.source()).toLowerCase(Locale.ROOT);
		return provider.contains("cloudmusic") || provider.contains("netease") || provider.contains("网易") ? Icon.NETEASE : Icon.MUSIC;
	}

	private void publish(String phase, float x, float y, float w, float h) {
		if (!System.getProperty("cheatprogram.dev", "").isEmpty())
			devState = new DevState(phase, track == null ? "" : track.title(), x, y, w, h, "docked".equals(phase), slotTrack == null ? "" : slotTrack.title());
	}

	public record DevState(String phase, String title, float x, float y, float w, float h, boolean docked, String slotTitle) { }

	public static DevState devState() {
		return devState;
	}

	/** Freeze only the music choreography for deterministic development captures. */
	public static void devElapsed(float seconds) {
		if (!System.getProperty("cheatprogram.dev", "").isEmpty()) devAge = seconds;
	}
}
