package com.cheatprogram.module.inventory;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.util.InventoryProtection;

/** Opt-in rules for automatic workers; the player's manual inventory clicks remain available. */
public final class ItemProtection extends Module {
	public final TextSetting lockedSlots = add(new TextSetting("lockedSlots", "", 256));
	public final BoolSetting named = add(new BoolSetting("named", true));
	public final BoolSetting enchanted = add(new BoolSetting("enchanted", true));
	public final BoolSetting shulkers = add(new BoolSetting("shulkers", true));
	public final TextSetting items = add(new TextSetting("items", "diamond, netherite_ingot, nether_star, elytra", 2048));
	public ItemProtection() { super("itemprotection", Category.INVENTORY); }
	@Override public String suffix() { return Integer.toString(InventoryProtection.protectedSlotCount()); }
}
