package com.cheatprogram.util;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.movement.AirWalk;
import com.cheatprogram.module.movement.FlatFlight;
import com.cheatprogram.module.utility.Blink;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.module.world.Scaffold;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** A building pose independent of the mouse, camera and movement input. */
public final class ScaffoldRotations {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final long LEASE_NANOS = 250_000_000L;
	private static final long RELEASE_NANOS = 240_000_000L;
	private static final long ACQUIRE_NANOS = 120_000_000L;
	private static LocalPlayer owner;
	private static ClientLevel world;
	private static boolean controlled;
	private static float yaw, pitch, previousYaw, previousPitch, bodyYaw, previousBodyYaw;
	private static float renderedYaw, renderedPitch, renderedBody;
	private static float releaseYaw, releasePitch, releaseBody;
	private static long updatedAt, releasedAt, renderedAt, acquireAt;
	private static int updatedTick = Integer.MIN_VALUE;

	private ScaffoldRotations() { }

	/** Degrees per client tick, matching Scaffold's jump/turn state machine. */
	public static void update(float targetYaw, float targetPitch, double degreesPerTick) {
		if (!contextAllowed() || !Float.isFinite(targetYaw) || !Float.isFinite(targetPitch)) { clear(); return; }
		LocalPlayer player = MC.player;
		long now = System.nanoTime();
		boolean fresh = !controlled || owner != player || world != MC.level || now - updatedAt > LEASE_NANOS;
		if (fresh) {
			boolean keepRendered = owner == player && world == MC.level && now - renderedAt < 150_000_000L;
			owner = player; world = MC.level;
			yaw = previousYaw = keepRendered ? renderedYaw : player.getYRot();
			pitch = previousPitch = keepRendered ? renderedPitch : player.getXRot();
			bodyYaw = previousBodyYaw = keepRendered ? renderedBody : player.yBodyRot;
			updatedTick = Integer.MIN_VALUE;
			releasedAt = 0;
			acquireAt = now;
		}
		controlled = true;
		updatedAt = now;
		// Pre-move, post-move and several placements may share a tick. Never multiply turning speed.
		if (updatedTick == player.tickCount) return;
		updatedTick = player.tickCount;
		previousYaw = yaw; previousPitch = pitch; previousBodyYaw = bodyYaw;
		targetPitch = Mth.clamp(targetPitch, -90f, 90f);
		float yawError = Mth.wrapDegrees(targetYaw - yaw), pitchError = targetPitch - pitch;
		float error = (float) Math.hypot(yawError, pitchError);
		float maximum = (float) Math.max(1, Math.min(180, Double.isFinite(degreesPerTick) ? degreesPerTick : 60));
		float step = Math.min(error, Math.min(maximum, error * (1f - (float) Math.exp(-.9))));
		if (error < .035f) { yaw += yawError; pitch = targetPitch; }
		else if (error > 0) { yaw += yawError * step / error; pitch += pitchError * step / error; }
		pitch = Mth.clamp(pitch, -90f, 90f);
		// A small torso lag makes looking down/back feel articulated instead of rotating a rigid model.
		float bodyError = Mth.wrapDegrees(yaw - bodyYaw);
		float bodyStep = bodyError * (1f - (float) Math.exp(-.55));
		bodyYaw += Mth.clamp(bodyStep, -maximum, maximum);
	}

	public static void aimAt(Vec3 point, double degreesPerTick) {
		if (MC.player == null || point == null || !finite(point)) { clear(); return; }
		Vec3 delta = point.subtract(MC.player.getEyePosition());
		float targetYaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90f;
		float targetPitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
		update(targetYaw, targetPitch, degreesPerTick);
	}

	/** Preserve the visible placement pose without adding another angular step in the same tick. */
	public static void placed(Vec3 point) { aimAt(point, 60); }

