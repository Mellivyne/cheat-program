package com.cheatprogram.util;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.utility.Blink;
import com.cheatprogram.module.movement.FlatFlight;
import com.cheatprogram.module.visual.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Independent attack orientation: network and third-person pose never overwrite camera angles. */
public final class AuraRotations {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final long LEASE_NANOS = 250_000_000L;
	private static final long RELEASE_NANOS = 220_000_000L;
	private static LocalPlayer owner;
	private static ClientLevel world;
	private static ClientPacketListener connection;
	private static boolean controlled, silent, networkRequested;
	private static float yaw, pitch, previousYaw, previousPitch;
	private static float bodyYaw, previousBodyYaw;
	private static float aimYaw, aimPitch, aimBodyYaw, appliedYawOffset;
	private static long updatedAt, releasedAt, renderedAt;
	private static float releaseYaw, releasePitch, releaseBody;
	private static float renderedYaw, renderedPitch, renderedBody;
	private static int updatedTick = Integer.MIN_VALUE;
	private static long correctionRevision;
	private static AttackPose attackPose;
	private static boolean attackInProgress;
	private record AttackPose(LocalPlayer player, ClientLevel level, ClientPacketListener connection,
			long revision, float cameraYaw, float cameraPitch) { }

	private AuraRotations() { }

	/** Update once per client tick; rayHits deliberately waits for this smoothed direction to align. */
	public static void update(Vec3 point, double degreesPerSecond, boolean useSilent) {
		update(point, degreesPerSecond, useSilent, 0);
	}

