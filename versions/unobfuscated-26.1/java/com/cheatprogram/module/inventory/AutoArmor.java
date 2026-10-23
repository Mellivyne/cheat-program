package com.cheatprogram.module.inventory;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.RangeSetting;
import com.cheatprogram.util.Clock;
import com.cheatprogram.util.CombatInventory;
import com.cheatprogram.util.InvUtil;
import com.cheatprogram.util.InventoryAutomation;
import com.cheatprogram.util.InventoryPoses;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;

import java.util.HashMap;
import java.util.Map;

public class AutoArmor extends Module {
	private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	private final BoolSetting instant = add(new BoolSetting("instant", false));
	private final RangeSetting delay = add(new RangeSetting("delay", 100, 150, 0, 500, 10, "ms")).visibleWhen(() -> !instant.on());
	private final BoolSetting autoClose = add(new BoolSetting("autoClose", false));
	// Retained for old profiles; operationMode is the sole presentation choice.
	private final BoolSetting openInv = add(new BoolSetting("openInv", false)).visibleWhen(() -> false);
	private final ModeSetting operationMode = add(InventoryAutomation.operationMode(openInv::on));
	private final Clock clock = new Clock();
	private Object workPlayer, workLevel, workScreen;
	private boolean cursorBlocked;
	private final Map<Integer, Rejected> rejected = new HashMap<>();
	private record Rejected(ItemStack source, ItemStack equipped) { }

	public AutoArmor() {
		super("autoarmor", Category.INVENTORY);
	}
	@Override public String summary() { return CombatInventory.summary(super.summary()); }
	@Override public String suffix() { return CombatInventory.suffix(); }
	@Override public String description() { return CombatInventory.description(super.description()); }

