package com.cheatprogram.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Incremental, dependency-free 3D grid A*. Finds the shortest 26-neighbour grid route between
 * connected seed cells, then removes bends only after collision tests. This is not a proof of
 * the globally shortest continuous-space polyline; cell size, bounds and budgets limit the graph.
 */
public final class LinePathfinder {
	public record Point(double x, double y, double z) {
		public double distance(Point other) {
			return Math.sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z));
		}
	}

	public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		public boolean contains(Point p) {
			return p.x >= minX && p.x <= maxX && p.y >= minY && p.y <= maxY && p.z >= minZ && p.z <= maxZ;
		}
	}

	@FunctionalInterface
	public interface Collision {
		/** True only when the entire segment, including endpoints, is in known free space. */
		boolean clear(Point from, Point to);
	}

	public enum State { CONNECTING, SEARCHING, SMOOTHING, FOUND, NO_PATH, LIMIT }
	public static final class BudgetExceeded extends RuntimeException {
		public BudgetExceeded(String message) { super(message); }
	}

	private record Cell(int x, int y, int z) { }
	private record QueueEntry(Cell cell, double g, double f) { }

	public static final class Search {
		private final Point start;
		private final Point end;
		private final Bounds bounds;
		private final double size;
		private final int nodeLimit;
		private final int smoothingLimit;
		private final Collision collision;
		private final Map<Cell, Boolean> free = new HashMap<>();
		private final Map<Cell, Double> costs = new HashMap<>();
		private final Map<Cell, Cell> parent = new HashMap<>();
		private final Set<Cell> closed = new HashSet<>();
		private final PriorityQueue<QueueEntry> queue = new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::f));
		private List<Cell> seeds;
		private int seedIndex;
		private Cell startCell;
		private Cell endCell;
		private State state = State.CONNECTING;
		private String reason = "";
		private int expanded;
		private int smoothChecks;
		private List<Point> raw;
		private final List<Point> result = new ArrayList<>();
		private int anchor;
		private int candidate;

		public Search(Point start, Point end, Bounds bounds, double cellSize, int nodeLimit, int smoothingLimit, Collision collision) {
			if (!Double.isFinite(cellSize) || cellSize <= 0 || nodeLimit < 1 || smoothingLimit < 1) throw new IllegalArgumentException("Invalid path budget/grid size");
			this.start = start;
			this.end = end;
			this.bounds = bounds;
			this.size = cellSize;
			this.nodeLimit = nodeLimit;
			this.smoothingLimit = smoothingLimit;
			this.collision = collision;
			seeds = seeds(start);
			if (!bounds.contains(start) || !bounds.contains(end)) fail(State.NO_PATH, "endpoint outside search bounds");
		}

		public State state() { return state; }
		public String reason() { return reason; }
		public int expanded() { return expanded; }
		public boolean done() { return state == State.FOUND || state == State.NO_PATH || state == State.LIMIT; }
		public List<Point> path() { return state == State.FOUND ? List.copyOf(result) : List.of(); }
		/** Live callers may invalidate occupancy memoization between ticks; smoothing always re-tests rays. */
		public void refreshCollisionCache() { free.clear(); }

		/** Runs a bounded number of operations; deadlineNanos may additionally yield early. */
		public int step(int operationLimit, long deadlineNanos) {
			int work = 0;
			try {
				while (!done() && work < operationLimit && System.nanoTime() < deadlineNanos) {
					work++;
					if (state == State.CONNECTING) connect();
					else if (state == State.SEARCHING) expand();
					else smooth();
				}
			} catch (BudgetExceeded exhausted) {
				fail(State.LIMIT, exhausted.getMessage());
			}
			return work;
		}

		private void connect() {
			Point endpoint = startCell == null ? start : end;
			if (seedIndex >= seeds.size()) { fail(State.NO_PATH, "no free connected endpoint cell"); return; }
			Cell seed = seeds.get(seedIndex++);
			if (!isFree(seed) || !collision.clear(endpoint, point(seed))) return;
			if (startCell == null) {
				startCell = seed;
				seeds = seeds(end);
				seedIndex = 0;
			} else {
				endCell = seed;
				costs.put(startCell, 0d);
				queue.add(new QueueEntry(startCell, 0, point(startCell).distance(point(endCell))));
				state = State.SEARCHING;
			}
		}

		private List<Cell> seeds(Point endpoint) {
			Cell base = cell(endpoint);
			List<Cell> cells = new ArrayList<>();
			for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) cells.add(new Cell(base.x + x, base.y + y, base.z + z));
			cells.sort(Comparator.comparingDouble(c -> point(c).distance(endpoint)));
			return cells;
		}

		private void expand() {
			if (queue.isEmpty()) { fail(State.NO_PATH, "no route inside the loaded search grid"); return; }
			QueueEntry entry = queue.poll();
			Cell current = entry.cell;
			if (closed.contains(current) || entry.g > costs.getOrDefault(current, Double.POSITIVE_INFINITY) + 1e-9) return;
			if (current.equals(endCell)) { reconstruct(); return; }
			if (++expanded > nodeLimit) { fail(State.LIMIT, "node search budget reached"); return; }
			closed.add(current);
			Point from = point(current);
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dy == 0 && dz == 0) continue;
				Cell next = new Cell(current.x + dx, current.y + dy, current.z + dz);
				if (closed.contains(next) || !isFree(next) || !clearCorners(current, dx, dy, dz)) continue;
				Point to = point(next);
				if (!collision.clear(from, to)) continue;
				double g = entry.g + from.distance(to);
				if (g + 1e-9 >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
				costs.put(next, g);
				parent.put(next, current);
				queue.add(new QueueEntry(next, g, g + to.distance(point(endCell))));
			}
		}

		private boolean clearCorners(Cell from, int dx, int dy, int dz) {
			int axes = (dx != 0 ? 1 : 0) | (dy != 0 ? 2 : 0) | (dz != 0 ? 4 : 0);
			// Every proper subset cell must be free: diagonal moves cannot squeeze between touching solids.
			for (int mask = 1; mask < 8; mask++) {
				if ((mask & axes) != mask || mask == axes) continue;
				if (!isFree(new Cell(from.x + ((mask & 1) != 0 ? dx : 0), from.y + ((mask & 2) != 0 ? dy : 0), from.z + ((mask & 4) != 0 ? dz : 0)))) return false;
			}
			return true;
		}

		private void reconstruct() {
			List<Point> reversed = new ArrayList<>();
			Cell cursor = endCell;
			while (cursor != null) { reversed.add(point(cursor)); cursor = parent.get(cursor); }
			Collections.reverse(reversed);
			raw = new ArrayList<>();
			raw.add(start);
			for (Point point : reversed) if (raw.get(raw.size() - 1).distance(point) > 1e-8) raw.add(point);
			if (raw.get(raw.size() - 1).distance(end) > 1e-8) raw.add(end);
			result.add(start);
			anchor = 0;
			candidate = raw.size() - 1;
			state = State.SMOOTHING;
		}

		private void smooth() {
			if (anchor >= raw.size() - 1) { state = State.FOUND; return; }
			if (++smoothChecks > smoothingLimit) { fail(State.LIMIT, "visibility smoothing budget reached"); return; }
			if (collision.clear(raw.get(anchor), raw.get(candidate))) {
				result.add(raw.get(candidate));
				anchor = candidate;
				candidate = raw.size() - 1;
			} else {
				if (--candidate <= anchor) fail(State.NO_PATH, "route changed or an endpoint connector is blocked");
			}
		}

		private boolean isFree(Cell cell) {
			return free.computeIfAbsent(cell, c -> bounds.contains(point(c)) && collision.clear(point(c), point(c)));
		}
		private Cell cell(Point p) { return new Cell((int) Math.floor(p.x / size), (int) Math.floor(p.y / size), (int) Math.floor(p.z / size)); }
		private Point point(Cell c) { return new Point((c.x + 0.5) * size, (c.y + 0.5) * size, (c.z + 0.5) * size); }
		private void fail(State state, String reason) { this.state = state; this.reason = reason; result.clear(); }
	}

	/** Inclusive slab intersection. Inflate collision boxes before calling to prevent grazing corners. */
	public static boolean intersects(Point a, Point b, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		double[] origin = {a.x, a.y, a.z};
		double[] delta = {b.x - a.x, b.y - a.y, b.z - a.z};
		double[] min = {minX, minY, minZ};
		double[] max = {maxX, maxY, maxZ};
		double lo = 0, hi = 1;
		for (int axis = 0; axis < 3; axis++) {
			if (Math.abs(delta[axis]) < 1e-12) {
				if (origin[axis] < min[axis] || origin[axis] > max[axis]) return false;
			} else {
				double near = (min[axis] - origin[axis]) / delta[axis];
				double far = (max[axis] - origin[axis]) / delta[axis];
				if (near > far) { double swap = near; near = far; far = swap; }
				lo = Math.max(lo, near);
				hi = Math.min(hi, far);
				if (lo > hi) return false;
			}
		}
		return true;
	}
}
