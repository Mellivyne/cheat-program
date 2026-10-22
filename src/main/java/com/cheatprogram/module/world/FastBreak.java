package com.cheatprogram.module.world;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.mixin.MultiPlayerGameModeAccessor;

public class FastBreak extends Module {
	private final NumberSetting speed = add(new NumberSetting("speed", 1.2, 1.0, 1.4, 0.05, "x"));
	private final BoolSetting noDelay = add(new BoolSetting("noDelay", true));

	public FastBreak() {
		super("fastbreak", Category.WORLD);
	}

	@Override
	public void onTick() {
		if (noDelay.on()) ((MultiPlayerGameModeAccessor) mc.gameMode).cheatprogram$setDestroyDelay(0);
	}

	/** Scales per-tick break progress. The server accepts blocks broken at 70% of the vanilla time. */
	public static float scale(float progress) {
		FastBreak fb = ModuleManager.get(FastBreak.class);
		return fb != null && fb.isEnabled() ? progress * fb.speed.floatVal() : progress;
	}
}
