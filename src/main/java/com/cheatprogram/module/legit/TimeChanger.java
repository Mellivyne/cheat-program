package com.cheatprogram.module.legit;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.NumberSetting;

public class TimeChanger extends Module {
	private final NumberSetting time = add(new NumberSetting("time", 6000, 0, 24000, 500));

	public TimeChanger() {
		super("timechanger", Category.LEGIT);
	}

	public static long apply(long vanilla) {
		TimeChanger t = ModuleManager.get(TimeChanger.class);
		return t != null && t.isEnabled() ? (long) t.time.val() : vanilla;
	}
}
