package com.cheatprogram.util;

import com.cheatprogram.core.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Predicate;

/** Target selection shared by combat modules, driven by the "Targets" client settings. */
public final class Targets {
	private static final Minecraft MC = Minecraft.getInstance();

	private Targets() {
	}

	/** True for the local player and, in single player, its server-side twin. */
	public static boolean isLocalPlayer(Object entity) {
		LocalPlayer p = MC.player;
		if (p == null) return false;
		if (entity == p) return true;
		return entity instanceof ServerPlayer sp && MC.hasSingleplayerServer() && sp.getUUID().equals(p.getUUID());
	}

	public static boolean isValid(Entity e) {
		if (MC.player == null || MC.level == null || e == null || e == MC.player || e.isRemoved()
				|| e.level() != MC.level || !e.isAttackable() || !(e instanceof LivingEntity living)
				|| !living.isAlive() || living.isDeadOrDying()) return false;
		if (e instanceof ArmorStand) return false;
		ClientSettings s = ClientSettings.INSTANCE;
		if (s == null) return false;
		if (e.isInvisible() && !s.targetInvisible.on()) return false;
		if (e instanceof Player p) {
			if (p.isSpectator() || !s.targetPlayers.on()) return false;
			return true;
		}
		if (e instanceof Enemy) return s.targetHostiles.on();
		return s.targetPassives.on();
	}

	/** Distance from the player's eyes to the closest point of the entity's hitbox. */
	public static double distance(Entity e) {
		Vec3 eye = MC.player.getEyePosition();
		AABB box = e.getBoundingBox();
		return Math.sqrt(box.distanceToSqr(eye));
	}

	/** Point on the hitbox closest to the player's eye line, used for aiming. */
	public static Vec3 aimPoint(Entity e) {
		Vec3 eye = MC.player.getEyePosition();
		AABB box = e.getBoundingBox();
		double height = box.maxY - box.minY;
		double y = Mth.clamp(eye.y, box.minY + Math.min(0.15, height * 0.25), box.maxY - Math.min(0.1, height * 0.25));
		return new Vec3((box.minX + box.maxX) / 2, y, (box.minZ + box.maxZ) / 2);
	}

	/** Yaw/pitch that look from the player's eyes at a point. */
	public static float[] rotationTo(Vec3 point) {
		Vec3 eye = MC.player.getEyePosition();
		double dx = point.x - eye.x;
		double dy = point.y - eye.y;
		double dz = point.z - eye.z;
		float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		return new float[]{yaw, Mth.clamp(pitch, -90f, 90f)};
	}

	/** Angle in degrees between the view direction and the direction to the entity. */
	public static float angleTo(Entity e) {
		float[] rot = rotationTo(aimPoint(e));
		float dyaw = Mth.wrapDegrees(rot[0] - MC.player.getYRot());
		float dpitch = rot[1] - MC.player.getXRot();
		return (float) Math.sqrt(dyaw * dyaw + dpitch * dpitch);
	}

	/** Whether an already selected attack target still belongs to this world and meets the filters. */
	public static boolean canAttackTarget(Entity e, double range, double fov, boolean throughWalls) {
		return selectable(e, range, fov) && e.isPickable() && MC.level.getEntity(e.getId()) == e
				&& (throughWalls || clearAimRay(e));
	}

	private static boolean selectable(Entity e, double range, double fov) {
		return isValid(e) && distance(e) <= range && (fov >= 360 || angleTo(e) <= fov / 2);
	}

	private static boolean clearAimRay(Entity e) {
		return MC.level.clip(new ClipContext(MC.player.getEyePosition(), aimPoint(e),
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player)).getType() == HitResult.Type.MISS;
	}

	/**
	 * Best target inside the range and field of view. AimAssist keeps its original eye-to-eye visibility check.
	 *
	 * @param priority "distance", "health" or "angle"
	 */
	public static LivingEntity best(double range, double fov, String priority, boolean throughWalls) {
		return best(range, fov, priority, throughWalls, false);
	}

	/** Attack candidates use a clear ray to the same point used for rotation. */
	public static LivingEntity bestAttackTarget(double range, double fov, String priority, boolean throughWalls) {
		return best(range, fov, priority, throughWalls, true);
	}

	/** Optional caller-specific eligibility without changing the other combat modules' target filters. */
	public static LivingEntity bestAttackTarget(double range, double fov, String priority, boolean throughWalls,
			Predicate<LivingEntity> filter) {
		return best(range, fov, priority, throughWalls, true, filter);
	}

	private static LivingEntity best(double range, double fov, String priority, boolean throughWalls, boolean attack) {
		return best(range, fov, priority, throughWalls, attack, entity -> true);
	}

	private static LivingEntity best(double range, double fov, String priority, boolean throughWalls, boolean attack,
			Predicate<LivingEntity> filter) {
		if (MC.player == null || MC.level == null) return null;
		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;
		// Wide aura discovery uses the world's spatial entity query, not a full render-list scan.
		Iterable<Entity> candidates = attack
				? MC.level.getEntities(MC.player, MC.player.getBoundingBox().inflate(range), entity -> entity instanceof LivingEntity)
				: MC.level.entitiesForRendering();
		for (Entity e : candidates) {
			if (!selectable(e, range, fov)) continue;
			if (attack && !e.isPickable()) continue;
			if (!filter.test((LivingEntity) e)) continue;
			if (!throughWalls && !(attack ? clearAimRay(e) : MC.player.hasLineOfSight(e))) continue;
			LivingEntity living = (LivingEntity) e;
			double score = switch (priority) {
				case "health" -> living.getHealth() + living.getAbsorptionAmount();
				case "angle" -> angleTo(e);
				default -> distance(e);
			};
			if (score < bestScore) {
				bestScore = score;
				best = living;
			}
		}
		return best;
	}
}
