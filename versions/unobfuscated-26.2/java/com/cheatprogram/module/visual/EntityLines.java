package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.LinePathfinder;
import com.cheatprogram.util.LinePathfinder.Bounds;
import com.cheatprogram.util.LinePathfinder.Point;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Observation-only lines. Geometry is sampled on client ticks; render never runs path searches. */
public final class EntityLines extends Module {
	private final ModeSetting targets = add(new ModeSetting("targets", "line_players", "line_players", "line_named", "line_passives", "line_hostiles", "line_living"));
	private final TextSetting players = add(new TextSetting("players", "", 512)).visibleWhen(() -> targets.is("line_named"));
	private final ModeSetting mode = add(new ModeSetting("mode", "straight", "straight", "shortest"));
	private final NumberSetting range = add(new NumberSetting("range", 64, 1, 128, 1));
	private final NumberSetting updateTicks = add(new NumberSetting("updateTicks", 10, 2, 40, 1, "ticks"));
	private final NumberSetting maxTargets = add(new NumberSetting("maxTargets", 4, 1, 16, 1));
	private final NumberSetting searchBudget = add(new NumberSetting("searchBudget", 6000, 256, 12000, 1));
	private final NumberSetting tickBudget = add(new NumberSetting("tickBudget", 64, 8, 256, 8));
	private final NumberSetting gridSize = add(new NumberSetting("gridSize", 1, 0.5, 2, 0.5));
	private final NumberSetting detour = add(new NumberSetting("detour", 12, 2, 24, 1));
	private final NumberSetting opacity = add(new NumberSetting("opacity", 70, 10, 100, 5, "%"));
	private final NumberSetting lineWidth = add(new NumberSetting("lineWidth", 1.6, 0.5, 4, 0.1));
	private final Map<UUID, Route> routes = new LinkedHashMap<>();
	private final Map<UUID, Pending> jobs = new LinkedHashMap<>();
	private final Map<UUID, Integer> attempts = new HashMap<>();
	private final Map<UUID, String> states = new HashMap<>();
	private List<LivingEntity> selected = List.of();
	private Set<String> names = Set.of();
	private ClientLevel lastLevel;
	private String options = "";
	private int tick;
	private int geometryCursor;
	private int searchCursor;

	private static final class Route {
		final LivingEntity entity;
		final List<Point> path;
		final int computed;
		List<Point> visible = List.of();
		List<Point> checking;
		int segment;
		int nextCheck;
		boolean cameraLink;
		Route(LivingEntity entity, List<Point> path, int computed) { this.entity = entity; this.path = path; this.computed = computed; }
	}
	private static final class Pending {
		final LivingEntity entity;
		final Point start, end;
		final LinePathfinder.Search search;
		final WorldGeometry geometry;
		final int created;
		Route candidate;
		Pending(LivingEntity entity, Point start, Point end, LinePathfinder.Search search, WorldGeometry geometry, int created) {
			this.entity = entity; this.start = start; this.end = end; this.search = search; this.geometry = geometry; this.created = created;
		}
	}
	private record Validation(int work, boolean blocked, boolean complete) { }
	public record PathState(UUID target, String state, List<Vec3> points) { }

	public EntityLines() { super("entitylines", Category.VISUAL); }
	@Override public String suffix() { return mode.display(); }
	@Override public void onEnable() { clear(); }
	@Override public void onDisable() { clear(); }

	private void clear() {
		routes.clear(); jobs.clear(); attempts.clear(); states.clear(); selected = List.of(); lastLevel = null; tick = 0;
	}

	/** Immutable snapshots for development checks; failures never contain a wall-crossing fallback. */
	public List<PathState> paths() {
		if (System.getProperty("cheatprogram.dev", "").isEmpty()) return List.of();
		List<PathState> snapshot = new ArrayList<>();
		for (LivingEntity entity : selected) {
			Route route = routes.get(entity.getUUID());
			List<Vec3> points = mode.is("straight") ? List.of(mc.player.getEyePosition(), body(entity))
					: route == null ? List.of() : route.visible.stream().map(EntityLines::vec).toList();
			snapshot.add(new PathState(entity.getUUID(), mode.is("straight") ? "straight" : states.getOrDefault(entity.getUUID(), "pending"), points));
		}
		return List.copyOf(snapshot);
	}

