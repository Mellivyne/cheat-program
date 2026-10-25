package com.cheatprogram.util;

import net.minecraft.core.BlockPos;

import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;

import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Observation-only mirrors of the 1.21.4 Mojang projectile update order. No projectile is created,
 * registered, ticked, or sent to a server. Random shot dispersion and future entity decisions are
 * intentionally not invented: this is the central launch ray, not a promise of a hit.
 */
public final class ProjectilePrediction {
	public enum Kind {
		BOW(EntityType.ARROW, .05, .6F), CROSSBOW(EntityType.ARROW, .05, .6F),
		TRIDENT(EntityType.TRIDENT, .05, .99F), PEARL(EntityType.ENDER_PEARL, .03, .8F);
		public final EntityType<?> type;
		public final double gravity;
		public final float waterInertia;
		Kind(EntityType<?> type, double gravity, float waterInertia) {
			this.type = type; this.gravity = gravity; this.waterInertia = waterInertia;
		}
		public boolean isArrow() { return this != PEARL; }
	}
	public record Shot(Kind kind, Vec3 origin, Vec3 velocity, ItemStack weapon, int useTicks) { }
	public record Result(Shot shot, List<Vec3> points, Entity hitEntity, BlockPos hitBlock,
			boolean limited, int simulatedTicks) {
		public Vec3 end() { return points.isEmpty() ? shot.origin() : points.getLast(); }
	}
	private record FluidSample(boolean water, Vec3 movement) { }
	private ProjectilePrediction() { }

	/** Uses the real body, including in free camera. Unused bows only preview full charge if requested. */
	public static Shot currentShot(Player player, boolean heldPreview, boolean crossbows, boolean tridents, boolean pearls) {
		if (player == null || !player.isAlive() || player.isSpectator()) return null;
		if (player.isUsingItem()) {
			return shotForStack(player, player.getUseItem(), player.getTicksUsingItem(), heldPreview, crossbows, tridents, pearls);
		}
		for (InteractionHand hand : InteractionHand.values()) {
			Shot shot = shotForStack(player, player.getItemInHand(hand), -1, heldPreview, crossbows, tridents, pearls);
			if (shot != null) return shot;
		}
		return null;
	}
	private static Shot shotForStack(Player player, ItemStack stack, int useTicks, boolean heldPreview,
			boolean crossbows, boolean tridents, boolean pearls) {
		Kind kind;
		float speed;
		boolean inheritMotion = true;
		if (stack.getItem() instanceof BowItem) {
			if (useTicks < 0 && !heldPreview || player.getProjectile(stack).isEmpty()) return null;
			float power = BowItem.getPowerForTime(useTicks < 0 ? 20 : useTicks);
			if (power < .1F) return null;
			kind = Kind.BOW; speed = power * 3.0F;
		} else if (crossbows && stack.getItem() instanceof CrossbowItem) {
			boolean loaded=CrossbowItem.isCharged(stack);
			// Fireworks accelerate and detonate differently; an arrow parabola would be misleading.
			if (!loaded || CrossbowItem.containsChargedProjectile(stack,Items.FIREWORK_ROCKET)) return null;
			kind = Kind.CROSSBOW; speed = 3.15F;
			// CrossbowItem.shootProjectile calls shoot(), not shootFromRotation(): no owner velocity.
			inheritMotion = false;
		} else if (tridents && stack.getItem() instanceof TridentItem) {
			if ((useTicks < 0 ? !heldPreview : useTicks < 10) || stack.getDamageValue()>=stack.getMaxDamage()-1
					|| EnchantmentHelper.getRiptide(stack) > 0) return null;
			kind = Kind.TRIDENT; speed = 2.5F;
		} else if (pearls && stack.is(Items.ENDER_PEARL)) {
			kind = Kind.PEARL; speed = 1.5f;
		} else return null;
		float yaw = player.getYRot() * Mth.DEG_TO_RAD;
		float pitch = player.getXRot() * Mth.DEG_TO_RAD;
		Vec3 direction = new Vec3(-Mth.sin(yaw) * Mth.cos(pitch), -Mth.sin(pitch), Mth.cos(yaw) * Mth.cos(pitch));
		Vec3 velocity = direction.normalize().scale(speed);
		if (inheritMotion) {
			Vec3 movement = player.getDeltaMovement();
			velocity = velocity.add(movement.x, player.onGround() ? 0 : movement.y, movement.z);
		}
		return new Shot(kind, new Vec3(player.getX(), player.getEyeY() - .1F, player.getZ()), velocity, stack, useTicks);
	}

