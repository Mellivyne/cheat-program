package com.cheatprogram.module.movement;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.util.Keys;

public class AutoWalk extends Module {
	public AutoWalk() {
		super("autowalk", Category.MOVEMENT);
	}

	@Override
	public void onTick() {
		if (mc.screen == null) mc.options.keyUp.setDown(true);
	}

	@Override
	public void onDisable() {
		if (mc.player != null) Keys.restore(mc.options.keyUp);
	}
}
