package com.cheatprogram.util;

/** Client-thread pacing for normal interactions; never acknowledges or cancels a server correction. */
public final class ServerActionGate {
	private static final long SETTLE_NANOS = 5_000_000_000L;
	private static final long ACK_TIMEOUT_NANOS = 2_000_000_000L;
	private long resumeAt, lastCorrection, predictionAt;
	private int correctionCount, pendingSequence = -1;
	private boolean timedOut, paused;

	public void reset() {
		resumeAt = lastCorrection = predictionAt = 0;
		correctionCount = 0; pendingSequence = -1; timedOut = paused = false;
	}

	/** Repeated corrections extend the pause instead of repeatedly fighting the authoritative position. */
	public void corrected(long now) {
		correctionCount = correctionCount > 0 && now - lastCorrection >= 0 && now - lastCorrection < SETTLE_NANOS
				? Math.min(4, correctionCount + 1) : 1;
		lastCorrection = now;
		long next = now + (250_000_000L << correctionCount);
		if (!paused || next - resumeAt > 0) resumeAt = next;
		paused = true;
	}

	public boolean ready(long now) { return !recovering(now) && pendingSequence < 0; }
	public boolean recovering(long now) { return paused && now - resumeAt < 0; }
	public boolean pending() { return pendingSequence >= 0; }

	public boolean predict(int sequence, long now) {
		if (sequence < 0 || !ready(now)) return false;
		pendingSequence = sequence; predictionAt = now; timedOut = false;
		return true;
	}

	/** A later sequence acknowledgement also covers earlier ordered predictions. */
	public boolean acknowledge(int sequence) {
		if (pendingSequence < 0 || sequence < pendingSequence) return false;
		pendingSequence = -1; predictionAt = 0; timedOut = false;
		return true;
	}

	/** Missing acknowledgements leave the block unconfirmed. A timer cannot turn prediction into fact. */
	public boolean pollTimeout(long now) {
		if (!pending() || timedOut || now - predictionAt < ACK_TIMEOUT_NANOS) return false;
		timedOut = true; corrected(now); return true;
	}

	public static boolean withinReach(double squaredDistance, double reach) {
		return Double.isFinite(squaredDistance) && squaredDistance >= 0 && Double.isFinite(reach)
				&& reach > 0 && squaredDistance <= reach * reach;
	}

	public record KeyboardDirection(float forward, float strafe) { }

	/** Follow view uses a real eight-way keyboard direction, matching the input flags sent to the server. */
	public static KeyboardDirection keyboardDirection(float forward, float strafe, float yawDifference) {
		if (!Float.isFinite(forward) || !Float.isFinite(strafe) || !Float.isFinite(yawDifference)
				|| forward == 0 && strafe == 0) return new KeyboardDirection(0, 0);
		double angle = Math.atan2(strafe, forward) + Math.toRadians(yawDifference);
		int octant = Math.floorMod((int) Math.round(angle / (Math.PI / 4)), 8);
		return new KeyboardDirection(switch (octant) { case 0, 1, 7 -> 1; case 3, 4, 5 -> -1; default -> 0; },
				switch (octant) { case 1, 2, 3 -> 1; case 5, 6, 7 -> -1; default -> 0; });
	}
}
