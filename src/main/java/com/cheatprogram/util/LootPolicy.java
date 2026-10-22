package com.cheatprogram.util;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Persisted text filters use exact registry IDs; invalid entries never broaden a filter. */
public final class LootPolicy {
    public static final String DEFAULT_WEAPONS = "wooden_sword, stone_sword, iron_sword, golden_sword, diamond_sword, netherite_sword, wooden_axe, stone_axe, iron_axe, golden_axe, diamond_axe, netherite_axe, bow, crossbow, trident, mace";
    private LootPolicy() { }

    public static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    public static Set<String> ids(String text) {
        Set<String> result = new HashSet<>();
        for (String part : text.split("[,;\\s]+")) {
            String id = normalize(part);
            if (id != null) result.add(id);
        }
        return Set.copyOf(result);
    }
    public static Map<String, Integer> quotas(String text) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String part : text.split("[,;\\s]+")) {
            String[] entry = part.split("=", -1);
            if (entry.length != 2) continue;
            String id = normalize(entry[0]);
            try {
                int count = Integer.parseInt(entry[1]);
                if (id != null && count > 0 && count <= 1000000) result.put(id, count);
            } catch (NumberFormatException ignored) { }
        }
        return Map.copyOf(result);
    }
    private static String normalize(String text) {
        String id = text.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return null;
        if (!id.contains(":")) id = "minecraft:" + id;
        return id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? id : null;
    }
    /** Includes equipped armour/offhand: an item already owned must not be taken again for a quota. */
    public static int ownedCount(ItemStack item) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        int count = 0;
        for (int index = 0; index < mc.player.getInventory().getContainerSize(); index++) {
            ItemStack owned = mc.player.getInventory().getItem(index);
            if (!owned.isEmpty() && owned.getItem() == item.getItem()) count += owned.getCount();
        }
        return count;
    }
}
