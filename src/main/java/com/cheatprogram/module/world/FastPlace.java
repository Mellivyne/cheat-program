package com.cheatprogram.module.world;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.mixin.MinecraftAccessor;

public class FastPlace extends Module {
	private final NumberSetting delay = add(new NumberSetting("delay", 0, 0, 4, 1, " tick"));

	public FastPlace() {
		super("fastplace", Category.WORLD);
	}

	@Override
	public void onTick() {
		MinecraftAccessor accessor = (MinecraftAccessor) mc;
		if (accessor.cheatprogram$getRightClickDelay() > delay.intVal()) accessor.cheatprogram$setRightClickDelay(delay.intVal());
	}
}
