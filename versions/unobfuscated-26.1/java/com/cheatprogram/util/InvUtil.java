package com.cheatprogram.util;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/** Inventory helpers. "Inventory index" means Inventory#getItem order (0-8 hotbar, 9-35 main). */
public final class InvUtil {
	private static final Minecraft MC = Minecraft.getInstance();

	private InvUtil() {
	}

	public static ItemStack stack(int index) {
		return MC.player.getInventory().getItem(index);
	}

	public static int findHotbar(Predicate<ItemStack> filter) {
		for (int i = 0; i < 9; i++) if (filter.test(stack(i))) return i;
		return -1;
	}

	public static int findMain(Predicate<ItemStack> filter) {
		for (int i = 9; i < 36; i++) if (filter.test(stack(i))) return i;
		return -1;
	}

	public static int find(Predicate<ItemStack> filter) {
		int slot = findHotbar(filter);
		return slot >= 0 ? slot : findMain(filter);
	}

	/** Converts an inventory index to the matching slot id of the player's inventory menu. */
	public static int menuSlot(int index) {
		if (index < 9) return 36 + index;
		if (index < 36) return index;
		if (index < 40) return 8 - (index - 36);
		return 45;
	}

	public static void click(int menuSlot, int button, ContainerInput type) {
		MC.gameMode.handleContainerInput(MC.player.inventoryMenu.containerId, menuSlot, button, type, MC.player);
	}

	public static void select(int hotbarSlot) {
		if (hotbarSlot >= 0 && hotbarSlot < 9) MC.player.getInventory().setSelectedSlot(hotbarSlot);
	}

	/** True while it is safe to move items around in the player's own inventory. */
	public static boolean canUseInventory() {
		return MC.player != null && MC.player.containerMenu == MC.player.inventoryMenu && MC.player.inventoryMenu.getCarried().isEmpty();
	}
}
