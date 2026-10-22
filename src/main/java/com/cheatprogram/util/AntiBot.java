package com.cheatprogram.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.player.Player;

import java.util.IdentityHashMap;
import java.util.Map;

/** Conservative, recoverable player-identity checks, scoped to KillAura's optional filter. */
public final class AntiBot {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final Map<Player, Boolean> CHECKED = new IdentityHashMap<>();
	private static ClientLevel world;
	private static ClientPacketListener connection;
	private static Player owner;
	private static int tick = Integer.MIN_VALUE;

	private AntiBot() { }

	/** Globally called even on disconnect; no decisions survive a world, connection, or player change. */
	public static void tickLifecycle() {
		if (world != MC.level || connection != MC.getConnection() || owner != MC.player) {
			clear();
			world = MC.level;
			connection = MC.getConnection();
			owner = MC.player;
		}
		int current = MC.player == null ? Integer.MIN_VALUE : MC.player.tickCount;
		if (tick != current) { CHECKED.clear(); tick = current; }
	}

	public static void clear() {
		CHECKED.clear();
		world = null; connection = null; owner = null; tick = Integer.MIN_VALUE;
	}

	/** Missing information is only a temporary exclusion; receiving the profile immediately restores eligibility. */
	public static boolean isBot(Player player) {
		if (player == null || player == MC.player || MC.hasSingleplayerServer()) return false;
		tickLifecycle();
		if (connection == null || world == null || player.level() != world || world.getEntity(player.getId()) != player) return true;
		var info = connection.getPlayerInfo(player.getUUID());
		if (info == null) return true;
		// Do not require Tab's listed flag, positive ping, a skin, UUID version 4, or a vanilla-only name.
		// Offline/proxy/Bedrock accounts and hidden Tab entries can all be real players.
		var profile = info.getProfile();
		var entityProfile = player.getGameProfile();
		if (profile == null || entityProfile == null || !player.getUUID().equals(profile.getId())
				|| !player.getUUID().equals(entityProfile.getId()) || info.getGameMode() == null) return true;
		String name = profile.getName(), entityName = entityProfile.getName();
		if (name == null || name.isBlank() || entityName == null || !name.equals(entityName)
				|| name.codePoints().anyMatch(Character::isISOControl)) return true;
		return CHECKED.computeIfAbsent(player, value -> !Double.isFinite(value.getX()) || !Double.isFinite(value.getY())
				|| !Double.isFinite(value.getZ()) || !Float.isFinite(value.getXRot()) || !Float.isFinite(value.getYRot())
				|| !Float.isFinite(value.getHealth()));
	}
}
