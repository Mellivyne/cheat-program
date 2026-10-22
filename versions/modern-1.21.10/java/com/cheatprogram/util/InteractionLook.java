package com.cheatprogram.util;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.movement.AirWalk;
import com.cheatprogram.module.movement.FlatFlight;
import com.cheatprogram.module.utility.Blink;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.function.Supplier;

/** Client-thread interaction orientation. Keeps server and placement context aligned in one scope. */
public final class InteractionLook {
	private static final Minecraft MC = Minecraft.getInstance();
	private static long lastFollowNanos;
	private static long correctionRevision;
	private static LocalPlayer syncedPlayer;
	private static ClientLevel syncedLevel;
	private static ClientPacketListener syncedConnection;
	private static long syncedRevision;
	private static LocalPlayer movementPlayer, publishedPlayer;
	private static ClientLevel movementLevel, publishedLevel;
	private static ClientPacketListener movementConnection, publishedConnection;
	private static long movementRevision, publishedRevision;
	private static float cameraYaw, cameraPitch, cameraYawOld, cameraPitchOld, movementBasisYaw, publishedYaw, publishedPitch;
	private static int publishedTick = Integer.MIN_VALUE;
	private static String movementOwner, publishedOwner;
	private static boolean movementScoped, movementSent, networkLease;

	/** The same angles are used by local movement, vanilla movement packets and later actions. */
	public record MovementAim(float yaw, float pitch, float basisYaw) { }

	public static boolean beginMovement() {
		if (movementScoped) return false;
		if (!movementAllowed()) { forgetMovement(); return false; }
		MovementAim aim = AuraRotations.movementAim();
		String source = "aura";
		if (aim == null) { aim = ScaffoldRotations.movementAim(); source = "scaffold"; }
		LocalPlayer player = MC.player;
		if (aim == null) {
			if (!validPublished() || !networkLease) return false;
			float yawError = Mth.wrapDegrees(player.getYRot() - publishedYaw);
			float pitchError = player.getXRot() - publishedPitch;
			// Releasing a target must not reverse its network look in one extra Rot packet.
			float length = (float) Math.hypot(yawError, pitchError);
			float step = Math.min(27, length);
			float factor = length > 0 ? step / length : 1;
			aim = new MovementAim(publishedYaw + yawError * factor,
					publishedPitch + pitchError * factor, player.getYRot());
			source = publishedOwner;
			networkLease = length > 27;
		} else networkLease = true;
		if (!Float.isFinite(aim.yaw) || !Float.isFinite(aim.pitch) || !Float.isFinite(aim.basisYaw)) {
			forgetMovement(); return false;
		}
		movementPlayer = player; movementLevel = MC.level; movementConnection = MC.getConnection();
		movementRevision = correctionRevision; movementOwner = source;
		cameraYaw = player.getYRot(); cameraPitch = player.getXRot();
		cameraYawOld = player.yRotO; cameraPitchOld = player.xRotO; movementBasisYaw = aim.basisYaw;
		movementScoped = true; movementSent = false;
		player.setYRot(aim.yaw); player.setXRot(Mth.clamp(aim.pitch, -90, 90));
		return true;
	}

	/** Called by a WrapMethod finally, including exceptions raised inside vanilla tick. */
	public static void endMovement(boolean started, boolean completed) {
		if (!started || !movementScoped) return;
		LocalPlayer player = movementPlayer;
		boolean valid = player == MC.player && movementLevel == MC.level && movementConnection == MC.getConnection()
				&& movementRevision == correctionRevision;
		if (valid) {
			if (completed && movementSent) {
				publishedPlayer = player; publishedLevel = movementLevel; publishedConnection = movementConnection;
				publishedRevision = movementRevision; publishedTick = player.tickCount;
				publishedYaw = player.getYRot(); publishedPitch = player.getXRot(); publishedOwner = movementOwner;
			}
			player.setYRot(cameraYaw); player.setXRot(cameraPitch);
			// LivingEntity.baseTick copies temporary angles into these camera interpolation fields.
			player.yRotO = cameraYawOld; player.xRotO = cameraPitchOld;
		}
		movementScoped = false; movementPlayer = null; movementLevel = null; movementConnection = null;
		if (!valid || !completed || !movementSent) forgetMovement();
	}

