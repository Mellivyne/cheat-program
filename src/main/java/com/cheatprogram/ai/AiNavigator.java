package com.cheatprogram.ai;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Bounded incremental ground A*. Checks a real body box, support, fluids, drops and jump clearance. */
final class AiNavigator {
	private record Node(int x, int y16, int z) {
		Vec3 point() { return new Vec3(x + .5, y16 / 16.0, z + .5); }
	}
	private record Entry(Node node, double cost, double score) { }
	private final Minecraft mc;
	private final Vec3 destination, origin;
	private final PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::score));
	private final Map<Node, Double> costs = new HashMap<>();
	private final Map<Node, Node> parents = new HashMap<>();
	private final Set<Node> closed = new HashSet<>();
	private List<Vec3> route;
	private String reason;
	private int expanded;

	AiNavigator(Minecraft mc, Vec3 destination) {
		this.mc = mc; this.destination = destination; origin = mc.player.position();
		Node start = new Node(Mth.floor(origin.x), (int) Math.round(origin.y * 16), Mth.floor(origin.z));
		costs.put(start, 0.0);
		open.add(new Entry(start, 0, heuristic(start)));
	}
	boolean ready() { return route != null; }
	boolean failed() { return reason != null; }
	String reason() { return reason == null ? "" : reason; }
	List<Vec3> path() { return route == null ? List.of() : route; }

	void step() {
		long deadline = System.nanoTime() + 1_500_000;
		for (int operations = 0; operations < 48 && !ready() && !failed() && System.nanoTime() < deadline; operations++) {
			if (open.isEmpty()) { reason = "附近已加载的安全地面中没有可行路线。"; return; }
			Entry entry = open.remove();
			Node current = entry.node();
			if (closed.contains(current) || entry.cost() > costs.getOrDefault(current, Double.POSITIVE_INFINITY) + 1e-6) continue;
			Vec3 from = current.point();
			if (current.x == Mth.floor(destination.x) && current.z == Mth.floor(destination.z) && Math.abs(from.y - destination.y) < 1.25) {
				reconstruct(current); return;
			}
			if (++expanded > 4096) { reason = "路线搜索达到预算上限，请拆成更近的目标。"; return; }
			closed.add(current);
			for (Direction direction : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
				int x = current.x + direction.getStepX(), z = current.z + direction.getStepZ();
				if (Math.abs(x + .5 - origin.x) > 68 || Math.abs(z + .5 - origin.z) > 68) continue;
				Vec3 candidate = landing(mc, x + .5, z + .5, from.y);
				if (candidate == null || Math.abs(candidate.y - origin.y) > 8) continue;
				Node next = new Node(x, (int) Math.round(candidate.y * 16), z);
				if (closed.contains(next) || !transition(from, candidate)) continue;
				double nextCost = entry.cost() + from.distanceTo(candidate) + Math.max(0, candidate.y - from.y) * .5;
				if (nextCost + 1e-6 >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
				costs.put(next, nextCost); parents.put(next, current);
				open.add(new Entry(next, nextCost, nextCost + heuristic(next)));
			}
		}
	}

	private boolean transition(Vec3 from, Vec3 to) {
		double rise = to.y - from.y;
		if (rise > 1.01 || rise < -1.1) return false;
		if (rise > .4) {
			// A one-block jump needs full-height clearance over both the departure and destination.
			if (!bodyClear(mc, from.add(0, rise + .25, 0)) || !bodyClear(mc, to.add(0, .25, 0))) return false;
		}
		for (int i = 1; i <= 4; i++) {
			double factor = i / 4.0;
			Vec3 point = new Vec3(Mth.lerp(factor, from.x, to.x), Math.max(from.y, to.y) + (rise > .4 ? .05 : 0), Mth.lerp(factor, from.z, to.z));
			if (!bodyClear(mc, point)) return false;
			if (landing(mc, point.x, point.z, point.y) == null) return false;
		}
		return true;
	}
	private double heuristic(Node node) { return Math.hypot(node.point().x - destination.x, node.point().z - destination.z); }
	private void reconstruct(Node end) {
		ArrayList<Vec3> result = new ArrayList<>();
		for (Node node = end; node != null; node = parents.get(node)) result.add(node.point());
		Collections.reverse(result);
		// The player may stand off centre; don't first steer sideways into the edge of the starting cell.
		if (!result.isEmpty()) result.removeFirst();
		route = List.copyOf(result);
	}

	static boolean safeGround(Minecraft mc, Vec3 projected) {
		return landing(mc, projected.x, projected.z, projected.y) != null;
	}
	private static Vec3 landing(Minecraft mc, double x, double z, double referenceY) {
		int base = Mth.floor(referenceY);
		for (int y = base + 1; y >= base - 2; y--) {
			BlockPos support = BlockPos.containing(x, y, z);
			if (!mc.level.hasChunkAt(support) || !mc.level.getWorldBorder().isWithinBounds(support)) continue;
			BlockState state = mc.level.getBlockState(support);
			if (dangerous(state) || !state.getFluidState().isEmpty()) continue;
			VoxelShape shape = state.getCollisionShape(mc.level, support);
			if (shape.isEmpty()) continue;
			double top = y + shape.max(Direction.Axis.Y);
			if (top - referenceY > 1.01 || referenceY - top > 1.1) continue;
			Vec3 point = new Vec3(x, top, z);
			if (!bodyClear(mc, point)) continue;
			// Require collision below the footprint itself, not a distant fence/pane within that block.
			double half = Math.min(.22, mc.player.getBbWidth() * .35);
			AABB supportBox = new AABB(x - half, top - .08, z - half, x + half, top - .001, z + half);
			if (mc.level.noCollision(mc.player, supportBox)) continue;
			return point;
		}
		return null;
	}
	private static boolean bodyClear(Minecraft mc, Vec3 feet) {
		double half = mc.player.getBbWidth() / 2.0 + .015;
		AABB body = new AABB(feet.x - half, feet.y + .001, feet.z - half, feet.x + half, feet.y + mc.player.getBbHeight(), feet.z + half);
		BlockPos lower = BlockPos.containing(body.minX, body.minY, body.minZ), upper = BlockPos.containing(body.maxX, body.maxY, body.maxZ);
		for (BlockPos pos : BlockPos.betweenClosed(lower, upper)) {
			if (!mc.level.hasChunkAt(pos) || !mc.level.getWorldBorder().isWithinBounds(pos)) return false;
			BlockState state = mc.level.getBlockState(pos);
			if (dangerous(state) || !state.getFluidState().isEmpty()) return false;
		}
		return mc.level.noCollision(mc.player, body);
	}
	private static boolean dangerous(BlockState state) {
		return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CACTUS)
				|| state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)
				|| state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
	}
}
