package com.cheatprogram.module.world;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.TextSetting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.util.StringUtil;

/** One explicit activation sends one bounded batch of ordinary chat, never commands. */
public final class ChatRepeat extends Module {
	private final TextSetting message = add(new TextSetting("message", "", 256));
	private final NumberSetting repeats = add(new NumberSetting("repeats", 3, 1, 1000, 1));
	private final NumberSetting interval = add(new NumberSetting("interval", 1000, 100, 10000, 100, "ms"));
	private ClientPacketListener connection;
	private ClientLevel world;
	private String activeMessage;
	private int requested, sent;
	private long intervalNanos, waitNanos, lastTickNanos;

	public ChatRepeat() { super("chatrepeat", Category.WORLD); }

	/** Do not persist or resume an in-flight batch after restarting or selecting another profile. */
	@Override public boolean isOneShot() { return true; }
	@Override public String suffix() { return activeMessage == null ? "" : sent + " / " + requested; }

	@Override public void onEnable() {
		clear();
		String text = normalize(message.get());
		if (text.isEmpty()) { stop("ui.chatrepeat.empty", false); return; }
		if (text.startsWith("/")) { stop("ui.chatrepeat.noCommand", false); return; }
		ClientPacketListener current = mc.getConnection();
		if (!inGame() || current == null || !current.getConnection().isConnected() || !mc.player.isAlive()) {
			stop("ui.chatrepeat.disconnected", false); return;
		}
		connection = current;
		world = mc.level;
		activeMessage = text;
		requested = repeats.intVal();
		intervalNanos = interval.intVal() * 1_000_000L;
		lastTickNanos = System.nanoTime();
	}

	@Override public void onDisable() { clear(); }

	/** Called by the global client tick even when there is no player or level. */
	public void checkLifecycle() {
		if (!isEnabled()) return;
		if (activeMessage == null) { setEnabled(false); return; }
		if (!inGame() || mc.level != world || mc.getConnection() != connection
				|| !connection.getConnection().isConnected() || !mc.player.isAlive()) {
			stop("ui.chatrepeat.stopped", false);
		}
	}

	@Override public void onTick() {
		checkLifecycle();
		if (!isEnabled() || activeMessage == null) return;
		long now = System.nanoTime();
		long elapsed = Math.max(0, now - lastTickNanos);
		lastTickNanos = now;
		if (mc.screen != null || mc.isPaused()) return;
		waitNanos -= elapsed;
		if (waitNanos > 0) return;
		try {
			// Only this one explicit action reaches the real signed-chat API. At most one per tick,
			// without a catch-up loop if the game stalls or the settings screen was open.
			connection.sendChat(activeMessage);
			sent++;
			if (sent >= requested) { stop("ui.chatrepeat.finished", true); return; }
			waitNanos = intervalNanos;
		} catch (RuntimeException failure) {
			// Do not display signed-chat internals or retry a possibly already sent packet.
			stop("ui.chatrepeat.failed", false);
		}
	}

	private void stop(String key, boolean positive) {
		String progress = activeMessage == null ? "" : " · " + sent + " / " + requested;
		setEnabled(false);
		Notifications.push(name(), Lang.t(key) + progress, positive);
	}

	private void clear() {
		connection = null; world = null; activeMessage = null;
		requested = sent = 0; intervalNanos = waitNanos = lastTickNanos = 0;
	}

	private static String normalize(String value) {
		String text = net.minecraft.SharedConstants.filterText(value == null ? "" : value.replace('\r', ' ').replace('\n', ' ')).strip();
		if (text.length() <= 256) return text;
		int end = 256;
		if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
		return text.substring(0, end).strip();
	}
}
