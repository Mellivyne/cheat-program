package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Cosmetic attachments read from real inventory slots; no equipment or packets are changed. */
public final class WearableWeapons extends Module {
    public static final String[] PARTS = {"wear_back_left", "wear_back_right", "wear_leg_left", "wear_leg_right"};
    private final BoolSetting preview = add(new BoolSetting("preview", true));
    private final ModeSetting part = add(new ModeSetting("part", PARTS[0], PARTS));
    private final Attachment[] attachments = new Attachment[4];

    public WearableWeapons() {
        super("wearableweapons", Category.VISUAL);
        attachments[0] = new Attachment(0, "backLeft", true, -.07, .34, .26, 0, 0, -45, 100);
        attachments[1] = new Attachment(1, "backRight", true, .07, .34, .30, 0, 0, 45, 100);
        attachments[2] = new Attachment(2, "legLeft", false, .16, .24, .01, 0, 90, 0, 65);
        attachments[3] = new Attachment(3, "legRight", false, -.16, .24, .01, 0, -90, 0, 65);
    }

    public boolean showPreview() { return preview.on(); }
    public int selectedPart() { return Math.max(0, java.util.Arrays.asList(PARTS).indexOf(part.get())); }
    public void selectPart(int index) { if (index >= 0 && index < PARTS.length) part.set(PARTS[index]); }
    public Attachment attachment(int index) { return attachments[index]; }
    @Override public String suffix() { return mc.player == null ? "" : resolved().size() + " / 4"; }

    public final class Attachment {
        public final int index;
        public final BoolSetting shown;
        /** 0 selects the first unused matching weapon; 1..36 map directly to inventory slots 0..35. */
        public final NumberSetting slot, x, y, z, angleX, angleY, angleZ, size;
        Attachment(int index, String id, boolean enabled, double px, double py, double pz,
                   double rx, double ry, double rz, double scale) {
            this.index = index;
            shown = add(new BoolSetting(id + "Shown", enabled)).visibleWhen(() -> selectedPart() == index);
            slot = add(new NumberSetting(id + "Slot", 0, 0, 36, 1)).visibleWhen(() -> selectedPart() == index);
            x = add(new NumberSetting(id + "X", px, -1.5, 1.5, .01)).visibleWhen(() -> selectedPart() == index);
            y = add(new NumberSetting(id + "Y", py, -1.5, 1.5, .01)).visibleWhen(() -> selectedPart() == index);
            z = add(new NumberSetting(id + "Z", pz, -1.5, 1.5, .01)).visibleWhen(() -> selectedPart() == index);
            angleX = add(new NumberSetting(id + "AngleX", rx, -180, 180, 1, "°")).visibleWhen(() -> selectedPart() == index);
            angleY = add(new NumberSetting(id + "AngleY", ry, -180, 180, 1, "°")).visibleWhen(() -> selectedPart() == index);
            angleZ = add(new NumberSetting(id + "AngleZ", rz, -180, 180, 1, "°")).visibleWhen(() -> selectedPart() == index);
            size = add(new NumberSetting(id + "Size", scale, 10, 250, 5, "%")).visibleWhen(() -> selectedPart() == index);
        }
        public String name() { return Lang.mode(PARTS[index]); }
        public boolean accepts(ItemStack stack) {
            if (stack.isEmpty()) return false;
            if (index >= 2) return stack.is(Items.BOW) || stack.is(Items.CROSSBOW);
            return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(Items.TRIDENT)
                    || false || stack.is(Items.BOW) || stack.is(Items.CROSSBOW);
        }
        public List<NumberSetting> transforms() { return List.of(x, y, z, angleX, angleY, angleZ, size); }
        public void resetTransform() { for (NumberSetting setting : transforms()) setting.reset(); }
    }
    public record Resolved(Attachment attachment, int inventorySlot, ItemStack stack) {}

    /** Invalid, removed and already-used slots disappear immediately rather than caching phantom weapons. */
    public List<Resolved> resolved() {
        List<Resolved> result = new ArrayList<>(4);
        if (mc.player == null) return result;
        Set<Integer> used = new HashSet<>();
        // Explicit choices win over automatic choices regardless of body-part order.
        for (Attachment a : attachments) {
            int slot = a.slot.intVal() - 1;
            if (!a.shown.on() || slot < 0 || used.contains(slot)) continue;
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (a.accepts(stack)) { result.add(new Resolved(a, slot, stack)); used.add(slot); }
        }
        for (Attachment a : attachments) {
            if (!a.shown.on() || a.slot.intVal() != 0) continue;
            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = mc.player.getInventory().getItem(slot);
                if (!used.contains(slot) && a.accepts(stack)) {
                    result.add(new Resolved(a, slot, stack)); used.add(slot); break;
                }
            }
        }
        return result;
    }
}
