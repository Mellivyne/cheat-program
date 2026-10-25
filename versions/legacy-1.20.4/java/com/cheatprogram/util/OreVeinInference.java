package com.cheatprogram.util;

import com.cheatprogram.module.visual.OreXray.Ore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bounded, observation-only neighborhood inference. A candidate is a geometric suggestion,
 * never a recovered block state or a calibrated probability. Even exposed anchors are only
 * what this client received; a custom server can obfuscate those too.
 */
public final class OreVeinInference {
	private static final int MAX_ANCHORS = 48;
	private static final int MAX_RESULT = 256;
	public record Candidate(BlockPos position, float association, BlockPos anchor, BlockState anchorState) { }
	private record Anchor(BlockPos position, BlockState state, String family) { }
	private record Shape(List<Anchor> anchors, Vec3 center, Vec3 direction, double halfLength) { }
	private ClientLevel world;
	private List<Shape> shapes = List.of();
	private List<Candidate> results = List.of();
	private final Map<Long, Candidate> working = new LinkedHashMap<>();
	private final Set<Long> considered = new HashSet<>();
	private Vec3 plannedCenter = Vec3.ZERO;
	private int radius, minimum, limit, tick, finishedAt, shapeIndex, anchorIndex, cellIndex, anchorCount;
	private boolean running;

	public List<Candidate> results() { return results; }
	public int anchorCount() { return anchorCount; }
	public boolean running() { return running; }
	public void clear() {
		world = null; shapes = List.of(); results = List.of(); working.clear(); considered.clear();
		running = false; tick = finishedAt = shapeIndex = anchorIndex = cellIndex = anchorCount = 0;
	}

	public void tick(ClientLevel level, Vec3 center, Collection<List<Ore>> exposed,
			int requestedRadius, int requestedMinimum, int requestedLimit, int viewRange) {
		int nextRadius = Math.max(2, Math.min(4, requestedRadius));
		int nextMinimum = Math.max(10, Math.min(90, requestedMinimum));
		int nextLimit = Math.max(16, Math.min(MAX_RESULT, requestedLimit));
		if (world != level || radius != nextRadius || minimum != nextMinimum || limit != nextLimit) {
			clear(); world = level; radius = nextRadius; minimum = nextMinimum; limit = nextLimit;
		}
		tick++;
		// Do not leave a stale candidate behind after mining, exposing it, unloading a chunk,
		// or removing the anchor. None of these reads asks the server for additional chunks.
		results = results.stream().filter(candidate -> valid(candidate, center, viewRange)).toList();
		if (!running && (tick == 1 || tick - finishedAt >= 12)
				|| center.distanceToSqr(plannedCenter) > 64) plan(center, exposed, viewRange);
		if (!running) return;
		long deadline = System.nanoTime() + 800_000L;
		int checked = 0, side = radius * 2 + 1, volume = side * side * side;
		while (running && checked < 2048 && System.nanoTime() < deadline) {
			if (shapeIndex >= shapes.size()) {
				running = false; finishedAt = tick; publish(center, viewRange); break;
			}
			Shape shape = shapes.get(shapeIndex);
			if (anchorIndex >= shape.anchors.size()) {
				shapeIndex++; anchorIndex = cellIndex = 0; considered.clear(); continue;
			}
			Anchor anchor = shape.anchors.get(anchorIndex);
			if (cellIndex >= volume) { anchorIndex++; cellIndex = 0; continue; }
			int cell = cellIndex++;
			int dx = cell % side - radius, dy = cell / side % side - radius, dz = cell / (side * side) - radius;
			checked++;
			if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
			BlockPos position = anchor.position.offset(dx, dy, dz);
			if (!considered.add(position.asLong()) || position.distToCenterSqr(center) > (double) viewRange * viewRange) continue;
			BlockState state = state(position);
			if (!possibleHost(state) || exposed(position)) continue;
			double nearest = Double.MAX_VALUE;
			int support = 0; Anchor closest = anchor;
			for (Anchor sample : shape.anchors) {
				double distance = sample.position.distSqr(position);
				if (distance < nearest) { nearest = distance; closest = sample; }
				if (distance <= 6.25) support++;
			}
			// Nearby support matters more than a fitted axis. With one point there is no known
			// vein direction, so use a compact radial suggestion instead of inventing one.
			double association = .72 * Math.exp(-nearest / (radius * radius * .8));
			if (shape.direction.lengthSqr() > .5) {
				Vec3 displacement = Vec3.atCenterOf(position).subtract(shape.center);
				double along = displacement.dot(shape.direction);
				double across = Math.max(0, displacement.lengthSqr() - along * along);
				double extension = Math.max(0, Math.abs(along) - shape.halfLength);
				double spread = Math.max(1, radius * .55);
				association = .60 * Math.exp(-nearest / (radius * radius * .8))
						+ .18 * Math.exp(-(across + extension * extension * .5) / (spread * spread));
			}
			association = Math.min(.92, association + Math.min(3, Math.max(0, support - 1)) * .045);
			if (association * 100 < minimum) continue;
			Candidate candidate = new Candidate(position.immutable(), (float) association, closest.position, closest.state);
			Candidate previous = working.get(position.asLong());
			if (previous == null || previous.association < candidate.association) working.put(position.asLong(), candidate);
			if (working.size() > MAX_RESULT * 2) trimWorking(center);
		}
		if (tick % 4 == 0) publish(center, viewRange);
	}

