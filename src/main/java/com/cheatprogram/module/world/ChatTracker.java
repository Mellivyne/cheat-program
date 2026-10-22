package com.cheatprogram.module.world;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.gui.ChatHistoryScreen;
import net.minecraft.client.gui.screens.Screen;

/** Opens the local history browser; the global receiver keeps already received chat available. */
public final class ChatTracker extends Module {
	public final TextSetting playerId = add(new TextSetting("playerId", "", 64));
	public ChatTracker() { super("chattracker", Category.WORLD); }
	@Override public boolean isOneShot() { return true; }
	@Override public void onEnable() { open(mc.screen); setEnabled(false); }
	public void open(Screen parent) { mc.setScreen(new ChatHistoryScreen(parent, playerId, false)); }
}