	/** sendPosition finished for the controlled body; an unloaded/cancelled player tick cannot publish. */
	public static void movementSent() { if (movementScoped) movementSent = true; }

	/** Keyboard flags and local impulses use one legal eight-way direction in the sent yaw frame. */
	public static void applyInput(ClientInput input) {
		if (!movementScoped || movementPlayer != MC.player || input != movementPlayer.input) return;
		var direction = ServerActionGate.keyboardDirection(com.cheatprogram.compat.InputBridge.forward(input), com.cheatprogram.compat.InputBridge.sideways(input),
				movementPlayer.getYRot() - movementBasisYaw);
		Input old = input.keyPresses;
		float forward = direction.forward(), strafe = direction.strafe();
		boolean sprint = old.sprint() && forward > 0 && !old.shift();
		com.cheatprogram.compat.InputBridge.set(input, forward, strafe);
		input.keyPresses = new Input(forward > 0, forward < 0, strafe > 0, strafe < 0,
				old.jump(), old.shift(), sprint);
		if (forward <= 0 || old.shift()) movementPlayer.setSprinting(false);
	}

	public static boolean movementScoped() { return movementScoped; }
	public static boolean publishedThisTick(String source) {
		return validPublished() && publishedTick == MC.player.tickCount && source.equals(publishedOwner);
	}
	public static boolean ownsNetwork(String source) {
		return validPublished() && networkLease && source.equals(publishedOwner);
	}
	public static float publishedYaw() { return publishedYaw; }
	public static float publishedPitch() { return publishedPitch; }

	/** Placement context uses the already transmitted look without another movement packet. */
	public static <T> T interactPublished(String source, Supplier<T> action, T unavailable) {
		if (!publishedThisTick(source) || !movementAllowed()) return unavailable;
		LocalPlayer player = MC.player; ClientLevel level = MC.level;
		ClientPacketListener connection = MC.getConnection(); long revision = correctionRevision;
		float yaw = player.getYRot(), pitch = player.getXRot();
		try { player.setYRot(publishedYaw); player.setXRot(publishedPitch); return action.get(); }
		finally {
			if (MC.player == player && MC.level == level && MC.getConnection() == connection && revision == correctionRevision) {
				player.setYRot(yaw); player.setXRot(pitch);
			}
		}
	}

	/** Real mouse actions take ownership before their packet is dispatched. */
	public static void releaseForManualAction() {
		if (validPublished() && !movementScoped && (publishedYaw != MC.player.getYRot() || publishedPitch != MC.player.getXRot()))
			MC.getConnection().send(new ServerboundMovePlayerPacket.Rot(MC.player.getYRot(), MC.player.getXRot(),
					MC.player.onGround(), MC.player.horizontalCollision));
		forgetMovement();
	}

	private static boolean validPublished() {
		return publishedPlayer != null && publishedPlayer == MC.player && publishedLevel == MC.level
				&& publishedConnection == MC.getConnection() && publishedRevision == correctionRevision;
	}
	private static boolean movementAllowed() {
		return MC.player != null && MC.level != null && MC.gameMode != null && MC.getConnection() != null
				&& MC.screen == null && MC.getOverlay() == null && !MC.isPaused() && MC.isWindowActive()
				&& MC.player.isAlive() && !MC.player.isDeadOrDying() && !MC.player.isSpectator() && !MC.player.isPassenger()
				&& !MC.player.isUsingItem() && !MC.options.keyUse.isDown() && !MC.options.keyAttack.isDown()
				&& !MC.gameMode.isDestroying() && !Freecam.isActive() && !AiController.isRunning()
				&& !MC.player.getAbilities().flying && !MC.player.isFallFlying()
				&& !AirWalk.ownsMotion() && !FlatFlight.ownsMotion() && !com.cheatprogram.module.movement.TeleportAura.ownsMotion()
				&& !ModuleManager.on(com.cheatprogram.module.movement.Flight.class) && !ModuleManager.on(Blink.class);
	}
	private static void forgetMovement() {
		networkLease = false; publishedPlayer = null; publishedLevel = null; publishedConnection = null;
		publishedOwner = null; publishedTick = Integer.MIN_VALUE;
	}

