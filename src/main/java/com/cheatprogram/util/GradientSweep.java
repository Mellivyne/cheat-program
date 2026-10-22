package com.cheatprogram.util;

/** A repeatable upward sweep with a smooth, non-linear velocity inside each cycle. */
public final class GradientSweep {
	private static volatile float frozenTime = Float.NaN;

	private GradientSweep() {
	}

	/** Preserve the acceleration setting as a timing bias, then soften both ends with quintic easing. */
	public static float phase(double elapsedSeconds, double periodSeconds, double acceleration) {
		double cycles = Math.max(0, elapsedSeconds) / Math.max(0.001, periodSeconds);
		double t = cycles - Math.floor(cycles);
		if (development() && !Float.isNaN(frozenTime)) t = frozenTime;
		double a = Math.max(0, acceleration);
		return MotionCurve.smootherStep((float) ((t + 0.5 * a * t * t) / (1 + 0.5 * a)));
	}

	/** 0 is the list's bottom and 1 its top. The head travels upward, including its soft edges. */
	public static float clarity(float upwardPosition, float progress, float bandWidth) {
		float halfBand = Math.max(0.01f, bandWidth) * 0.5f;
		// Start below the list and finish above it: both cycle boundaries have the same dark floor.
		float head = -halfBand + clamp(progress) * (1 + 2 * halfBand);
		float distance = Math.min(1, Math.abs(clamp(upwardPosition) - head) / halfBand);
		return 1 - distance * distance * (3 - 2 * distance);
	}

	public static float brightness(float clarity, float darkBrightness) {
		float floor = clamp(darkBrightness);
		return floor + (1 - floor) * clamp(clarity);
	}

	public static float blur(float clarity, float maximumBlur) {
		return Math.max(0, maximumBlur) * (1 - clamp(clarity));
	}

	/** Freeze normalized elapsed time before acceleration, only in a development launch. */
	public static void freezePhase(float normalizedTime) {
		if (development()) frozenTime = clamp(normalizedTime);
	}

	public static void releasePhase() {
		if (development()) frozenTime = Float.NaN;
	}

	private static boolean development() {
		return !System.getProperty("cheatprogram.dev", "").isBlank();
	}

	private static float clamp(float value) {
		return Math.max(0, Math.min(1, value));
	}
}
