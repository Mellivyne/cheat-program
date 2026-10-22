package com.cheatprogram.util;

/** Easing for finite visual transitions. Real clocks and continuous rotations stay unmodified. */
public final class MotionCurve {
	private MotionCurve() { }

	/** Quintic ease-in/out; both velocity and acceleration settle to zero at either endpoint. */
	public static float smootherStep(float progress) {
		float t = Anim.clamp01(progress);
		return t * t * t * (t * (6 * t - 15) + 10);
	}
}