	/**
	 * A smooth, signed turn offset overlays the independently tracked aim. Keeping the offset
	 * out of the aim integrator prevents a 180-degree turn from wrapping the return the wrong way.
	 */
	public static void update(Vec3 point, double degreesPerSecond, boolean useSilent, float yawOffset) {
		if (!contextAllowed() || point == null || !finite(point) || !Float.isFinite(yawOffset)) { clear(); return; }
		LocalPlayer player = MC.player;
		long now = System.nanoTime();
		boolean fresh = !controlled || owner != player || world != MC.level || connection != MC.getConnection() || silent != useSilent
				|| now - updatedAt > LEASE_NANOS;
		if (fresh) {
			owner = player;
			world = MC.level;
			connection = MC.getConnection();
			yaw = previousYaw = player.getYRot();
			pitch = previousPitch = player.getXRot();
			bodyYaw = previousBodyYaw = player.yBodyRot;
			aimYaw = yaw;
			aimPitch = pitch;
			aimBodyYaw = bodyYaw;
			appliedYawOffset = 0;
			updatedTick = Integer.MIN_VALUE;
			renderedAt = releasedAt = 0;
		}
		controlled = true;
		silent = useSilent;
		// Multiple checks in an attack tick must not advance the angular integration twice.
		if (updatedTick == player.tickCount) { updatedAt = now; return; }
		updatedTick = player.tickCount;
		float seconds = fresh ? .05f : Mth.clamp((now - updatedAt) / 1_000_000_000f, .001f, .075f);
		updatedAt = now;
		previousYaw = yaw;
		previousPitch = pitch;
		previousBodyYaw = bodyYaw;
		if (!silent) {
			// Subtract only the offset already applied by us. New mouse movement still belongs to
			// the user, and the base aim never accumulates a full turn on every follow-mode tick.
			aimYaw = player.getYRot() - appliedYawOffset;
			aimPitch = player.getXRot();
		}
		Vec3 delta = point.subtract(player.getEyePosition());
		float targetYaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90f;
		float targetPitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))), -90f, 90f);
		float yawError = Mth.wrapDegrees(targetYaw - aimYaw);
		float pitchError = targetPitch - aimPitch;
		float error = (float) Math.hypot(yawError, pitchError);
		float speed = (float) Math.max(1, Double.isFinite(degreesPerSecond) ? degreesPerSecond : 540);
		float ease = 1f - (float) Math.exp(-16f * seconds);
		float step = Math.min(error, Math.min(speed * seconds, error * ease));
		if (error < .025f) { aimYaw += yawError; aimPitch = targetPitch; }
		else if (error > 0) { aimYaw += yawError * step / error; aimPitch += pitchError * step / error; }
		aimPitch = Mth.clamp(aimPitch, -90f, 90f);
		// Torso follows the head with a little lag instead of rotating the entire model as a rigid block.
		float bodyError = Mth.wrapDegrees(aimYaw - aimBodyYaw);
		float bodyStep = bodyError * (1f - (float) Math.exp(-11f * seconds));
		// Even activation immediately after a large mouse turn cannot snap the whole body in one tick.
		aimBodyYaw += Mth.clamp(bodyStep, -speed * seconds, speed * seconds);
		yaw = aimYaw + yawOffset;
		pitch = aimPitch;
		bodyYaw = aimBodyYaw + yawOffset;
		appliedYawOffset = yawOffset;
		// The offset is signed and continuous. Shortest-arc wrapping would reverse exact 180°
		// steps at high turn speeds, or collapse a full spin into a stationary camera.
		if (!silent) player.turn((yaw - player.getYRot()) / .15, (pitch - player.getXRot()) / .15);
	}

	/** Uses the actual angular state and the first intercepted entity, without replacing the mouse pick. */
	public static boolean rayHits(Entity target, double range, boolean throughWalls) {
		if (!active() || target == null || target.isRemoved() || target.level() != world
				|| !target.isPickable() || target.isSpectator() || world.getEntity(target.getId()) != target
				|| !Double.isFinite(range) || range <= 0) return false;
		Vec3 eye = owner.getEyePosition();
		boolean published = !MC.hasSingleplayerServer() && silent;
		if (published && !InteractionLook.publishedThisTick("aura")) return false;
		Vec3 direction = Vec3.directionFromRotation(published ? InteractionLook.publishedPitch() : silent ? pitch : owner.getXRot(),
				published ? InteractionLook.publishedYaw() : silent ? yaw : owner.getYRot());
		Vec3 end = eye.add(direction.scale(range));
		AABB targetBox = target.getBoundingBox();
		Vec3 hit = targetBox.contains(eye) ? eye : targetBox.clip(eye, end).orElse(null);
		if (hit == null) return false;
		double targetDistance = eye.distanceToSqr(hit);
		if (targetDistance > range * range + 1.0E-7) return false;
		if (!throughWalls) {
			var block = world.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, owner));
			if (block.getType() != HitResult.Type.MISS && eye.distanceToSqr(block.getLocation()) + 1.0E-7 < targetDistance) return false;
		}
		AABB search = new AABB(eye, end).inflate(1);
		for (Entity entity : world.getEntities(owner, search, e -> !e.isRemoved() && !e.isSpectator() && e.isPickable())) {
			if (entity == target) continue;
			AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
			Vec3 intercept = box.contains(eye) ? eye : box.clip(eye, end).orElse(null);
			if (intercept != null && eye.distanceToSqr(intercept) + 1.0E-7 < targetDistance) return false;
		}
		return true;
	}

	/** Nearby combat may own the next real movement rotation; distant HUD tracking never does. */
	public static void requestNetwork(boolean requested) { networkRequested = requested; }
	public static boolean requestsNetwork() { return networkRequested && active() && silent; }
	public static InteractionLook.MovementAim movementAim() {
		return requestsNetwork() ? new InteractionLook.MovementAim(yaw, pitch, MC.player.getYRot()) : null;
	}

	/** TAIL attacks use the direction vanilla already transmitted during this body's movement. */
	public static boolean beforeAttack() {
		if (!active() || MC.getConnection() == null || attackPose != null) return false;
		if (silent && !MC.hasSingleplayerServer() && !InteractionLook.publishedThisTick("aura")) return false;
		LocalPlayer player = owner;
		if (!Double.isFinite(player.getX()) || !Double.isFinite(player.getY()) || !Double.isFinite(player.getZ())) return false;
		attackPose = new AttackPose(player, world, MC.getConnection(), correctionRevision, player.getYRot(), player.getXRot());
		if (silent && InteractionLook.publishedThisTick("aura")) {
			player.setYRot(InteractionLook.publishedYaw()); player.setXRot(InteractionLook.publishedPitch());
		}
		attackInProgress = true;
		return true;
	}

	/** Finish the scope without reversing the action direction inside the same network tick. */
	public static void afterAttack() {
		attackInProgress = false;
		AttackPose pose = attackPose; attackPose = null;
		if (pose != null && pose.revision == correctionRevision && pose.player == MC.player
				&& pose.level == MC.level && pose.connection == MC.getConnection()) {
			pose.player.setYRot(pose.cameraYaw); pose.player.setXRot(pose.cameraPitch);
		}
	}

	/** A real/manual attack takes priority over a held automatic direction. */
	public static void beforeManualAttack() {
		if (!attackInProgress) { attackPose = null; InteractionLook.releaseForManualAction(); }
	}


	public static boolean active() {
		return controlled && owner == MC.player && world == MC.level && connection == MC.getConnection() && contextAllowed()
				&& System.nanoTime() - updatedAt <= LEASE_NANOS;
	}

	public static boolean silent() { return active() && silent; }
	/** Model priority also includes the brief return from a silent attack to the player's own pose. */
	public static boolean hasRenderPose() {
		return owner == MC.player && owner != null && world == MC.level && connection == MC.getConnection() && owner.isAlive()
				&& (silent() || !controlled && releasedAt != 0 && System.nanoTime() - releasedAt < RELEASE_NANOS);
	}
	public static float packetYaw(float original) { return original; }
	public static float packetPitch(float original) { return original; }

	/** The vanilla teleport acknowledgement has already run. Do not send the stale pre-correction pose. */
	public static void onServerCorrection() {
		correctionRevision++;
		attackPose = null;
		attackInProgress = false;
		controlled = false; networkRequested = false; owner = null; world = null; connection = null;
		updatedAt = releasedAt = renderedAt = 0;
		updatedTick = Integer.MIN_VALUE; appliedYawOffset = 0;
	}

	/** A lost target or ownership change adopts the visible turn as the next aim's starting point. */
	public static void cancelYawOffset() {
		if (!controlled || appliedYawOffset == 0) return;
		aimYaw = yaw;
		aimBodyYaw = bodyYaw;
		appliedYawOffset = 0;
	}

	/** Release tracking now; the network look returns through following ordinary body ticks. */
	public static void clear() {
		networkRequested = false;
		boolean wasControlled = controlled;
		boolean wasSilent = silent;
		controlled = false;
		if (wasControlled && wasSilent && owner == MC.player && world == MC.level && owner != null && owner.isAlive()) {
			long now = System.nanoTime();
			boolean useRendered = now - renderedAt < 150_000_000L;
			releaseYaw = useRendered ? renderedYaw : yaw;
			releasePitch = useRendered ? renderedPitch : pitch;
			releaseBody = useRendered ? renderedBody : bodyYaw;
			releasedAt = now;
		}
		updatedTick = Integer.MIN_VALUE;
	}

	/** Called globally even while KillAura is disabled or no world is loaded. */
	public static void tickLifecycle() {
		// Called at Minecraft.tick HEAD even while disabled. Movement release is owned by
		// InteractionLook and never inserts an extra reverse rotation packet here.
		if (!attackInProgress) attackPose = null;
		if (controlled && !active()) clear();
		if (owner != MC.player || world != MC.level || connection != MC.getConnection() || owner == null || !owner.isAlive()) {
			controlled = false;
			releasedAt = renderedAt = updatedAt = 0;
			owner = null;
			world = null;
			connection = null;
		} else if (releasedAt != 0 && System.nanoTime() - releasedAt >= RELEASE_NANOS) releasedAt = 0;
	}

	/** Mutate only the extracted model state: camera, eye direction and first-person hands remain vanilla. */
	public static void applyRenderPose(AbstractClientPlayer player, PlayerRenderState state, float partialTick) {
		if (player != owner || player != MC.player || world != MC.level || !player.isAlive()) return;
		float modelYaw, modelPitch, modelBody;
		if (silent()) {
			float alpha = Mth.clamp(partialTick, 0, 1);
			modelYaw = Mth.lerp(alpha, previousYaw, yaw);
			modelPitch = Mth.lerp(alpha, previousPitch, pitch);
			modelBody = Mth.lerp(alpha, previousBodyYaw, bodyYaw);
		} else if (!controlled && releasedAt != 0) {
			float t = Mth.clamp((System.nanoTime() - releasedAt) / (float) RELEASE_NANOS, 0, 1);
			if (t >= 1) { releasedAt = 0; return; }
			float eased = t * t * (3 - 2 * t);
			float originalHead = state.bodyRot + (state.isUpsideDown ? -state.yRot : state.yRot);
			modelYaw = Mth.rotLerp(eased, releaseYaw, originalHead);
			modelPitch = Mth.lerp(eased, releasePitch, state.isUpsideDown ? -state.xRot : state.xRot);
			modelBody = Mth.rotLerp(eased, releaseBody, state.bodyRot);
		} else return;
		renderedYaw = modelYaw;
		renderedPitch = modelPitch;
		renderedBody = modelBody;
		renderedAt = System.nanoTime();
		state.bodyRot = modelBody;
		state.yRot = Mth.wrapDegrees(modelYaw - modelBody) * (state.isUpsideDown ? -1 : 1);
		state.xRot = modelPitch * (state.isUpsideDown ? -1 : 1);
	}

	private static boolean contextAllowed() {
		return MC.player != null && MC.level != null && MC.gameMode != null && MC.getConnection() != null
				&& MC.screen == null && MC.getOverlay() == null && !MC.isPaused() && MC.isWindowActive()
				&& MC.player.isAlive() && !MC.player.isDeadOrDying() && !MC.player.isSpectator()
				&& !MC.player.isUsingItem() && !MC.gameMode.isDestroying() && !MC.options.keyUse.isDown()
				&& !Freecam.isActive() && !AiController.isRunning() && !ModuleManager.on(Blink.class) && !FlatFlight.usesVanillaGlide();
	}

	private static boolean finite(Vec3 point) { return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z); }
}