	@Override
	public void onTick() {
		if (workPlayer != mc.player || workLevel != mc.level) resetSession();
		if (workScreen != mc.screen) {
			workScreen = mc.screen; clock.reset(0);
			InventoryAutomation.cancelCloseOwnInventory(this);
		}
		if (!InventoryAutomation.ownInventoryReady()) return;
		if (!hasPendingWork()) { completed(); return; }
		if (!instant.on() && !clock.ready()) return;
		boolean claimed = false;
		for (EquipmentSlot slot : SLOTS) {
			ItemStack current = mc.player.getItemBySlot(slot);
			int bestIndex = candidate(slot);
			if (bestIndex < 0) continue;
			int armorMenuSlot = 8 - slot.getIndex();
			int source = InvUtil.menuSlot(bestIndex);
			if (!InventoryAutomation.prepareOwnInventory(operationMode.is("follow"))) return;
			if (!claimed && !InventoryAutomation.claim(mc.player.inventoryMenu, source)) return;
			claimed = true; InventoryAutomation.highlight(mc.player.inventoryMenu, source);
			ItemStack beforeSource = InvUtil.stack(bestIndex).copy(), beforeEquipped = current.copy();
			if (!current.isEmpty()) {
				// Exchange in place, including a full backpack, without throwing the old armour away.
				InventoryAutomation.clickOwnInventory(source, 0, ContainerInput.PICKUP);
				InventoryAutomation.clickOwnInventory(armorMenuSlot, 0, ContainerInput.PICKUP);
				InventoryAutomation.clickOwnInventory(source, 0, ContainerInput.PICKUP);
			} else {
				InventoryAutomation.clickOwnInventory(source, 0, ContainerInput.QUICK_MOVE);
			}
			if (!mc.player.inventoryMenu.getCarried().isEmpty()) { cursorBlocked = true; return; }
			if (ItemStack.matches(beforeSource, InvUtil.stack(bestIndex))
					&& ItemStack.matches(beforeEquipped, mc.player.getItemBySlot(slot))) {
				// A slot which refuses a move must not hold another inventory worker or auto-close forever.
				rejected.put(armorMenuSlot * 36 + bestIndex, new Rejected(beforeSource, beforeEquipped));
			} else {
				InventoryAutomation.watchOwnChange(this,
						() -> ItemStack.matches(beforeSource, InvUtil.stack(bestIndex)) && ItemStack.matches(beforeEquipped, mc.player.getItemBySlot(slot)),
						() -> rejected.put(armorMenuSlot * 36 + bestIndex, new Rejected(beforeSource, beforeEquipped)));
				InventoryPoses.operated(this, operationMode.get(), null, true);
			}
			clock.reset((long) delay.random());
			if (!instant.on()) break;
		}
		if (!hasPendingWork()) completed();
	}
	private void completed() {
		if (!mc.player.inventoryMenu.getCarried().isEmpty()) return;
		// A new screen is also a completed pass when silent work already finished before it opened.
		InventoryAutomation.requestCloseOwnInventory(this, () -> autoClose.on() || CombatInventory.linked());
	}
	private void resetSession() {
		workPlayer = mc.player; workLevel = mc.level; workScreen = mc.screen; rejected.clear(); cursorBlocked = false; clock.reset(0);
		InventoryAutomation.forgetOwnChanges(this);
		InventoryAutomation.cancelCloseOwnInventory(this);
	}
	@Override public void onEnable() { resetSession(); }
	@Override public void onDisable() { resetSession(); }
	/** Ignores untouchable armour, rather than blocking completion on a binding-cursed slot. */
	public boolean hasPendingWork() {
		if (mc.player != null && !InventoryAutomation.ownChangesSettled(this)) return true;
		if (!InventoryAutomation.canUseOwnInventory()) return false;
		for (EquipmentSlot slot : SLOTS) if (candidate(slot) >= 0) return true;
		return false;
	}
	public boolean refusedWork() {
		if (mc.player == null) return false;
		if (cursorBlocked) return true;
		for (EquipmentSlot slot : SLOTS) {
			ItemStack current = mc.player.getItemBySlot(slot);
			int armorSlot = 8 - slot.getIndex();
			if (current.is(Items.ELYTRA)
					|| !mc.player.inventoryMenu.getSlot(armorSlot).mayPickup(mc.player)) continue;
			for (int index = 0; index < 36; index++) {
				Rejected refusal = rejected.get(armorSlot * 36 + index);
				if (refusal != null
						&& ItemStack.matches(refusal.source, InvUtil.stack(index))
						&& ItemStack.matches(refusal.equipped, current) && score(refusal.source, slot) > score(current, slot)) return true;
			}
		}
		return false;
	}
	private int candidate(EquipmentSlot slot) {
		ItemStack current = mc.player.getItemBySlot(slot);
		int armorSlot = 8 - slot.getIndex();
		if (current.is(Items.ELYTRA) || !mc.player.inventoryMenu.getSlot(armorSlot).mayPickup(mc.player)) return -1;
		int best = -1;
		double bestScore = score(current, slot);
		for (int index = 0; index < 36; index++) {

			ItemStack stack = InvUtil.stack(index);
			Rejected refusal = rejected.get(armorSlot * 36 + index);
			if (refusal != null && ItemStack.matches(refusal.source, stack) && ItemStack.matches(refusal.equipped, current)) continue;
			double candidateScore = score(stack, slot);
			if (candidateScore > bestScore && mc.player.inventoryMenu.getSlot(armorSlot).mayPlace(stack)
					&& mc.player.inventoryMenu.getSlot(InvUtil.menuSlot(index)).mayPickup(mc.player)) {
				bestScore = candidateScore; best = index;
			}
		}
		return best;
	}

	private static double score(ItemStack stack, EquipmentSlot slot) {
		if (stack.isEmpty()) return 0;
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		if (equippable == null || equippable.slot() != slot) return -1;
		ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		double[] sum = new double[2];
		modifiers.forEach(slot, (attribute, modifier) -> {
			if (attribute.value() == Attributes.ARMOR.value()) sum[0] += modifier.amount();
			else if (attribute.value() == Attributes.ARMOR_TOUGHNESS.value()) sum[1] += modifier.amount();
		});
		double durability = stack.isDamageableItem() ? 1.0 - stack.getDamageValue() / (double) Math.max(1, stack.getMaxDamage()) : 1.0;
		return sum[0] * 10 + sum[1] * 3 + (stack.isEnchanted() ? 2 : 0) + durability;
	}
}
