package com.cheatprogram.module.combat;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.util.Keys;
import net.minecraft.world.entity.Entity;

public class WTap extends Module {
	private final NumberSetting chance = add(new NumberSetting("chance", 90, 0, 100, 1, "%"));
	private final NumberSetting duration = add(new NumberSetting("duration", 2, 1, 6, 1, " tick"));
	private int ticks;

	public WTap() {
		super("wtap", Category.COMBAT);
	}

	@Override
	public void onPostAttack(Entity target) {
		if (mc.player.isSprinting() && Math.random() * 100 < chance.val()) ticks = duration.intVal();
	}

	@Override
	public void onTick() {
		if (ticks > 0) {
			mc.options.keyUp.setDown(false);
			mc.player.setSprinting(false);
			if (--ticks == 0) Keys.restore(mc.options.keyUp);
		}
	}

	@Override
	public void onDisable() {
		if (ticks > 0 && mc.player != null) Keys.restore(mc.options.keyUp);
		ticks = 0;
	}
}
