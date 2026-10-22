package com.cheatprogram.module.legit;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.util.Keys;

public class ToggleSneak extends Module {
	private boolean sneaking;
	private boolean lastPhysical;

	public ToggleSneak() {
		super("togglesneak", Category.LEGIT);
	}

	@Override
	public void onTick() {
		boolean physical = Keys.physical(mc.options.keyShift);
		if (physical && !lastPhysical && mc.screen == null) sneaking = !sneaking;
		lastPhysical = physical;
		if (mc.screen == null) mc.options.keyShift.setDown(sneaking || physical);
	}

	@Override
	public void onDisable() {
		sneaking = false;
		if (mc.player != null) Keys.restore(mc.options.keyShift);
	}
}