	@Override
	public void onTick() {
		if (!inGame() || !mc.player.isAlive() || mc.isPaused()) { clear(); return; }
		String signature = targets.get() + "|" + players.get() + "|" + mode.get() + "|" + range.val() + "|"
				+ updateTicks.val() + "|" + maxTargets.val() + "|" + searchBudget.val() + "|" + tickBudget.val() + "|" + gridSize.val() + "|" + detour.val();
		if (lastLevel != mc.level || !options.equals(signature)) {
			clear(); lastLevel = mc.level; options = signature; names = parseNames(players.get());
		}
		tick++;
		if (tick == 1 || tick % updateTicks.intVal() == 0) selectTargets();
		selected = selected.stream().filter(this::matches).filter(e -> mc.player.getEyePosition().distanceToSqr(body(e)) <= range.val() * range.val()).toList();
		Set<UUID> ids = new HashSet<>();
		for (LivingEntity entity : selected) ids.add(entity.getUUID());
		routes.keySet().retainAll(ids); jobs.keySet().retainAll(ids); attempts.keySet().retainAll(ids); states.keySet().retainAll(ids);
		if (mode.is("straight")) return;

		long deadline = System.nanoTime() + 4_000_000L;
		int budget = tickBudget.intVal();
		WorldGeometry live = new WorldGeometry(mc.level);
		try {
			List<Route> cached = new ArrayList<>(routes.values());
			int validationBudget = Math.max(2, budget / 2);
			long validationDeadline = System.nanoTime() + 2_000_000L;
			if (!cached.isEmpty()) {
				int first = geometryCursor++ % cached.size();
				for (int index = 0; index < cached.size() && validationBudget > 0 && System.nanoTime() < validationDeadline; index++) {
					Route route = cached.get((first + index) % cached.size());
					Validation check = validate(route, live, validationBudget, validationDeadline);
					budget -= check.work; validationBudget -= check.work;
					if (check.blocked) {
						UUID id = route.entity.getUUID();
						routes.remove(id); jobs.remove(id); attempts.remove(id); states.put(id, "blocked");
					}
				}
			}
			List<LivingEntity> candidates = new ArrayList<>(selected);
			candidates.sort(Comparator.comparingInt(e -> attempts.getOrDefault(e.getUUID(), Integer.MIN_VALUE)));
			for (LivingEntity entity : candidates) {
				if (jobs.size() >= 2 || budget <= 0 || System.nanoTime() >= deadline) break;
				UUID id = entity.getUUID();
				Route existing = routes.get(id);
				if (jobs.containsKey(id) || attempts.containsKey(id) && tick - attempts.get(id) < updateTicks.intVal()) continue;
				if (existing != null && tick - existing.computed < updateTicks.intVal()) continue;
				Point start = point(mc.player.getEyePosition());
				WorldGeometry geometry = new WorldGeometry(mc.level);
				Point end = endpoint(entity, geometry);
				attempts.put(id, tick); budget--;
				if (end == null) { states.put(id, "blocked endpoint"); routes.remove(id); continue; }
				if (geometry.clear(start, end)) {
					Route direct = new Route(entity, List.of(start, end), tick);
					direct.visible = direct.path; direct.nextCheck = tick + 1;
					routes.put(id, direct); states.put(id, "ready/direct");
					continue;
				}
				double margin = detour.val();
				Bounds bounds = new Bounds(Math.min(start.x(), end.x()) - margin, Math.max(mc.level.getMinY(), Math.min(start.y(), end.y()) - margin),
						Math.min(start.z(), end.z()) - margin, Math.max(start.x(), end.x()) + margin,
						Math.min(mc.level.getMaxY() + 0.99, Math.max(start.y(), end.y()) + margin), Math.max(start.z(), end.z()) + margin);
				jobs.put(id, new Pending(entity, start, end, new LinePathfinder.Search(start, end, bounds, gridSize.val(), searchBudget.intVal(), 512, geometry), geometry, tick));
				states.put(id, "searching");
			}
			List<UUID> idsToProcess = new ArrayList<>(jobs.keySet());
			if (!idsToProcess.isEmpty()) {
				int first = searchCursor++ % idsToProcess.size();
				for (int index = 0; index < idsToProcess.size() && budget > 0 && System.nanoTime() < deadline; index++) {
					UUID id = idsToProcess.get((first + index) % idsToProcess.size());
					Pending job = jobs.get(id);
					if (tick - job.created > 200 || job.start.distance(point(mc.player.getEyePosition())) > 3
							|| job.end.distance(point(body(job.entity))) > 3) { states.put(id, "moved/expired"); jobs.remove(id); continue; }
					job.geometry.beginTick(); job.search.refreshCollisionCache();
					budget -= job.search.step(Math.max(1, Math.min(budget, tickBudget.intVal() / Math.max(1, jobs.size()))), deadline);
					if (!job.search.done()) continue;
					if (job.search.state() != LinePathfinder.State.FOUND) {
						states.put(id, job.search.state().name().toLowerCase(Locale.ROOT) + ": " + job.search.reason()); jobs.remove(id); continue;
					}
					if (job.candidate == null) job.candidate = new Route(job.entity, job.search.path(), tick);
					Validation check = validate(job.candidate, live, budget, deadline);
					budget -= check.work;
					if (check.blocked) { jobs.remove(id); attempts.remove(id); states.put(id, "changed/blocked"); }
					else if (check.complete) { routes.put(id, job.candidate); states.put(id, "ready"); jobs.remove(id); }
				}
			}
		} catch (LinePathfinder.BudgetExceeded exhausted) {
			// Resource exhaustion suppresses unvalidated geometry; it must not turn into a direct line.
			for (LivingEntity entity : selected) if (!routes.containsKey(entity.getUUID())) states.put(entity.getUUID(), "collision budget");
		}
	}

