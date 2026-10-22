package com.cheatprogram.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Consumer;

/** In single player, runs an action on the integrated server's copy of the local player. */
public final class ServerSide {
	private ServerSide() {
	}

	public static void withPlayer(Consumer<ServerPlayer> action) {
		Minecraft mc = Minecraft.getInstance();
		IntegratedServer server = mc.getSingleplayerServer();
		if (server == null || mc.player == null) return;
		UUID id = mc.player.getUUID();
		server.execute(() -> {
			ServerPlayer player = server.getPlayerList().getPlayer(id);
			if (player != null) action.accept(player);
		});
	}
}
