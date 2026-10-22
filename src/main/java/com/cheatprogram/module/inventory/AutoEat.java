package com.cheatprogram.module.inventory;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.util.InvUtil;
import com.cheatprogram.util.Keys;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class AutoEat extends Module {
	private final NumberSetting hunger = add(new NumberSetting("hunger", 14, 1, 19, 1));
	private boolean eating;
	private int previous = -1;
	private int ticks;

	public AutoEat() {
		super("autoeat", Category.INVENTORY);
	}

	@Override
	public void onTick() {
		var p = mc.player;
		int food = p.getFoodData().getFoodLevel();
		if (eating) {
			ticks++;
			boolean done = food >= 20 || (ticks > 6 && !p.isUsingItem()) || mc.screen != null || !isFood(p.getMainHandItem());
			if (done) stop();
			else mc.options.keyUse.setDown(true);
			return;
		}
		if (food > hunger.intVal() || mc.screen != null || p.isUsingItem() || p.getAbilities().instabuild) return;
		int slot = InvUtil.findHotbar(this::isFood);
		if (slot < 0) return;
		previous = p.getInventory().selected;
		InvUtil.select(slot);
		mc.options.keyUse.setDown(true);
		eating = true;
		ticks = 0;
	}

	private void stop() {
		Keys.restore(mc.options.keyUse);
		if (previous >= 0) InvUtil.select(previous);
		previous = -1;
		eating = false;
	}

	@Override
	public void onDisable() {
		if (eating && mc.player != null) stop();
		eating = false;
	}

	private boolean isFood(ItemStack stack) {
		if (!stack.has(DataComponents.FOOD)) return false;
		return !(stack.is(Items.ROTTEN_FLESH) || stack.is(Items.SPIDER_EYE) || stack.is(Items.POISONOUS_POTATO)
				|| stack.is(Items.PUFFERFISH) || stack.is(Items.CHORUS_FRUIT) || stack.is(Items.SUSPICIOUS_STEW));
	}
}
