package com.cheatprogram.module.macro;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.TextSetting;

/** Runs a chat command (or message) once each time it is triggered. */
public class MacroModule extends Module {
	private final TextSetting command;

	public MacroModule(String id, String defaultCommand) {
		super(id, Category.MACRO);
		command = add(new TextSetting("command", defaultCommand, 256));
	}

	@Override
	public boolean isOneShot() {
		return true;
	}

	@Override
	public void onEnable() {
		try {
			if (!inGame()) return;
			String text = command.get().trim();
			if (text.startsWith("/")) mc.player.connection.sendCommand(text.substring(1));
			else if (!text.isEmpty()) mc.player.connection.sendChat(text);
		} finally {
			setEnabled(false);
		}
	}
}
