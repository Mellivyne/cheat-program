package com.cheatprogram.util;

import com.cheatprogram.core.ClientSettings;

/** Frame-rate independent exponential smoothing towards a target value. */
public final class Anim {
	public float value;
	public float target;

	public Anim(float value) {
		this.value = value;
		this.target = value;
	}

	/** @param speed roughly "how many times per second the gap shrinks by e" */
	public float update(float dt, float speed) {
		float k = 1f - (float) Math.exp(-speed * dt * globalSpeed());
		value += (target - value) * k;
		if (Math.abs(target - value) < 0.0005f) value = target;
		return value;
	}

	public float to(float target, float dt, float speed) {
		this.target = target;
		return update(dt, speed);
	}

	public void snap(float v) {
		value = v;
		target = v;
	}

	public static float globalSpeed() {
		ClientSettings s = ClientSettings.INSTANCE;
		return s == null ? 1f : (float) (s.animSpeed.val() / 100.0);
	}

	public static float clamp01(float v) {
		return v < 0 ? 0 : v > 1 ? 1 : v;
	}

	public static float easeOutCubic(float t) {
		t = clamp01(t);
		float f = 1 - t;
		return 1 - f * f * f;
	}

	public static float easeOutBack(float t) {
		t = clamp01(t);
		float c1 = 1.4f;
		float c3 = c1 + 1;
		float f = t - 1;
		return 1 + c3 * f * f * f + c1 * f * f;
	}

	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	public static int lerpColor(int a, int b, float t) {
		t = clamp01(t);
		int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
				| (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
	}

	/** White (or black) with the given alpha in 0..1. */
	public static int white(float alpha) {
		return (Math.round(clamp01(alpha) * 255) << 24) | 0xFFFFFF;
	}

	public static int black(float alpha) {
		return Math.round(clamp01(alpha) * 255) << 24;
	}
}
