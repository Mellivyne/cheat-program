package com.cheatprogram.util;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.Lang;
import com.cheatprogram.module.inventory.AutoArmor;
import com.cheatprogram.module.inventory.ChestStealer;
import com.cheatprogram.module.inventory.InvCleaner;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

/** The three enabled inventory workers share one preparation/open/loot/close cycle. */
public final class CombatInventory {
	private CombatInventory() { }

	public static boolean linked() {
		AutoArmor armor = ModuleManager.get(AutoArmor.class);
		InvCleaner cleaner = ModuleManager.get(InvCleaner.class);
		ChestStealer stealer = ModuleManager.get(ChestStealer.class);
		return armor != null && armor.isEnabled() && cleaner != null && cleaner.isEnabled()
				&& stealer != null && stealer.isEnabled();
	}
	public static String summary(String normal) { return linked() ? Lang.t("inventory.combatCycle") + ", " + normal : normal; }
	public static String suffix() { return linked() ? Lang.t("inventory.combatCycle") : ""; }
	public static String description(String normal) { return linked() ? normal + "\n" + Lang.t("inventory.combatCycleHint") : normal; }

	/** Preserve actual equipment and supplies, plus items explicitly protected by the user. */
	public static boolean keep(ItemStack stack) {
		return keep(stack, false);
	}
	public static boolean keep(ItemStack stack, boolean tools) {
		if (stack.isEmpty()) return false;
		if (InventoryProtection.protectedItem(stack)) return true;
		if(com.cheatprogram.module.world.FarmAssist.keepsSeed(stack))return true;
		// Planting items subclass BlockItem too, but are not building blocks or immediate combat supplies.
		if (stack.is(Items.WHEAT_SEEDS) || stack.is(Items.BEETROOT_SEEDS) || stack.is(Items.MELON_SEEDS)
				|| stack.is(Items.PUMPKIN_SEEDS) || stack.is(Items.TORCHFLOWER_SEEDS) || stack.is(Items.PITCHER_POD)
				|| stack.is(Items.COCOA_BEANS) || stack.is(Items.NETHER_WART) || stack.is(Items.DEAD_BUSH)) return false;
		if (stack.getItem() instanceof BlockItem || stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES)) return true;
		if (tools && (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)
				|| stack.is(Items.SHEARS) || stack.is(Items.FISHING_ROD))) return true;
		if (stack.has(DataComponents.FOOD) && !stack.is(Items.ROTTEN_FLESH) && !stack.is(Items.POISONOUS_POTATO)
				&& !stack.is(Items.SPIDER_EYE) && !stack.is(Items.PUFFERFISH)) return true;
		Equippable equipment = stack.get(DataComponents.EQUIPPABLE);
		if (equipment != null && (equipment.slot() == EquipmentSlot.HEAD || equipment.slot() == EquipmentSlot.CHEST
				|| equipment.slot() == EquipmentSlot.LEGS || equipment.slot() == EquipmentSlot.FEET)) return true;
		return stack.is(Items.BOW) || stack.is(Items.CROSSBOW) || stack.is(Items.TRIDENT) || stack.is(Items.MACE)
				|| stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW) || stack.is(Items.TIPPED_ARROW)
				|| stack.is(Items.SHIELD) || stack.is(Items.ELYTRA) || stack.is(Items.TOTEM_OF_UNDYING)
				|| stack.is(Items.ENDER_PEARL) || stack.is(Items.END_CRYSTAL) || stack.is(Items.WIND_CHARGE)
				|| stack.is(Items.POTION) || stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION)
				|| stack.is(Items.EXPERIENCE_BOTTLE) || stack.is(Items.FIREWORK_ROCKET)
				|| stack.is(Items.WATER_BUCKET) || stack.is(Items.LAVA_BUCKET) || stack.is(Items.POWDER_SNOW_BUCKET)
				|| stack.is(Items.MILK_BUCKET)
				|| stack.is(Items.BUCKET) || stack.is(Items.FLINT_AND_STEEL) || stack.is(Items.SNOWBALL);
	}
}