	private InteractionLook() { }

	public static void onServerCorrection() { correctionRevision++; lastFollowNanos = 0; clearSyncedLook(); forgetMovement(); }

	/**
	 * Create BlockPlaceContext/useItemOn inside action: it sees the same exact angle sent to the server.
	 * silent restores the local angle before the next render; follow gently moves the visible view first.
	 * AI and Freecam retain ownership of visible/body orientation, so their interactions stay silent.
	 */
	public static <T> T interact(Vec3 point, String mode, Supplier<T> action) {
		return interact(point, mode, action, false);
	}

	/** Remote Scaffold calls only after vanilla movement has sent its position for this tick. */
	public static <T> T interactSynced(Vec3 point, String mode, Supplier<T> action) {
		return interact(point, mode, action, true);
	}

	/** Runs before the next body/input tick, including when Scaffold is suspended or disabled. */
	public static void restoreSyncedLook() {
		if (!movementScoped && MC.player != null && (MC.options.keyUse.isDown() || MC.options.keyAttack.isDown())) releaseForManualAction();
		var player = syncedPlayer;
		var connection = syncedConnection;
		boolean valid = player != null && player == MC.player && syncedLevel == MC.level
				&& connection == MC.getConnection() && syncedRevision == correctionRevision;
		clearSyncedLook();
		if (valid) connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(),
				player.onGround(), player.horizontalCollision));
	}

	private static void clearSyncedLook() { syncedPlayer = null; syncedLevel = null; syncedConnection = null; }

	private static <T> T interact(Vec3 point, String mode, Supplier<T> action, boolean deferRestore) {
		if (MC.player == null || MC.level == null || MC.getConnection() == null || point == null) return action.get();
		if (!Double.isFinite(point.x) || !Double.isFinite(point.y) || !Double.isFinite(point.z)) throw new IllegalArgumentException("Invalid interaction point");
		var player = MC.player;
		var connection = MC.getConnection();
		var level = MC.level;
		long actionRevision = correctionRevision;
		Vec3 delta = point.subtract(player.getEyePosition());
		float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
		float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))), -90, 90);
		if ("follow".equals(mode) && !AiController.isRunning() && !Freecam.isActive() && MC.screen == null) {
			long now = System.nanoTime();
			float dt = lastFollowNanos == 0 ? .05f : Math.min(.05f, Math.max(0, (now - lastFollowNanos) / 1_000_000_000f));
			lastFollowNanos = now;
			float factor = 1f - (float) Math.exp(-9f * dt);
			float yawStep = Mth.clamp(Mth.wrapDegrees(yaw - player.getYRot()) * factor, -18, 18);
			float pitchStep = Mth.clamp((Mth.clamp(pitch, -78, 78) - player.getXRot()) * factor, -10, 10);
			player.turn(yawStep / .15, pitchStep / .15);
		}
		float originalYaw = player.getYRot(), originalPitch = player.getXRot();
		try {
			player.setYRot(yaw);
			player.setXRot(pitch);
			connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround(), player.horizontalCollision));
			return action.get();
		} finally {
			if (MC.player == player && MC.level == level && MC.getConnection() == connection && actionRevision == correctionRevision) {
				player.setYRot(originalYaw);
				player.setXRot(originalPitch);
				if (deferRestore) {
					syncedPlayer = player; syncedLevel = level; syncedConnection = connection; syncedRevision = actionRevision;
				} else {
					// Ordinary assisted interactions keep their immediate network restoration.
					connection.send(new ServerboundMovePlayerPacket.Rot(originalYaw, originalPitch, player.onGround(), player.horizontalCollision));
				}
			}
		}
	}
}
