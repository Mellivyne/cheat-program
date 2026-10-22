package com.cheatprogram.util;

import java.util.ArrayDeque;
import java.util.Deque;

/** Counts mouse clicks over the last second. */
public final class Cps {
	private static final Deque<Long> LEFT = new ArrayDeque<>();
	private static final Deque<Long> RIGHT = new ArrayDeque<>();

	private Cps() {
	}

	public static void click(int button) {
		if (button == 0) LEFT.add(System.currentTimeMillis());
		else if (button == 1) RIGHT.add(System.currentTimeMillis());
	}

	public static int left() {
		return count(LEFT);
	}

	public static int right() {
		return count(RIGHT);
	}

	private static int count(Deque<Long> clicks) {
		long cutoff = System.currentTimeMillis() - 1000;
		while (!clicks.isEmpty() && clicks.peekFirst() < cutoff) clicks.pollFirst();
		return clicks.size();
	}
}
