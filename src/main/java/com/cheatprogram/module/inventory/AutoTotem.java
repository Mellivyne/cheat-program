package com.cheatprogram.module.inventory;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.util.Clock;
import com.cheatprogram.util.InvUtil;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;

public class AutoTotem extends Module {
	private final Clock clock = new Clock();

	public AutoTotem() {
		super("autototem", Category.INVENTORY);
	}

	@Override
	public void onTick() {
		if (mc.screen != null && !(mc.screen instanceof InventoryScreen)) return;
		if (mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING) || !InvUtil.canUseInventory() || !clock.passed(150)) return;
		int index = InvUtil.find(s -> s.is(Items.TOTEM_OF_UNDYING));
		if (index < 0) return;
		InvUtil.click(InvUtil.menuSlot(index), 40, ClickType.SWAP);
		clock.reset();
	}
}
