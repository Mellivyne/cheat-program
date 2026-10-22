package com.cheatprogram.module.utility;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.util.InvUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Throws an ender pearl where the player is looking, then switches back. */
public class AutoPearl extends Module {
	private final BoolSetting switchBack = add(new BoolSetting("switchBack", true));

	public AutoPearl() {
		super("autopearl", Category.UTILITY);
	}

	@Override
	public boolean isOneShot() {
		return true;
	}

	@Override
	public void onEnable() {
		try {
			if (!inGame()) return;
			int slot = InvUtil.findHotbar(s -> s.is(Items.ENDER_PEARL));
			if (slot < 0) return;
			ItemStack stack = InvUtil.stack(slot);
			if (mc.player.getCooldowns().isOnCooldown(stack)) return;
			int previous = mc.player.getInventory().getSelectedSlot();
			InvUtil.select(slot);
			mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
			mc.player.swing(InteractionHand.MAIN_HAND);
			if (switchBack.on()) InvUtil.select(previous);
		} finally {
			setEnabled(false);
		}
	}
}
