package com.cheatprogram.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Loaded-terrain path search. Long trips proceed through reachable legs without teleportation. */
final class AiActorNavigation {
	private record Node(BlockPos position, double cost, double score, Node parent) { }
	private final AiActor actor;
	private final Vec3 destination;
	private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
	private final Map<BlockPos, Double> cost = new HashMap<>();
	private final ArrayDeque<Vec3> path = new ArrayDeque<>();
	private Node best;
	private boolean searching = true, exhausted;
	private Vec3 lastProgress;
	private int stalled;

	AiActorNavigation(AiActor actor, Vec3 destination) {
		this.actor = actor; this.destination = destination; lastProgress = actor.position();
		BlockPos start = actor.blockPosition();
		best = new Node(start, 0, distance(start), null); open.add(best); cost.put(start, 0.0);
	}
	boolean searching() { return searching; }
	boolean failed() { return exhausted; }
	boolean reached() { return actor.position().distanceToSqr(destination) < 1.25 * 1.25; }
	private double distance(BlockPos position) { return Vec3.atBottomCenterOf(position).distanceTo(destination); }

	void search() {
		if (!searching) return;
		long deadline = System.nanoTime() + 2_000_000L;
		int budget = 192;
		while (!open.isEmpty() && budget-- > 0 && System.nanoTime() < deadline) {
			Node current = open.poll();
			if (current.cost > cost.getOrDefault(current.position, Double.POSITIVE_INFINITY)) continue;
			if (distance(current.position) < distance(best.position)) best = current;
			if (distance(current.position) < 1.4) { finish(current); return; }
			if (cost.size() >= 4096 || current.cost >= 48) { finish(best); return; }
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos horizontal = current.position.relative(direction), next = null;
				for (int offset : new int[]{0, 1, -1}) {
					BlockPos candidate = horizontal.offset(0, offset, 0);
					if (standable(actor.level(), actor, candidate)) { next = candidate; break; }
				}
				if (next == null) continue;
				if (next.getY() > current.position.getY() && !headroom(current.position.above())) continue;
				double nextCost = current.cost + 1 + Math.abs(next.getY() - current.position.getY()) * 0.4;
				if (nextCost >= cost.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
				cost.put(next, nextCost); open.add(new Node(next, nextCost, nextCost + distance(next), current));
			}
		}
		if (open.isEmpty()) finish(best);
	}
	private boolean headroom(BlockPos feet) {
		return actor.level().noCollision(actor, box(feet));
	}
	private void finish(Node end) {
		searching = false;
		if (end.parent == null) { exhausted = true; return; }
		List<Vec3> reversed = new ArrayList<>();
		for (Node node = end; node.parent != null; node = node.parent) reversed.add(Vec3.atBottomCenterOf(node.position));
		for (int index = reversed.size() - 1; index >= 0; index--) path.addLast(reversed.get(index));
	}
	/** Null means this leg ended; callers start another leg toward the same full destination. */
	Vec3 next() {
		while (!path.isEmpty() && actor.position().distanceToSqr(path.getFirst()) < 0.38 * 0.38) path.removeFirst();
		if (path.isEmpty()) return null;
		if (!standable(actor.level(), actor, BlockPos.containing(path.getFirst()))) { exhausted = true; return null; }
		if (actor.position().distanceToSqr(lastProgress) > 0.08 * 0.08) { lastProgress = actor.position(); stalled = 0; }
		else if (++stalled > 60) { exhausted = true; return null; }
		return path.getFirst();
	}
	static AABB box(BlockPos feet) { return new AABB(feet.getX() + 0.2, feet.getY() + 0.01, feet.getZ() + 0.2,
			feet.getX() + 0.8, feet.getY() + 1.8, feet.getZ() + 0.8); }
	static boolean standable(ServerLevel level, net.minecraft.world.entity.Entity actor, BlockPos feet) {
		if (!level.hasChunkAt(feet) || feet.getY() <= level.getMinY() || feet.getY() + 2 >= level.getMaxY()
				|| !level.getWorldBorder().isWithinBounds(feet) || !level.getFluidState(feet).isEmpty()
				|| !level.getFluidState(feet.below()).isEmpty()) return false;
		var floor = level.getBlockState(feet.below()); var inside = level.getBlockState(feet);
		if (!floor.isFaceSturdy(level, feet.below(), Direction.UP) || dangerous(floor) || dangerous(inside)) return false;
		return level.noCollision(actor, box(feet));
	}
	private static boolean dangerous(net.minecraft.world.level.block.state.BlockState state) {
		return state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
				|| state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW);
	}
}