	private Validation validate(Route route, WorldGeometry geometry, int budget, long deadline) {
		if (budget <= 0 || System.nanoTime() >= deadline) return new Validation(0, false, false);
		if (tick < route.nextCheck && route.checking == null) return new Validation(0, false, false);
		if (route.checking == null) {
			Point end = endpoint(route.entity, geometry);
			if (end == null || route.path.size() < 2) return new Validation(1, true, false);
			route.checking = new ArrayList<>(route.path);
			route.checking.set(0, point(mc.player.getEyePosition())); route.checking.set(route.checking.size() - 1, end);
			route.segment = 1;
		}
		int work = 0;
		while (route.segment < route.checking.size() && work < budget && System.nanoTime() < deadline) {
			work++;
			if (!geometry.clear(route.checking.get(route.segment - 1), route.checking.get(route.segment))) {
				route.checking = null; return new Validation(work, true, false);
			}
			route.segment++;
		}
		if (route.segment < route.checking.size()) return new Validation(work, false, false);
		route.visible = List.copyOf(route.checking); route.checking = null; route.nextCheck = tick + 1;
		// A blocked detached-camera connector does not invalidate the player's body route.
		if (work < budget && System.nanoTime() < deadline && !Freecam.isActive()) {
			var camera = mc.gameRenderer.mainCamera(); var look = camera.forwardVector();
			Point visual = point(camera.position().add(look.x() * 0.04, look.y() * 0.04, look.z() * 0.04));
			route.cameraLink = geometry.clear(visual, route.visible.get(0)); work++;
		}
		return new Validation(work, false, true);
	}

