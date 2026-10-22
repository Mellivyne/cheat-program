package com.cheatprogram.module.combat;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.NumberSetting;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

public class HitSelect extends Module {
	private final NumberSetting chance = add(new NumberSetting("chance", 90, 0, 100, 1, "%"));

	public HitSelect() {
		super("hitselect", Category.COMBAT);
	}

	@Override
	public boolean onAttack(Entity target) {
		return target instanceof LivingEntity living && living.hurtTime > 1 && Math.random() * 100 < chance.val();
	}
}
