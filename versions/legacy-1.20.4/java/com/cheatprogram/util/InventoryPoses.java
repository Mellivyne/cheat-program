package com.cheatprogram.util;

import com.cheatprogram.core.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import com.cheatprogram.compat.legacy.LegacyPlayerRenderPose;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Short visual-only inventory gestures; the player's camera, hands and network angles stay unchanged. */
public final class InventoryPoses {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final long LEASE_NANOS = 400_000_000L;
	private static final long RELEASE_NANOS = 200_000_000L;
	private static final long ACQUIRE_NANOS = 100_000_000L;
	private static final long SWING_NANOS = 280_000_000L;
	private static LocalPlayer player;
	private static ClientLevel world;
	private static Module source;
	private static Vec3 target;
	private static boolean controlled;
	private static float yaw, pitch, body, previousYaw, previousPitch, previousBody;
	private static float releaseYaw, releasePitch, releaseBody;
	private static float renderedYaw, renderedPitch, renderedBody;
	private static long operatedAt, acquireAt, releasedAt, renderedAt, swingAt;
	private static int updatedTick = Integer.MIN_VALUE;

	private InventoryPoses() { }

	/** Called after a real operation, rather than merely finding a potential slot or chest. */
	public static void operated(Module owner, String mode, Vec3 point, boolean swing) {
		if (owner == null || !owner.isEnabled() || !"silent".equals(mode) || !contextAllowed()
				|| point != null && !finite(point)) {
			if (source == owner) release();
			return;
		}
		long now = System.nanoTime();
		boolean fresh = !controlled || player != MC.player || world != MC.level || now - operatedAt > LEASE_NANOS;
		if (fresh) {
			boolean reuse = player == MC.player && world == MC.level && now - renderedAt < 150_000_000L;
			player = MC.player;
			world = MC.level;
			yaw = previousYaw = reuse ? renderedYaw : player.getYRot();
			pitch = previousPitch = reuse ? renderedPitch : player.getXRot();
			body = previousBody = reuse ? renderedBody : player.yBodyRot;
			updatedTick = Integer.MIN_VALUE;
			acquireAt = now;
			swingAt = 0;
		}
		source = owner;
		target = point;
		controlled = true;
		operatedAt = now;
		releasedAt = 0;
		// Multiple slots, including instant mode, keep one coherent gesture instead of resetting it.
		if (swing && (swingAt == 0 || now - swingAt >= SWING_NANOS)) swingAt = now;
		advance();
	}

	/** Called globally so module toggles, world changes and leaving a server cannot retain a pose. */
	public static void tickLifecycle() {
		if (player == null || player != MC.player || world != MC.level || !contextAllowed()) {
			reset();
			return;
		}
		long now = System.nanoTime();
		if (controlled && (source == null || !source.isEnabled() || now - operatedAt > LEASE_NANOS)) release();
		if (controlled) advance();
		if (releasedAt != 0 && now - releasedAt >= RELEASE_NANOS) reset();
	}

	public static boolean hasRenderPose() {
		// A lease may expire between client ticks; start the return immediately instead of dropping a frame.
		if (controlled && !active()) release();
		return player != null && player == MC.player && world == MC.level && contextAllowed()
				&& (active() || releasedAt != 0 && System.nanoTime() - releasedAt < RELEASE_NANOS);
	}