	private void selectTargets() {
		List<LivingEntity> entities = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) if (matches(entity) && mc.player.getEyePosition().distanceToSqr(body((LivingEntity) entity)) <= range.val() * range.val()) entities.add((LivingEntity) entity);
		entities.sort(Comparator.comparingDouble(e -> mc.player.getEyePosition().distanceToSqr(body(e))));
		selected = List.copyOf(entities.subList(0, Math.min(maxTargets.intVal(), entities.size())));
	}

	private boolean matches(Entity entity) {
		if (!(entity instanceof LivingEntity living) || entity == mc.player || entity.getUUID().equals(mc.player.getUUID())
				|| entity.isRemoved() || !living.isAlive() || living.isDeadOrDying() || entity instanceof ArmorStand) return false;
		if (entity instanceof Player player) {
			if (player.isSpectator()) return false;
			return targets.is("line_players") || targets.is("line_named") && names.contains(player.getGameProfile().name().toLowerCase(Locale.ROOT));
		}
		return targets.is("line_living") || targets.is("line_hostiles") && entity instanceof Enemy
				|| targets.is("line_passives") && !(entity instanceof Enemy);
	}

	private static Set<String> parseNames(String text) {
		Set<String> names = new HashSet<>();
		for (String name : text.split("[,，]")) if (!name.trim().isEmpty()) names.add(name.trim().toLowerCase(Locale.ROOT));
		return Set.copyOf(names);
	}
	private static Vec3 body(LivingEntity entity) { return entity.position().add(0, entity.getBbHeight() * 0.55, 0); }
	private static Point point(Vec3 vector) { return new Point(vector.x, vector.y, vector.z); }
	private static Vec3 vec(Point point) { return new Vec3(point.x(), point.y(), point.z()); }

	private static Point endpoint(LivingEntity entity, WorldGeometry geometry) {
		Vec3 body = body(entity);
		// Short entities or partly embedded feet can use their eye/top, never a fabricated ray through solid ground.
		for (Vec3 candidate : List.of(body, entity.getEyePosition(), new Vec3(body.x, entity.getBoundingBox().maxY - 0.05, body.z),
				new Vec3(body.x, entity.getBoundingBox().maxY + 0.1, body.z), new Vec3(body.x, entity.getBoundingBox().maxY + 0.5, body.z))) {
			Point point = point(candidate);
			if (geometry.clear(point, point)) return point;
		}
		return null;
	}

	@Override
	public void onRender3D(Render3D renderer) {
		if (!inGame() || selected.isEmpty()) return;
		renderer.begin();
		try {
			for (LivingEntity entity : selected) {
				if (!matches(entity)) continue;
				float fade = (float) Math.max(0.35, 1 - entity.distanceTo(mc.player) / Math.max(1, range.val()));
				int color = Anim.white(opacity.floatVal() / 100 * fade);
				if (mode.is("straight")) {
					Vec3 end = entity.getPosition(renderer.partialTick).add(0, entity.getBbHeight() * 0.55, 0);
					renderer.line(renderer.crosshair(), end, color, lineWidth.floatVal());
				} else {
					Route route = routes.get(entity.getUUID());
					if (route == null || route.visible.isEmpty()) continue;
					List<Point> path = route.visible;
					if (route.cameraLink && !Freecam.isActive()) {
						var look = renderer.cameraObject.forwardVector();
						renderer.line(renderer.camera.add(look.x() * 0.04, look.y() * 0.04, look.z() * 0.04), vec(path.get(0)), color, lineWidth.floatVal());
					}
					for (int segment = 1; segment < path.size(); segment++) renderer.line(vec(path.get(segment - 1)), vec(path.get(segment)), color, lineWidth.floatVal());
				}
			}
		} finally { renderer.end(); }
	}

	/** Loaded collision shapes only; all calls are from the client thread, never a worker or renderer. */
	private static final class WorldGeometry implements LinePathfinder.Collision {
		private static final double CLEARANCE = 0.035;
		private final ClientLevel level;
		private final Map<Long, List<AABB>> shapes = new HashMap<>();
		WorldGeometry(ClientLevel level) { this.level = level; }
		void beginTick() { shapes.clear(); }

		@Override
		public boolean clear(Point from, Point to) {
			double longest = Math.max(Math.abs(to.x() - from.x()), Math.max(Math.abs(to.y() - from.y()), Math.abs(to.z() - from.z())));
			int steps = Math.max(1, (int) Math.ceil(longest * 2));
			Set<Long> seen = new HashSet<>();
			for (int step = 0; step < steps; step++) {
				double a = step / (double) steps, b = (step + 1d) / steps;
				double ax = from.x() + (to.x() - from.x()) * a, bx = from.x() + (to.x() - from.x()) * b;
				double ay = from.y() + (to.y() - from.y()) * a, by = from.y() + (to.y() - from.y()) * b;
				double az = from.z() + (to.z() - from.z()) * a, bz = from.z() + (to.z() - from.z()) * b;
				for (int x = (int) Math.floor(Math.min(ax, bx) - CLEARANCE); x <= Math.floor(Math.max(ax, bx) + CLEARANCE); x++)
					for (int y = (int) Math.floor(Math.min(ay, by) - CLEARANCE); y <= Math.floor(Math.max(ay, by) + CLEARANCE); y++)
						for (int z = (int) Math.floor(Math.min(az, bz) - CLEARANCE); z <= Math.floor(Math.max(az, bz) + CLEARANCE); z++) {
							long key = BlockPos.asLong(x, y, z);
							if (!seen.add(key)) continue;
							BlockPos position = new BlockPos(x, y, z);
							if (y < level.getMinY() || y > level.getMaxY() || !level.hasChunkAt(position)) return false;
							List<AABB> boxes = shapes.get(key);
							if (boxes == null) {
								if (shapes.size() >= 32768) throw new LinePathfinder.BudgetExceeded("collision sample cache budget reached");
								var state = level.getBlockState(position);
								if (state.isAir()) boxes = List.of();
								else {
									List<AABB> translated = new ArrayList<>();
									for (AABB box : state.getCollisionShape(level, position, CollisionContext.empty()).toAabbs()) translated.add(box.move(x, y, z));
									boxes = List.copyOf(translated);
								}
								shapes.put(key, boxes);
							}
							for (AABB box : boxes) if (LinePathfinder.intersects(from, to, box.minX - CLEARANCE, box.minY - CLEARANCE, box.minZ - CLEARANCE,
									box.maxX + CLEARANCE, box.maxY + CLEARANCE, box.maxZ + CLEARANCE)) return false;
							}
			}
			return true;
		}
	}
}
