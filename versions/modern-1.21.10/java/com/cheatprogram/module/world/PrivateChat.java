package com.cheatprogram.module.world;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.gui.ChatHistoryScreen;
import com.cheatprogram.util.ChatHistoryStore;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.util.StringUtil;

import java.util.UUID;
import java.util.regex.Pattern;

/** A user-operated composer for vanilla /msg. No activation or background tick sends anything. */
public final class PrivateChat extends Module {
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
	public final TextSetting playerId = add(new TextSetting("playerId", "", 64));
	public PrivateChat() { super("privatechat", Category.WORLD); }
	@Override public boolean isOneShot() { return true; }
	@Override public void onEnable() { open(mc.screen); setEnabled(false); }
	public void open(Screen parent) { mc.setScreen(new ChatHistoryScreen(parent, playerId, true)); }
	public record Result(boolean submitted, String statusKey) { }

	/** Called only by the explicit send button or Enter while the message field is focused. */
	public static Result send(String playerId, String message) {
		ClientPacketListener connection = mc.getConnection();
		if (!inGame() || connection == null || !connection.getConnection().isConnected())
			return new Result(false, "ui.privatechat.disconnected");
		String recipient = resolveName(connection, playerId == null ? "" : playerId.strip());
		if (recipient == null) return new Result(false, "ui.privatechat.invalidPlayer");
		String body = message == null ? "" : message;
		if (body.isBlank()) return new Result(false, "ui.privatechat.empty");
		for (int i = 0; i < body.length(); i++) {
			if (!StringUtil.isAllowedChatCharacter(body.charAt(i)))
				return new Result(false, "ui.privatechat.invalidText");
		}
		String command = "msg " + recipient + " " + body;
		// The vanilla signed/unsigned command packets both use FriendlyByteBuf's 32767 UTF-16 limit.
		if (command.length() > 32767) return new Result(false, "ui.privatechat.tooLong");
		try { connection.sendCommand(command); }
		catch (RuntimeException failure) { return new Result(false, "ui.privatechat.failed"); }
		// A recording failure cannot turn an already submitted command into a retryable send error.
		try { ChatHistoryStore.recordOutgoing(recipient, body); } catch (RuntimeException ignored) { }
		return new Result(true, "ui.privatechat.submitted");
	}

	private static String resolveName(ClientPacketListener connection, String value) {
		if (NAME.matcher(value).matches()) {
			for (PlayerInfo player : connection.getOnlinePlayers()) {
				if (player.getProfile().name().equalsIgnoreCase(value)) return player.getProfile().name();
			}
			// Offline names may be supported by a server's /msg implementation; the server decides.
			return value;
		}
		try {
			String canonical = value;
			if (value.matches("[0-9a-fA-F]{32}")) canonical = value.substring(0, 8) + "-" + value.substring(8, 12)
					+ "-" + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20);
			PlayerInfo player = connection.getPlayerInfo(UUID.fromString(canonical));
			if (player != null && NAME.matcher(player.getProfile().name()).matches()) return player.getProfile().name();
		} catch (IllegalArgumentException ignored) { }
		return null;
	}
}
