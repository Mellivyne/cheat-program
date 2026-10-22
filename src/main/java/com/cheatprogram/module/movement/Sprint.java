package com.cheatprogram.module.movement;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;

public class Sprint extends Module {
	private final BoolSetting omni = add(new BoolSetting("omni", false));

	public Sprint() {
		super("sprint", Category.MOVEMENT);
	}

	@Override
	public void onTick() {
		var p = mc.player;
		boolean moving = omni.on()
				? Math.abs(p.input.forwardImpulse) > 0.01f || Math.abs(p.input.leftImpulse) > 0.01f
				: p.input.hasForwardImpulse();
		boolean hungry = p.getFoodData().getFoodLevel() <= 6 && !p.getAbilities().mayfly;
		if (moving && !hungry && !p.isShiftKeyDown() && !p.isUsingItem() && !p.horizontalCollision && !p.isPassenger()) {
			p.setSprinting(true);
		}
	}
}
