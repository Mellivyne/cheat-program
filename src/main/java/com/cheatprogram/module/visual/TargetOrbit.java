package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.OrbitBrackets;
import com.cheatprogram.render.Render3D;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A white, rotating world-space target decoration; it never changes entity movement or attacks. */
public final class TargetOrbit extends Module {
	private static final double TURN = Math.PI * 2;
	private static final double SPEED_BOUNDARY = 180;
	private final ModeSetting targets = add(new ModeSetting("targets", "orbit_all", "line_players", "line_passives", "line_hostiles", "orbit_all"));
	private final NumberSetting range = add(new NumberSetting("range", 16, 1, 64, 1));
	private final NumberSetting maxTargets = add(new NumberSetting("maxTargets", 8, 1, 32, 1));
	private final NumberSetting fastSpeed = add(new NumberSetting("fastSpeed", 360, SPEED_BOUNDARY, 720, 10, "°/s"));
	private final NumberSetting slowSpeed = add(new NumberSetting("slowSpeed", 90, 10, SPEED_BOUNDARY, 5, "°/s"));
	private final NumberSetting size = add(new NumberSetting("size", 100, 50, 180, 5, "%"));
	private final NumberSetting opacity = add(new NumberSetting("opacity", 90, 10, 100, 5, "%"));
	private final NumberSetting thickness = add(new NumberSetting("thickness", 100, 25, 200, 5, "%"));
	private final BoolSetting glow = add(new BoolSetting("glow", true));
	private final NumberSetting glowStrength = add(new NumberSetting("glowStrength", 35, 0, 100, 5, "%")).visibleWhen(glow::on);
	private final NumberSetting glowWidth = add(new NumberSetting("glowWidth", 100, 25, 300, 5, "%")).visibleWhen(glow::on);
	private final BoolSetting throughWalls = add(new BoolSetting("throughWalls", false));
	private ClientLevel world;
	private LivingEntity combatTarget;
	private List<LivingEntity> displayed = List.of();
	private final Map<UUID, Double> phases = new HashMap<>();
	private int combatTicks;
	private long lastFrame;

	public TargetOrbit() { super("targetorbit", Category.VISUAL); }
	@Override public String suffix() { return targets.display() + ", " + displayed.size(); }
	@Override public void onEnable() { clear(); }
	@Override public void onDisable() { clear(); }
	private void clear() {
		world = null; displayed = List.of(); phases.clear(); combatTarget = null; combatTicks = 0;
		lastFrame = System.nanoTime();
	}

	private boolean matches(Entity entity) {
		if (!(entity instanceof LivingEntity living) || entity == mc.player || entity instanceof ArmorStand
				|| entity.level() != mc.level || entity.isRemoved() || !living.isAlive() || living.isDeadOrDying()
				|| entity.isInvisible() || !mc.level.hasChunkAt(entity.blockPosition())) return false;
		if (entity instanceof Player player) return !player.isSpectator() && (targets.is("line_players") || targets.is("orbit_all"));
		return targets.is("orbit_all") || targets.is("line_passives") && !(entity instanceof Enemy)
				|| targets.is("line_hostiles") && entity instanceof Enemy;
	}
	private Vec3 camera() {
		var camera = mc.gameRenderer.getMainCamera();
		return camera.isInitialized() ? camera.getPosition() : mc.player.getEyePosition();
	}
	private boolean eligible(Entity entity, Vec3 eye) {
		return matches(entity) && eye.distanceToSqr(entity.position()) <= range.val() * range.val();
	}

	@Override public void onPostAttack(Entity entity) {
		if (!inGame() || !matches(entity)) return;
		if (world != mc.level) { clear(); world = mc.level; }
		combatTarget = (LivingEntity) entity; combatTicks = 40;
	}
	@Override public void onTick() {
		if (!inGame() || !mc.player.isAlive()) { clear(); return; }
		if (world != mc.level) { clear(); world = mc.level; }
		if (mc.isPaused()) return;
		Vec3 eye = camera();
		if (combatTicks > 0) combatTicks--;
		if (combatTicks == 0 || combatTarget != null && !eligible(combatTarget, eye)) combatTarget = null;
		LinkedHashSet<LivingEntity> selected = new LinkedHashSet<>();
		if (combatTarget != null) selected.add(combatTarget);
		if (eligible(mc.crosshairPickEntity, eye)) selected.add((LivingEntity) mc.crosshairPickEntity);
		List<LivingEntity> candidates = new ArrayList<>();
		for (Entity entity : mc.level.entitiesForRendering()) {
			if (eligible(entity, eye)) candidates.add((LivingEntity) entity);
		}
		// A small preference keeps the last few visible entities stable at the display limit.
		candidates.sort(Comparator.comparingDouble((LivingEntity entity) -> eye.distanceTo(entity.position()) - (displayed.contains(entity) ? .6 : 0))
				.thenComparingInt(Entity::getId));
		selected.addAll(candidates);
		displayed = selected.stream().limit(maxTargets.intVal()).toList();
		var ids = displayed.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());
		phases.keySet().retainAll(ids);
		for (LivingEntity entity : displayed) phases.putIfAbsent(entity.getUUID(), 0d);
	}

	/** Two complete revolutions; consume leftover time at every speed boundary without an angle jump. */
	private double advance(double phase, double seconds) {
		double remaining = seconds;
		while (remaining > 0) {
			boolean firstTurn = phase < TURN;
			double boundary = firstTurn ? TURN : TURN * 2;
			double angularSpeed = Math.toRadians(firstTurn ? fastSpeed.val() : slowSpeed.val());
			double toBoundary = (boundary - phase) / angularSpeed;
			if (remaining < toBoundary) {
				phase += remaining * angularSpeed;
				return phase;
			}
			remaining -= toBoundary;
			phase = firstTurn ? TURN : 0;
		}
		return phase;
	}

	@Override public void onRender3D(Render3D renderer) {
		long now = System.nanoTime();
		double dt = lastFrame == 0 ? 0 : Math.min(0.15, Math.max(0, (now - lastFrame) / 1_000_000_000.0));
		lastFrame = now;
		if (!inGame() || world != mc.level || !mc.player.isAlive()) return;
		var look = renderer.cameraObject.getLookVector();
		for (LivingEntity target : displayed) {
			if (!eligible(target, renderer.camera)) continue;
			double phase = phases.getOrDefault(target.getUUID(), 0d);
			if (!mc.isPaused()) { phase = advance(phase, dt); phases.put(target.getUUID(), phase); }
			Vec3 anchor = target.getPosition(renderer.partialTick).add(0, target.getBbHeight() * 0.52, 0);
			if (anchor.subtract(renderer.camera).dot(new Vec3(look.x(), look.y(), look.z())) <= 0.05) continue;
			double radius = Math.min(3.5, Math.max(0.38, Math.max(target.getBbHeight() * 0.63, target.getBbWidth() * 0.8) + 0.08)) * size.fraction();
			OrbitBrackets.draw(renderer, anchor, radius, phase, opacity.fraction(), thickness.fraction(),
					glow.on() ? glowStrength.fraction() : 0, glowWidth.fraction(), throughWalls.on());
		}
	}
}
