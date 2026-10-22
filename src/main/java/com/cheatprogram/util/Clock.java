package com.cheatprogram.util;

/** Millisecond stopwatch with a randomizable delay. */
public final class Clock {
	private long last = System.currentTimeMillis();
	private long delay;

	public boolean passed(long ms) {
		return System.currentTimeMillis() - last >= ms;
	}

	/** True once the current delay has elapsed. */
	public boolean ready() {
		return passed(delay);
	}

	public void reset() {
		last = System.currentTimeMillis();
	}

	/** Resets and picks the next delay. */
	public void reset(long nextDelay) {
		last = System.currentTimeMillis();
		delay = nextDelay;
	}

	public long elapsed() {
		return System.currentTimeMillis() - last;
	}

	/** Random delay between clicks for a CPS value, with a little jitter. */
	public static long cpsDelay(double cps) {
		double base = 1000.0 / Math.max(1, cps);
		return Math.max(1, Math.round(base * (0.9 + Math.random() * 0.2)));
	}
}