	private void plan(Vec3 center, Collection<List<Ore>> exposed, int viewRange) {
		plannedCenter = center; working.clear(); considered.clear(); shapeIndex = anchorIndex = cellIndex = 0;
		List<Anchor> nearby = new ArrayList<>();
		for (List<Ore> section : exposed) for (Ore ore : section) {
			String family = family(ore.state());
			if (family != null && ore.position().distToCenterSqr(center) <= (double) viewRange * viewRange)
				nearby.add(new Anchor(ore.position(), ore.state(), family));
		}
		nearby.sort(Comparator.comparingDouble(anchor -> anchor.position.distToCenterSqr(center)));
		List<Anchor> anchors = new ArrayList<>();
		// The main overlay already recorded these as exposed. Recheck a small nearest prefix
		// so a large cave cannot turn one planning tick into thousands of neighbor reads.
		for (int i = 0; i < Math.min(nearby.size(), MAX_ANCHORS * 3) && anchors.size() < MAX_ANCHORS; i++) {
			Anchor anchor = nearby.get(i);
			if (state(anchor.position) == anchor.state && exposed(anchor.position)) anchors.add(anchor);
		}
		anchorCount = anchors.size();
		List<Shape> next = new ArrayList<>();
		Set<BlockPos> used = new HashSet<>();
		for (Anchor start : anchors) {
			if (!used.add(start.position)) continue;
			List<Anchor> component = new ArrayList<>(); component.add(start);
			for (int cursor = 0; cursor < component.size(); cursor++) for (Anchor other : anchors) {
				if (other.family.equals(start.family) && !used.contains(other.position)
						&& component.get(cursor).position.distSqr(other.position) <= 3) {
					used.add(other.position); component.add(other);
				}
			}
			Vec3 average = Vec3.ZERO;
			for (Anchor sample : component) average = average.add(Vec3.atCenterOf(sample.position));
			average = average.scale(1.0 / component.size());
			Vec3 direction = Vec3.ZERO; double length = 0;
			for (Anchor a : component) for (Anchor b : component) {
				double distance = a.position.distSqr(b.position);
				if (distance > length) { length = distance; direction = Vec3.atCenterOf(b.position).subtract(Vec3.atCenterOf(a.position)).normalize(); }
			}
			next.add(new Shape(List.copyOf(component), average, direction, Math.sqrt(length) * .5));
		}
		shapes = List.copyOf(next); running = !shapes.isEmpty();
		if (!running) { results = List.of(); finishedAt = tick; }
	}

	private boolean valid(Candidate candidate, Vec3 center, int viewRange) {
		return candidate.position.distToCenterSqr(center) <= (double) viewRange * viewRange
				&& state(candidate.anchor) == candidate.anchorState && exposed(candidate.anchor)
				&& possibleHost(state(candidate.position)) && !exposed(candidate.position);
	}
	private void trimWorking(Vec3 center) {
		List<Candidate> best = working.values().stream().sorted(order(center)).limit(MAX_RESULT).toList();
		working.clear(); for (Candidate candidate : best) working.put(candidate.position.asLong(), candidate);
	}
	private Comparator<Candidate> order(Vec3 center) {
		return Comparator.comparingDouble((Candidate candidate) -> candidate.association).reversed()
				.thenComparingDouble(candidate -> candidate.position.distToCenterSqr(center));
	}
	private void publish(Vec3 center, int viewRange) {
		results = working.values().stream().filter(candidate -> valid(candidate, center, viewRange))
				.sorted(order(center)).limit(limit).toList();
	}
	private BlockState state(BlockPos position) {
		if (world == null || world.isOutsideBuildHeight(position.getY())) return null;
		LevelChunk chunk = world.getChunkSource().getChunk(position.getX() >> 4, position.getZ() >> 4, ChunkStatus.FULL, false);
		return chunk == null ? null : chunk.getBlockState(position);
	}
	private boolean exposed(BlockPos position) {
		for (Direction direction : Direction.values()) {
			BlockState neighbor = state(position.relative(direction));
			if (neighbor != null && !neighbor.canOcclude()) return true;
		}
		return false;
	}
	private static boolean possibleHost(BlockState state) {
		if (state == null || !state.canOcclude() || state.hasBlockEntity()) return false;
		return state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE) || state.is(Blocks.ANDESITE)
				|| state.is(Blocks.DIORITE) || state.is(Blocks.GRANITE) || state.is(Blocks.TUFF)
				|| state.is(Blocks.NETHERRACK) || state.is(Blocks.BLACKSTONE) || state.is(Blocks.BASALT)
				|| family(state) != null;
	}
	private static String family(BlockState state) {
		var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
		if (!id.getNamespace().equals("minecraft")) return null;
		String path = id.getPath();
		if (path.equals("ancient_debris")) return path;
		if (!path.endsWith("_ore")) return null;
		return path.startsWith("deepslate_") ? path.substring("deepslate_".length()) : path;
	}
}