	/** A real visible point on this exact face, within the server's interaction attribute. */
	public static Vec3 visibleFacePoint(BlockPos neighbor, Direction face, double reach) {
		if (MC.player == null || MC.level == null || neighbor == null || face == null) return null;
		if (!MC.level.hasChunkAt(neighbor) || !MC.level.getWorldBorder().isWithinBounds(neighbor)) return null;
		Vec3 normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
		Vec3 centre = Vec3.atCenterOf(neighbor).add(normal.scale(.5));
		Vec3 eye = MC.player.getEyePosition();
		Vec3 near = new Vec3(face.getAxis() == Direction.Axis.X ? centre.x : Mth.clamp(eye.x, neighbor.getX() + .08, neighbor.getX() + .92),
				face.getAxis() == Direction.Axis.Y ? centre.y : Mth.clamp(eye.y, neighbor.getY() + .08, neighbor.getY() + .92),
				face.getAxis() == Direction.Axis.Z ? centre.z : Mth.clamp(eye.z, neighbor.getZ() + .08, neighbor.getZ() + .92));
		for (Vec3 point : new Vec3[] {near, centre}) {
			if (!ServerActionGate.withinReach(eye.distanceToSqr(point), reach)) continue;
			var visible = MC.level.clip(new ClipContext(eye, point.subtract(normal.scale(.001)),
					ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
			if (visible.getType() != HitResult.Type.BLOCK || visible.isInside()
					|| !visible.getBlockPos().equals(neighbor) || visible.getDirection() != face) continue;
			// The packet carries float angles. Validate that actual look ray, not just a double
			// segment ending at an ideal face point, before constructing the use-item hit result.
			Vec3 delta = visible.getLocation().subtract(eye);
			float hitYaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90f;
			float hitPitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
			var packetHit = MC.level.clip(new ClipContext(eye, eye.add(Vec3.directionFromRotation(hitPitch, hitYaw).scale(reach)),
					ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
			if (packetHit.getType() == HitResult.Type.BLOCK && !packetHit.isInside()
					&& packetHit.getBlockPos().equals(neighbor) && packetHit.getDirection() == face
					&& ServerActionGate.withinReach(eye.distanceToSqr(packetHit.getLocation()), reach))
				return packetHit.getLocation();
		}
		return null;
	}


	public static InteractionLook.MovementAim movementAim() {
		if (!active() || !ModuleManager.on(Scaffold.class)) return null;
		Scaffold scaffold = ModuleManager.get(Scaffold.class);
		return scaffold == null ? null : scaffold.movementAim(yaw, pitch);
	}

	/** Use exactly the transmitted float ray, after movement changed the eye position. */
	public static Vec3 publishedFacePoint(BlockPos neighbor, Direction face, double reach) {
		if (!InteractionLook.publishedThisTick("scaffold") || MC.player == null || MC.level == null
				|| neighbor == null || face == null || !MC.level.hasChunkAt(neighbor) || !MC.level.getWorldBorder().isWithinBounds(neighbor)
				|| !Double.isFinite(reach) || reach <= 0) return null;
		Vec3 eye = MC.player.getEyePosition();
		Vec3 end = eye.add(Vec3.directionFromRotation(InteractionLook.publishedPitch(), InteractionLook.publishedYaw()).scale(reach));
		var hit = MC.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
		return hit.getType() == HitResult.Type.BLOCK && !hit.isInside() && hit.getBlockPos().equals(neighbor)
				&& hit.getDirection() == face && ServerActionGate.withinReach(eye.distanceToSqr(hit.getLocation()), reach)
				? hit.getLocation() : null;
	}

	public static boolean active() {
		return controlled && owner == MC.player && world == MC.level && contextAllowed()
				&& System.nanoTime() - updatedAt <= LEASE_NANOS;
	}

	// A silent model pose must not replace the yaw used for vanilla movement/input validation.
	public static float packetYaw(float original) { return original; }
	public static float packetPitch(float original) { return original; }
	public static void onServerCorrection() { reset(); }

	/** Stop network ownership immediately and ease only the rendered model back to its vanilla pose. */
	public static void clear() {
		if (!controlled) return;
		controlled = false;
		updatedTick = Integer.MIN_VALUE;
		if (owner != MC.player || world != MC.level || owner == null || !owner.isAlive()) { reset(); return; }
		long now = System.nanoTime();
		boolean useRendered = now - renderedAt < 150_000_000L;
		releaseYaw = useRendered ? renderedYaw : yaw;
		releasePitch = useRendered ? renderedPitch : pitch;
		releaseBody = useRendered ? renderedBody : bodyYaw;
		releasedAt = now;
	}

	/** Also called outside enabled-module ticks, so leaving a world cannot leave a stale pose behind. */
	public static void tickLifecycle() {
		InteractionLook.restoreSyncedLook();
		if (owner != MC.player || world != MC.level || owner == null || !owner.isAlive() || exclusiveBodyOwner()) { reset(); return; }
		if (controlled && !active()) clear();
		if (releasedAt != 0 && System.nanoTime() - releasedAt >= RELEASE_NANOS) releasedAt = 0;
	}

	/** Returns whether a pose was applied; first-person hands and camera never read these values. */
	public static boolean applyRenderPose(AbstractClientPlayer player, AvatarRenderState state, float partialTick) {
		if (player != owner || player != MC.player || world != MC.level || !player.isAlive() || exclusiveBodyOwner()) return false;
		float modelYaw, modelPitch, modelBody;
		long now = System.nanoTime();
		float originalHead = state.bodyRot + (state.isUpsideDown ? -state.yRot : state.yRot);
		float originalPitch = state.isUpsideDown ? -state.xRot : state.xRot;
		if (active()) {
			float alpha = Mth.clamp(partialTick, 0, 1);
			modelYaw = Mth.rotLerp(alpha, previousYaw, yaw);
			modelPitch = Mth.lerp(alpha, previousPitch, pitch);
			modelBody = Mth.rotLerp(alpha, previousBodyYaw, bodyYaw);
			// When an attack pose releases ownership, acquire from vanilla instead of exposing a hidden jump.
			if (now - renderedAt > 150_000_000L) acquireAt = now;
			float acquire = smooth((now - acquireAt) / (float) ACQUIRE_NANOS);
			modelYaw = Mth.rotLerp(acquire, originalHead, modelYaw);
			modelPitch = Mth.lerp(acquire, originalPitch, modelPitch);
			modelBody = Mth.rotLerp(acquire, state.bodyRot, modelBody);
		} else if (!controlled && releasedAt != 0) {
			float t = (now - releasedAt) / (float) RELEASE_NANOS;
			if (t >= 1) { releasedAt = 0; return false; }
			float eased = smooth(t);
			modelYaw = Mth.rotLerp(eased, releaseYaw, originalHead);
			modelPitch = Mth.lerp(eased, releasePitch, originalPitch);
			modelBody = Mth.rotLerp(eased, releaseBody, state.bodyRot);
		} else return false;
		renderedYaw = modelYaw; renderedPitch = modelPitch; renderedBody = modelBody; renderedAt = now;
		state.bodyRot = modelBody;
		state.yRot = Mth.wrapDegrees(modelYaw - modelBody) * (state.isUpsideDown ? -1 : 1);
		state.xRot = modelPitch * (state.isUpsideDown ? -1 : 1);
		return true;
	}

	private static boolean contextAllowed() {
		return MC.player != null && MC.level != null && MC.gameMode != null && MC.getConnection() != null
				&& ModuleManager.on(Scaffold.class) && MC.screen == null && MC.getOverlay() == null
				&& !MC.isPaused() && MC.isWindowActive() && MC.player.isAlive() && !MC.player.isDeadOrDying()
				&& !MC.player.isSpectator() && !MC.player.isPassenger() && !MC.player.isUsingItem()
				&& !MC.player.getAbilities().flying && !MC.player.isFallFlying()
				&& !MC.gameMode.isDestroying() && !MC.options.keyUse.isDown() && !exclusiveBodyOwner();
	}

	private static boolean exclusiveBodyOwner() { return Freecam.isActive() || AiController.isRunning() || AirWalk.ownsMotion() || FlatFlight.ownsMotion() || ModuleManager.on(Blink.class); }
	private static float smooth(float value) { float t = Mth.clamp(value, 0, 1); return t * t * (3 - 2 * t); }
	private static boolean finite(Vec3 point) { return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z); }
	private static void reset() {
		controlled = false; owner = null; world = null;
		updatedAt = releasedAt = renderedAt = acquireAt = 0;
		updatedTick = Integer.MIN_VALUE;
	}
}
