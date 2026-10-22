package com.cheatprogram.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/** Small incremental path legs over already-loaded safe terrain; no teleport or forced chunks. */
final class BotNavigation {
	private record Node(BlockPos position, double cost, double score, Node parent) { }
	private final BotActor actor;
	private final Vec3 destination;
	private final PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
	private final Map<BlockPos, Double> costs = new HashMap<>();
	private final ArrayDeque<Vec3> path = new ArrayDeque<>();
	private Node best;
	private boolean searching = true;
	private Vec3 progress;
	private int lastProgressTick;
	BotNavigation(BotActor actor, Vec3 destination) {
		this.actor = actor; this.destination = destination; BlockPos start = BlockPos.containing(actor.position().add(0, .05, 0));
		progress=actor.position();lastProgressTick=actor.tickCount;
		best = new Node(start, 0, distance(start), null); open.add(best); costs.put(start, 0d);
	}
	boolean searching() { return searching; }
	private double distance(BlockPos position) { return Vec3.atBottomCenterOf(position).distanceTo(destination); }
	void search() {
		if (!searching) return;
		int budget = 48; long deadline = System.nanoTime() + 400_000;
		while (!open.isEmpty() && budget-- > 0 && System.nanoTime() < deadline) {
			Node node = open.poll(); if (node.cost > costs.getOrDefault(node.position, Double.POSITIVE_INFINITY)) continue;
			if (distance(node.position) < distance(best.position)) best = node;
			if (distance(node.position) <= 1.5) { finish(node); return; }
			if (costs.size() >= 1024 || node.cost >= 24) { finish(best); return; }
			for (Direction direction : Direction.Plane.HORIZONTAL) {
				BlockPos horizontal = node.position.relative(direction), next = null;
				for (int dy : new int[]{0, 1, -1}) {
					BlockPos candidate = horizontal.offset(0, dy, 0);
					if (AiActorNavigation.standable(actor.level(), actor, candidate)) { next = candidate.immutable(); break; }
				}
				if (next == null || next.getY() > node.position.getY() && !actor.level().noCollision(actor, AiActorNavigation.box(node.position.above()))) continue;
				double cost = node.cost + 1 + Math.abs(next.getY() - node.position.getY()) * .4;
				if (cost >= costs.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
				costs.put(next, cost); open.add(new Node(next, cost, cost + distance(next), node));
			}
		}
		if (open.isEmpty()) finish(best);
	}
	private void finish(Node node) {
		searching = false; List<Vec3> reverse = new ArrayList<>();
		for (Node next = node; next.parent != null; next = next.parent) reverse.add(Vec3.atBottomCenterOf(next.position));
		for (int i = reverse.size() - 1; i >= 0; i--) path.addLast(reverse.get(i));
	}
	Vec3 next() {
		if(actor.position().distanceToSqr(progress)>.05*.05){progress=actor.position();lastProgressTick=actor.tickCount;}
		else if(!searching&&actor.tickCount-lastProgressTick>30){path.clear();return null;}
		while (!path.isEmpty() && actor.position().distanceToSqr(path.getFirst()) < .4 * .4) path.removeFirst();
		if (path.isEmpty() || !AiActorNavigation.standable(actor.level(), actor, BlockPos.containing(path.getFirst()))) return null;
		return path.getFirst();
	}
}
