package com.cheatprogram.module.movement;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.NumberSetting;

public class Step extends Module {
	private final NumberSetting height = add(new NumberSetting("height", 1.0, 0.6, 3.0, 0.1));

	public Step() {
		super("step", Category.MOVEMENT);
	}

	@Override
	public String suffix() {
		return height.format();
	}

	/** Step height for the local player (applied on both the client and the integrated server). */
	public static float stepHeight(float vanilla, boolean sneaking) {
		Step s = ModuleManager.get(Step.class);
		if (s == null || !s.isEnabled() || sneaking || FlatFlight.ownsMotion()) return vanilla;
		return Math.max(vanilla, s.height.floatVal());
	}
}
