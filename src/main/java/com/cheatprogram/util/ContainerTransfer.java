package com.cheatprogram.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Only vanilla clicks against the player's currently owned, genuinely opened storage menu. */
public final class ContainerTransfer {
    private static final Minecraft MC = Minecraft.getInstance();
    private ContainerTransfer() { }
    public static AbstractContainerMenu visibleStorage() {
        if (MC.player == null || !(MC.screen instanceof ContainerScreen || MC.screen instanceof ShulkerBoxScreen)) return null;
        AbstractContainerMenu menu = ((AbstractContainerScreen<?>) MC.screen).getMenu();
        int slots = menu.slots.size() - 36;
        return menu == MC.player.containerMenu && slots > 0 && slots <= 54 ? menu : null;
    }
    public static int playerSlot(AbstractContainerMenu menu, int index) {
        for (Slot slot : menu.slots) if (slot.container == MC.player.getInventory() && slot.getContainerSlot() == index) return slot.index;
        return -1;
    }
    public static boolean canStore(AbstractContainerMenu menu, int storageSlots, ItemStack stack) {
        for (int i = 0; i < storageSlots; i++) {
            Slot slot = menu.getSlot(i);
            ItemStack current = slot.getItem();
            if (slot.mayPlace(stack) && (current.isEmpty() || ItemStack.isSameItemSameComponents(current, stack)
                    && current.getCount() < slot.getMaxStackSize(stack))) return true;
        }
        return false;
    }
    public static int capacity(AbstractContainerMenu menu, ItemStack stack) {
        int total = 0;
        for (Slot slot : menu.slots) {
            if (!destination(slot, stack)) continue;
            total += Math.max(0, slot.getMaxStackSize(stack) - slot.getItem().getCount());
        }
        return total;
    }
    private static boolean destination(Slot slot, ItemStack stack) {
        int index = slot.getContainerSlot();
        return slot.container == MC.player.getInventory() && index >= 0 && index < 36
                && slot.mayPlace(stack)
                && (slot.getItem().isEmpty() || ItemStack.isSameItemSameComponents(slot.getItem(), stack));
    }
    public static void click(AbstractContainerMenu menu, int slot, int button, ClickType type) {
        if (MC.player == null || MC.gameMode == null || MC.player.containerMenu != menu) return;

        MC.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, MC.player);
    }
    /** A partial quota never quick-moves a full stack. User cursor contents are never adopted. */
    public static void take(AbstractContainerMenu menu, int sourceIndex, int requested) {
        if (!menu.getCarried().isEmpty() || requested <= 0 || MC.player.containerMenu != menu) return;
        Slot source = menu.getSlot(sourceIndex);
        ItemStack original = source.getItem().copy();
        int amount = Math.min(Math.min(original.getCount(), requested), capacity(menu, original));
        if (amount <= 0 || !source.mayPickup(MC.player)) return;
        
        
        if (amount == original.getCount()) {
            click(menu, sourceIndex, 0, ClickType.QUICK_MOVE);
            return;
        }
        // Keep the untouched remainder in its original source. If that is not legal, defer the split.
        if (!source.mayPlace(original)) return;
        List<Slot> destinations = new ArrayList<>();
        for (Slot slot : menu.slots) if (destination(slot, original)) destinations.add(slot);
        click(menu, sourceIndex, 0, ClickType.PICKUP);
        if (!ItemStack.isSameItemSameComponents(menu.getCarried(), original)) return;
        int remaining = amount;
        for (Slot target : destinations) {
            int room = Math.min(remaining, target.getMaxStackSize(original) - target.getItem().getCount());
            for (int i = 0; i < room && !menu.getCarried().isEmpty(); i++) {
                if (!destination(target, original)) break;
                int before = menu.getCarried().getCount();
                click(menu, target.index, 1, ClickType.PICKUP);
                int moved = before - menu.getCarried().getCount();
                if (moved != 1) break;
                remaining--;
            }
            if (remaining <= 0 || menu.getCarried().isEmpty()) break;
        }
        if (!menu.getCarried().isEmpty() && ItemStack.isSameItemSameComponents(menu.getCarried(), original)
                && source.mayPlace(menu.getCarried()) && (source.getItem().isEmpty()
                || ItemStack.isSameItemSameComponents(source.getItem(), menu.getCarried()))) {
            click(menu, sourceIndex, 0, ClickType.PICKUP);
        }
    }
}
