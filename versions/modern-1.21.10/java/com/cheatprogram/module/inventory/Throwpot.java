package com.cheatprogram.module.inventory;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.util.InvUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

public class Throwpot extends Module {
	private final NumberSetting health = add(new NumberSetting("health", 10, 1, 19, 0.5, " HP"));
	private long last;

	public Throwpot() {
		super("throwpot", Category.INVENTORY);
	}

	@Override
	public void onTick() {
		var p = mc.player;
		if (p.getHealth() > health.val() || mc.screen != null || System.currentTimeMillis() - last < 900 || p.isUsingItem()) return;
		int slot = InvUtil.findHotbar(Throwpot::isHealing);
		if (slot < 0) return;
		int previous = p.getInventory().getSelectedSlot();
		float pitch = p.getXRot();
		InvUtil.select(slot);
		p.setXRot(90f);
		mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		p.swing(InteractionHand.MAIN_HAND);
		p.setXRot(pitch);
		InvUtil.select(previous);
		last = System.currentTimeMillis();
	}

	private static boolean isHealing(ItemStack stack) {
		if (!stack.is(Items.SPLASH_POTION)) return false;
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		if (contents == null) return false;
		for (MobEffectInstance effect : contents.getAllEffects()) {
			if (effect.getEffect().equals(MobEffects.INSTANT_HEALTH) || effect.getEffect().equals(MobEffects.REGENERATION)) return true;
		}
		return false;
	}
}
