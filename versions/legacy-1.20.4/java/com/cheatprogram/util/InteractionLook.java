package com.cheatprogram.util;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.core.ModuleManager;


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
 private static final Minecraft MC=Minecraft.getInstance();
 private static long correctionRevision,lastFollowNanos;
 private static LocalPlayer syncedPlayer;
 private static ClientLevel syncedLevel;
 private static ClientPacketListener syncedConnection;
 private static long syncedRevision;
 private InteractionLook() { }
 public static <T> T interact(Vec3 point,String mode,Supplier<T> action){return interact(point,mode,action,false);}
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
			connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround()));
			return action.get();
		} finally {
			if (MC.player == player && MC.level == level && MC.getConnection() == connection && actionRevision == correctionRevision) {
				player.setYRot(originalYaw);
				player.setXRot(originalPitch);
				if (deferRestore) {
					syncedPlayer = player; syncedLevel = level; syncedConnection = connection; syncedRevision = actionRevision;
				} else {
					// Ordinary assisted interactions keep their immediate network restoration.
					connection.send(new ServerboundMovePlayerPacket.Rot(originalYaw, originalPitch, player.onGround()));
				}
			}
		}
	}
}
