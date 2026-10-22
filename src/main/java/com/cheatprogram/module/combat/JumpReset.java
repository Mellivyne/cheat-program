package com.cheatprogram.module.combat;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.RangeSetting;

public class JumpReset extends Module {
	private final NumberSetting chance = add(new NumberSetting("chance", 40, 0, 100, 1, "%"));
	private final RangeSetting accuracy = add(new RangeSetting("accuracy", 40, 60, 0, 100, 1, "%"));
	private int lastHurtTime;
	private int jumpIn = -1;

	public JumpReset() {
		super("jumpreset", Category.COMBAT);
	}

	@Override
	public void onTick() {
		int hurt = mc.player.hurtTime;
		if (hurt > 0 && hurt == mc.player.hurtDuration && hurt != lastHurtTime && Math.random() * 100 < chance.val()) {
			jumpIn = Math.random() * 100 < accuracy.random() ? 0 : 1;
		}
		lastHurtTime = hurt;
		if (jumpIn == 0) {
			if (mc.player.onGround() && !mc.player.isInWater()) mc.player.jumpFromGround();
			jumpIn = -1;
		} else if (jumpIn > 0) {
			jumpIn--;
		}
	}
}
