package com.cheatprogram.util;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.inventory.ItemProtection;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class InventoryProtection {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final ThreadLocal<Integer> AUTOMATIC = ThreadLocal.withInitial(() -> 0);
	private static String slotsText, itemsText;
	private static Set<Integer> slots = Set.of();
	private static Set<String> items = Set.of();
	private InventoryProtection() { }
	public static boolean isAutomatic() { return AUTOMATIC.get() > 0; }
	public static void automatic(Runnable action) {
		AUTOMATIC.set(AUTOMATIC.get() + 1);
		try { action.run(); } finally { int depth = AUTOMATIC.get() - 1; if (depth == 0) AUTOMATIC.remove(); else AUTOMATIC.set(depth); }
	}
	public static boolean protectedSlot(int index) {
		ItemProtection rules = ModuleManager.get(ItemProtection.class);
		if (rules == null || !rules.isEnabled() || MC.player == null) return false;
		parse(rules);
		return slots.contains(index) || protectedItem(MC.player.getInventory().getItem(index));
	}
	public static boolean protectedItem(ItemStack stack) {
		ItemProtection rules = ModuleManager.get(ItemProtection.class);
		if (rules == null || !rules.isEnabled() || stack.isEmpty()) return false;
		parse(rules);
		return rules.named.on() && stack.has(DataComponents.CUSTOM_NAME)
				|| rules.enchanted.on() && stack.isEnchanted()
				|| rules.shulkers.on() && stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock
				|| items.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}
	private static void parse(ItemProtection rules) {
		if (!rules.lockedSlots.get().equals(slotsText)) {
			slotsText = rules.lockedSlots.get(); Set<Integer> next = new HashSet<>();
			for (String value : slotsText.split("[,;\\s]+")) try {
				String[] range = value.split("-"); if (range.length == 0) continue;
				int a = Integer.parseInt(range[0]), b = range.length > 1 ? Integer.parseInt(range[1]) : a;
				for (int i = Math.max(1, a); i <= Math.min(36, b); i++) next.add(i - 1);
			} catch (NumberFormatException ignored) { }
			slots = Set.copyOf(next);
		}
		if (!rules.items.get().equals(itemsText)) {
			itemsText = rules.items.get(); Set<String> next = new HashSet<>();
			for (String part : itemsText.toLowerCase(Locale.ROOT).split("[,;\\s]+")) if (!part.isBlank()) next.add(part.contains(":") ? part : "minecraft:" + part);
			items = Set.copyOf(next);
		}
	}
	/** Guard sources and explicit swap destinations; quick-move may still add items to inventory. */
	public static boolean allowClick(AbstractContainerMenu menu, int slot, int button, ClickType type) {
		if (MC.player == null || menu == null) return true;
		if (slot >= 0 && slot < menu.slots.size()) {
			var source = menu.getSlot(slot);
			if (source.container == MC.player.getInventory() && protectedSlot(source.getContainerSlot())) return false;
		}
		if (type == ClickType.SWAP && (button >= 0 && button <= 8 || button == 40) && protectedSlot(button)) return false;
		// Double-click collect can pull matching protected stacks from other inventory positions.
		if (type == ClickType.PICKUP_ALL && !menu.getCarried().isEmpty()) {
			for (int i = 0; i < 41; i++) if (protectedSlot(i) && ItemStack.isSameItemSameComponents(menu.getCarried(), MC.player.getInventory().getItem(i))) return false;
		}
		return true;
	}
	public static int protectedSlotCount() {
		if (MC.player == null) return 0;
		int n = 0; for (int i = 0; i < 41; i++) if (protectedSlot(i)) n++;
		return n;
	}
}
