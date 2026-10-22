package com.cheatprogram.module.utility;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import net.minecraft.client.gui.screens.DeathScreen;

public class AutoRespawn extends Module {
	public AutoRespawn() {
		super("autorespawn", Category.UTILITY);
	}

	@Override
	public void onTick() {
		if (mc.screen instanceof DeathScreen && mc.player.isDeadOrDying()) {
			mc.player.respawn();
			mc.setScreen(null);
		}
	}
}
