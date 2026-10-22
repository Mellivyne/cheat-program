package com.cheatprogram.core;

import com.cheatprogram.render.Icon;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.MotionCurve;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Short-lived messages shown by the dynamic island (or as toasts when the island is off). */
public final class Notifications {
	public static final int MAX_MODULE_ENTRIES = 12;
	private static final int MAX_INFO_ENTRIES = 5;
	public static final long DURATION_MS = 2400;
	/** Extra time an expired entry stays around while it animates away. */
	public static final long FADE_MS = 360;
	private static final List<Entry> ENTRIES = new ArrayList<>();

	private Notifications() {
	}

	public static void push(Icon icon, String title, String detail) {
		add(new Entry(Kind.INFO, icon, title, detail, true, System.currentTimeMillis()));
	}

	/** Profile and friend messages always show; module toggles go through {@link #moduleToggled}, which respects the setting. */
	public static void push(String title, String detail, boolean positive) {
		add(new Entry(Kind.INFO, positive ? Icon.CHECK : Icon.INFO, title, detail, positive, System.currentTimeMillis()));
	}

	public static void moduleToggled(Module module) {
		if (Config.restartRequired()) return;
		com.cheatprogram.util.ToggleSounds.play(module.isEnabled(),module instanceof com.cheatprogram.module.visual.Hud);
		if (ClientSettings.INSTANCE != null && !ClientSettings.INSTANCE.notifications.on()) return;
		add(new Entry(Kind.MODULE, Icon.POWER, module.name(),
				Lang.t(module.isEnabled() ? "ui.notify.enabled" : "ui.notify.disabled"), module.isEnabled(), System.currentTimeMillis()));
	}

	private static void add(Entry entry) {
		ENTRIES.add(entry);
		int count = 0;
		for (Entry existing : ENTRIES) if (existing.kind == entry.kind) count++;
		int limit = entry.kind == Kind.MODULE ? MAX_MODULE_ENTRIES : MAX_INFO_ENTRIES;
		Iterator<Entry> iterator = ENTRIES.iterator();
		while (count > limit && iterator.hasNext()) {
			if (iterator.next().kind == entry.kind) { iterator.remove(); count--; }
		}
	}

	public static List<Entry> entries() {
		long now = System.currentTimeMillis();
		Iterator<Entry> it = ENTRIES.iterator();
		while (it.hasNext()) {
			if (now - it.next().created > DURATION_MS + FADE_MS) it.remove();
		}
		return ENTRIES;
	}

	public enum Kind {
		/** A module was switched on or off; {@code title} is the module name. */
		MODULE,
		INFO
	}

	public static final class Entry {
		public final Kind kind;
		public final Icon icon;
		public final String title;
		public final String detail;
		public final boolean positive;
		public final long created;
		/** Smoothed vertical slot, animated by the toast renderer. */
		public float slot = -1;

		Entry(Kind kind, Icon icon, String title, String detail, boolean positive, long created) {
			this.kind = kind;
			this.icon = icon;
			this.title = title;
			this.detail = detail;
			this.positive = positive;
			this.created = created;
		}

		/** 0..1 visibility: grows in when created, shrinks away after the display time. */
		public float visibility(long now) {
			long age = now - created;
			float in = Anim.easeOutCubic(age / 280f);
			float out = age > DURATION_MS ? 1f - MotionCurve.smootherStep((age - DURATION_MS) / (float) FADE_MS) : 1f;
			return Math.max(0f, Math.min(in, out));
		}
	}
}
