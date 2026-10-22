package com.cheatprogram.module.combat;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.RangeSetting;

public class Reach extends Module {
	private final RangeSetting range = add(new RangeSetting("range", 3.0, 3.1, 3.0, 6.0, 0.1, ""));
	private final NumberSetting chance = add(new NumberSetting("chance", 50, 0, 100, 1, "%"));
	private final BoolSetting sprintOnly = add(new BoolSetting("sprintOnly", false));
	private final BoolSetting disableInWater = add(new BoolSetting("disableInWater", false));
	private final BoolSetting blockReach = add(new BoolSetting("blockReach", false));
	private volatile double current = 3.0;

	public Reach() {
		super("reach", Category.COMBAT);
	}

	@Override
	public String suffix() {
		return range.format();
	}

	@Override
	public void onTick() {
		double next = Math.random() * 100 < chance.val() ? range.random() : 3.0;
		if (sprintOnly.on() && !mc.player.isSprinting()) next = 3.0;
		if (disableInWater.on() && mc.player.isInWater()) next = 3.0;
		current = next;
	}

	public static double entityRange(double vanilla) {
		Reach reach = ModuleManager.get(Reach.class);
		if (reach == null || !reach.isEnabled()) return vanilla;
		return Math.max(vanilla, reach.current);
	}

	public static double blockRange(double vanilla) {
		Reach reach = ModuleManager.get(Reach.class);
		if (reach == null || !reach.isEnabled() || !reach.blockReach.on()) return vanilla;
		return vanilla + Math.max(0, reach.range.high() - 3.0);
	}
}