	/** Only extracted third-person model data changes; first-person rendering reads the real LocalPlayer. */
	public static boolean applyRenderPose(AbstractClientPlayer owner, LegacyPlayerRenderPose state, float partialTick) {
		if (owner != player || owner != MC.player || !hasRenderPose() || state.isUsingItem) return false;
		long now = System.nanoTime();
		float originalHead = state.bodyRot + (state.isUpsideDown ? -state.yRot : state.yRot);
		float originalPitch = state.isUpsideDown ? -state.xRot : state.xRot;
		float modelYaw, modelPitch, modelBody;
		if (active()) {
			float alpha = Float.isFinite(partialTick) ? Mth.clamp(partialTick, 0, 1) : 1;
			modelYaw = Mth.rotLerp(alpha, previousYaw, yaw);
			modelPitch = Mth.lerp(alpha, previousPitch, pitch);
			modelBody = Mth.rotLerp(alpha, previousBody, body);
			// Higher priority attacks/building may have hidden this gesture. Reacquire without a snap.
			if (now - renderedAt > 150_000_000L) acquireAt = now;
			float acquire = smooth((now - acquireAt) / (float) ACQUIRE_NANOS);
			modelYaw = Mth.rotLerp(acquire, originalHead, modelYaw);
			modelPitch = Mth.lerp(acquire, originalPitch, modelPitch);
			modelBody = Mth.rotLerp(acquire, state.bodyRot, modelBody);
		} else if (releasedAt != 0) {
			float amount = (now - releasedAt) / (float) RELEASE_NANOS;
			if (amount >= 1) return false;
			float eased = smooth(amount);
			modelYaw = Mth.rotLerp(eased, releaseYaw, originalHead);
			modelPitch = Mth.lerp(eased, releasePitch, originalPitch);
			modelBody = Mth.rotLerp(eased, releaseBody, state.bodyRot);
		} else return false;
		renderedYaw = modelYaw;
		renderedPitch = modelPitch;
		renderedBody = modelBody;
		renderedAt = now;
		state.bodyRot = modelBody;
		state.yRot = Mth.wrapDegrees(modelYaw - modelBody) * (state.isUpsideDown ? -1 : 1);
		state.xRot = modelPitch * (state.isUpsideDown ? -1 : 1);
		// Respect actual use/attack animations. This pulse never calls swing() or changes held items.
		if (swingAt != 0 && state.attackTime <= .001f && !state.swinging) {
			float progress = (now - swingAt) / (float) SWING_NANOS;
			if (progress >= 0 && progress < 1) {
				state.attackTime = Mth.clamp(progress, .001f, .999f);
				state.attackArm = player.getMainArm();
				state.swinging = true;
			}
		}
		return true;
	}

	private static void advance() {
		if (player == null || updatedTick == player.tickCount) return;
		updatedTick = player.tickCount;
		previousYaw = yaw;
		previousPitch = pitch;
		previousBody = body;
		float targetYaw = player.getYRot(), targetPitch = 38f;
		if (target != null) {
			Vec3 delta = target.subtract(player.getEyePosition());
			if (delta.lengthSqr() > 1.0E-8) {
				targetYaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90f;
				targetPitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
			}
		}
		float yawError = Mth.wrapDegrees(targetYaw - yaw);
		float pitchError = Mth.clamp(targetPitch, -80f, 80f) - pitch;
		yaw += Mth.clamp(yawError * .58f, -48f, 48f);
		pitch += Mth.clamp(pitchError * .58f, -38f, 38f);
		body += Mth.clamp(Mth.wrapDegrees(yaw - body) * .36f, -32f, 32f);
	}

	private static boolean active() {
		return controlled && source != null && source.isEnabled() && System.nanoTime() - operatedAt <= LEASE_NANOS;
	}

	private static void release() {
		if (!controlled) return;
		controlled = false;
		updatedTick = Integer.MIN_VALUE;
		if (player == null || player != MC.player || world != MC.level) { reset(); return; }
		long now = System.nanoTime();
		boolean useRendered = now - renderedAt < 150_000_000L;
		releaseYaw = useRendered ? renderedYaw : yaw;
		releasePitch = useRendered ? renderedPitch : pitch;
		releaseBody = useRendered ? renderedBody : body;
		releasedAt = now;
	}

	private static boolean contextAllowed() {
		return MC.player != null && MC.level != null && MC.gameMode != null && MC.isWindowActive()
				&& !MC.isPaused() && MC.player.isAlive() && !MC.player.isDeadOrDying()
				&& !MC.player.isSpectator() && !MC.player.isSleeping() && !MC.player.isUsingItem();
	}
	private static float smooth(float value) { float t = Mth.clamp(value, 0, 1); return t * t * (3 - 2 * t); }
	private static boolean finite(Vec3 point) { return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z); }
	private static void reset() {
		player = null; world = null; source = null; target = null; controlled = false;
		operatedAt = acquireAt = releasedAt = renderedAt = swingAt = 0;
		updatedTick = Integer.MIN_VALUE;
	}
}