	/**
	 * Simulates one tick per segment, clipping blocks before selecting the nearest entity, like
	 * AbstractArrow.stepMoveAndHit / ProjectileUtil. All chunk checks are read-only, never loads.
	 * The deadline bounds exceptional long paths; a limited path is never labelled as a hit.
	 */
	public static Result simulate(Level level, Player owner, Shot shot, int maxTicks, double maxDistance,
			double entityLeadTicks, long deadlineNanos) {
		List<Vec3> points = new ArrayList<>();
		Vec3 position = shot.origin(), velocity = shot.velocity();
		points.add(position);
		boolean water = false; // Newly constructed Mojang projectiles have not run Entity.baseTick yet.
		boolean leftOwner = false;
		for (int tick = 0; tick < Math.max(1, Math.min(400, maxTicks)); tick++) {
			if (System.nanoTime() >= deadlineNanos || !finite(position) || !finite(velocity))
				return result(shot, points, null, null, true, tick);
			Vec3 travel;
			if (shot.kind().isArrow()) {
				// AbstractArrow captures vec3 first; water drag changes the NEXT tick's movement.
				travel = velocity;
				if (water) velocity = velocity.scale(shot.kind().waterInertia);
			} else {
				// ThrowableProjectile reverses that order: gravity -> inertia -> segment -> baseTick.
				velocity = velocity.add(0, -shot.kind().gravity, 0).scale(water ? shot.kind().waterInertia : .99F);
				travel = velocity;
			}
			Vec3 next = position.add(travel);
			AABB swept = bounds(shot.kind(), position).expandTowards(travel).inflate(1);
			if (shot.origin().distanceToSqr(next) > maxDistance * maxDistance || !loaded(level, swept)
					|| next.y < level.getMinBuildHeight() - 32 || next.y > level.getMaxBuildHeight() + 128)
				return result(shot, points, null, null, true, tick);
			BlockHitResult block = level.clip(new ClipContext(position, next, ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE, owner));
			Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
			double nearest = position.distanceToSqr(end);
			Entity hitEntity = null;
			Vec3 entityHit = null;
			AABB search = entityLeadTicks <= 0 ? swept : swept.inflate(3);
			for (Entity entity : level.getEntities((Entity) null, search, Entity::canBeHitByProjectile)) {
				if (entity.isSpectator() || !leftOwner && entity.isPassengerOfSameVehicle(owner)) continue;
				if (shot.kind().isArrow() && entity instanceof Player other && !owner.canHarmPlayer(other)) continue;
				AABB box = entity.getBoundingBox().inflate(.3F); // ProjectileUtil's projectile margin, not cursor pick radius.
				if (entityLeadTicks > 0 && entity != owner) {
					Vec3 offset = entity.getDeltaMovement().scale(Math.min(tick + 1, entityLeadTicks));
					if (offset.lengthSqr() > 9) offset = offset.normalize().scale(3);
					box = box.move(offset);
				}
				var intersection = box.clip(position, end);
				if (intersection.isEmpty()) continue;
				double distance = position.distanceToSqr(intersection.get());
				if (distance <= nearest) { nearest = distance; hitEntity = entity; entityHit = intersection.get(); }
			}
			if (hitEntity != null) {
				points.add(entityHit);
				return result(shot, points, hitEntity, null, false, tick + 1);
			}
			points.add(end);
			if (block.getType() != HitResult.Type.MISS)
				return result(shot, points, null, block.getBlockPos().immutable(), false, tick + 1);
			position = next;
			if (shot.kind().isArrow()) {
				if (!water) velocity = velocity.scale(.99F);
				velocity = velocity.add(0, -shot.kind().gravity, 0);
			}
			if (!leftOwner) {
				AABB ownerCheck = bounds(shot.kind(), position).expandTowards(velocity).inflate(1);
				leftOwner = owner.getRootVehicle().getSelfAndPassengers()
						.filter(Entity::isPickable).noneMatch(entity -> ownerCheck.intersects(entity.getBoundingBox()));
			}
			FluidSample sample = fluid(level, bounds(shot.kind(), position), velocity);
			water = sample.water(); velocity = sample.movement();
		}
		return result(shot, points, null, null, true, maxTicks);
	}
	private static Result result(Shot shot, List<Vec3> points, Entity hit, BlockPos block, boolean limited, int ticks) {
		return new Result(shot, List.copyOf(points), hit, block, limited, ticks);
	}
	private static AABB bounds(Kind kind, Vec3 point) {
		double half = kind.type.getWidth() / 2;
		return new AABB(point.x - half, point.y, point.z - half, point.x + half, point.y + kind.type.getHeight(), point.z + half);
	}
	private static boolean finite(Vec3 point) {
		return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
	}
	private static boolean loaded(Level level, AABB box) {
		int minX = Mth.floor(box.minX) >> 4, maxX = Mth.floor(box.maxX) >> 4;
		int minZ = Mth.floor(box.minZ) >> 4, maxZ = Mth.floor(box.maxZ) >> 4;
		if ((long) (maxX - minX + 1) * (maxZ - minZ + 1) > 256) return false;
		for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) if (!level.hasChunk(x, z)) return false;
		return true;
	}
	/** Water volume and current mirror Entity.updateFluidHeightAndDoFluidPushing(WATER, .014). */
	private static FluidSample fluid(Level level, AABB bounds, Vec3 movement) {
		AABB box = bounds.deflate(.001);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		double depth = 0;
		boolean water = false;
		Vec3 flow = Vec3.ZERO;
		int samples = 0;
		for (int x = Mth.floor(box.minX); x < Mth.ceil(box.maxX); x++)
			for (int y = Mth.floor(box.minY); y < Mth.ceil(box.maxY); y++)
				for (int z = Mth.floor(box.minZ); z < Mth.ceil(box.maxZ); z++) {
					pos.set(x, y, z);
					FluidState state = level.getFluidState(pos);
					if (!state.is(FluidTags.WATER)) continue;
					double surface = y + state.getHeight(level, pos);
					if (surface < box.minY) continue;
					water = true; depth = Math.max(surface - box.minY, depth);
					Vec3 current = state.getFlow(level, pos);
					flow = flow.add(depth < .4 ? current.scale(depth) : current); samples++;
				}
		if (flow.lengthSqr() > 0) {
			flow = flow.scale(1d / Math.max(1, samples)).normalize().scale(.014);
			if (Math.abs(movement.x) < .003 && Math.abs(movement.z) < .003 && flow.length() < .0045)
				flow = flow.normalize().scale(.0045);
			movement = movement.add(flow);
		}
		return new FluidSample(water, movement);
	}
}
