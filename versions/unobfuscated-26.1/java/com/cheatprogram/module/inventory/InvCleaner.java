package com.cheatprogram.module.inventory;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.RangeSetting;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.util.Clock;
import com.cheatprogram.util.CombatInventory;
import com.cheatprogram.util.InvUtil;
import com.cheatprogram.util.InventoryAutomation;
import com.cheatprogram.util.InventoryPoses;
import com.cheatprogram.util.ContainerTransfer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.cheatprogram.gui.InventoryPreviewScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

public class InvCleaner extends Module {
	private final BoolSetting instant = add(new BoolSetting("instant", false));
	private final RangeSetting delay = add(new RangeSetting("delay", 80, 120, 0, 500, 10, "ms")).visibleWhen(() -> !instant.on());
	private final BoolSetting autoClose = add(new BoolSetting("autoClose", false));
	private final BoolSetting openInv = add(new BoolSetting("openInv", true)).visibleWhen(() -> false);
	private final ModeSetting operationMode = add(InventoryAutomation.operationMode(openInv::on));
	private final ModeSetting cleanMode = add(new ModeSetting("cleanMode", "combat_keep", "combat_keep", "junk_ids") {
		@Override public void load(JsonElement element) { super.load(element == null ? new JsonPrimitive("combat_keep") : element); }
	});
	private final BoolSetting keepTools = add(new BoolSetting("keepTools", false)).visibleWhen(() -> cleanMode.is("combat_keep"));
	private final TextSetting keepItems = add(new TextSetting("keepItems", "", 2048)).visibleWhen(() -> cleanMode.is("combat_keep"));
	private final TextSetting junk = add(new TextSetting("junk",
			"rotten_flesh, poisonous_potato, spider_eye, pufferfish, wheat_seeds, beetroot_seeds, dead_bush, egg", 512)).visibleWhen(() -> cleanMode.is("junk_ids"));
	private final ModeSetting junkAction = add(new ModeSetting("junkAction", "junk_drop", "junk_drop", "junk_return", "junk_keep"));
	private final Clock clock = new Clock();
	private AbstractContainerMenu returnMenu;
	private String parsedFrom;
	private Set<String> junkIds = Set.of();
	private Object workPlayer, workLevel, workScreen;
	private final Map<Integer, ItemStack> rejected = new HashMap<>();
	private boolean wasLinked;
	private String policy;
	private String parsedKeep;
	private Set<String> keepIds = Set.of();

	public InvCleaner() {
		super("invcleaner", Category.INVENTORY);
	}
	@Override public String summary() { return CombatInventory.summary(super.summary()); }
	@Override public String suffix() { return CombatInventory.suffix(); }
	@Override public String description() { return CombatInventory.description(super.description()); }

	@Override
	public void onTick() {
		if (workPlayer != mc.player || workLevel != mc.level) resetSession();
		boolean linked = CombatInventory.linked();
		if (linked != wasLinked) { wasLinked = linked; rejected.clear(); clock.reset(0); }
		String currentPolicy = cleanMode.get() + "|" + keepTools.on() + "|" + keepItems.get() + "|" + junk.get() + "|" + effectiveJunkAction();
		if (!currentPolicy.equals(policy)) { policy = currentPolicy; rejected.clear(); clock.reset(0); InventoryAutomation.forgetOwnChanges(this); }
		if (workScreen != mc.screen) {
			workScreen = mc.screen; clock.reset(0);
			InventoryAutomation.cancelCloseOwnInventory(this);
		}
		ChestStealer stealer = ModuleManager.get(ChestStealer.class);
		if (stealer != null && stealer.isEnabled() && mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) return;
		if (effectiveJunkAction().equals("junk_keep")) { completed(); return; }
		if (effectiveJunkAction().equals("junk_return") || ContainerTransfer.visibleStorage() != null) { cleanOpenedStorage(); return; }
		if (!InventoryAutomation.ownInventoryReady()) return;
		// Equipping gets the first complete pass, including its configured delay and follow-screen opening.
		AutoArmor armor = ModuleManager.get(AutoArmor.class);
		if (armor != null && armor.isEnabled() && armor.hasPendingWork()) return;
		if (!hasOwnPendingWork()) { completed(); return; }
		if (!instant.on() && !clock.ready()) return;
		boolean claimed = false;
		for (int i = 0; i < 36; i++) {
			ItemStack stack = InvUtil.stack(i);
			if (stack.isEmpty() || wasRejected(i, stack)) continue;
			if (discard(stack) && mc.player.inventoryMenu.getSlot(InvUtil.menuSlot(i)).mayPickup(mc.player)) {
				if (!InventoryAutomation.prepareOwnInventory(operationMode.is("follow"))) return;
				if (!claimed && !InventoryAutomation.claim(mc.player.inventoryMenu, InvUtil.menuSlot(i))) return;
				claimed = true; InventoryAutomation.highlight(mc.player.inventoryMenu, InvUtil.menuSlot(i));
				ItemStack before = stack.copy();
				InventoryAutomation.clickOwnInventory(InvUtil.menuSlot(i), 1, ContainerInput.THROW);
				if (!mc.player.inventoryMenu.getCarried().isEmpty()) return;
				if (ItemStack.matches(before, InvUtil.stack(i))) rejected.put(i, before);
				else {
					int index = i;
					InventoryAutomation.watchOwnChange(this, () -> ItemStack.matches(before, InvUtil.stack(index)), () -> rejected.put(index, before));
					InventoryPoses.operated(this, operationMode.get(), null, true);
				}
				clock.reset((long) delay.random());
				if (!instant.on()) break;
			}
		}
		if (!hasOwnPendingWork()) completed();
	}
	private void completed() {
		if (!mc.player.inventoryMenu.getCarried().isEmpty()) return;
		InventoryAutomation.requestCloseOwnInventory(this, () -> autoClose.on() || CombatInventory.linked());
	}
	private void resetSession() {
		workPlayer = mc.player; workLevel = mc.level; workScreen = mc.screen; rejected.clear(); clock.reset(0);
		InventoryAutomation.forgetOwnChanges(this);
		InventoryAutomation.cancelCloseOwnInventory(this);
		returnMenu = null;
	}
	@Override public void onEnable() { resetSession(); }
	@Override public void onDisable() { resetSession(); }
	private boolean wasRejected(int index, ItemStack stack) {
		ItemStack refusal = rejected.get(index);
		if (refusal == null) return false;
		if (ItemStack.matches(refusal, stack)) return true;
		rejected.remove(index); return false;
	}
	/** A return policy remains pending while no real box is open, and a refusal is never completion. */
	public boolean hasPendingWork() {
		if (mc.player == null || effectiveJunkAction().equals("junk_keep")) return false;
		if (!InventoryAutomation.ownChangesSettled(this)) return true;
		for (int index = 0; index < 36; index++) if (junkSlot(index)) return true;
		return false;
	}
	/** Only executable own-menu work delays equipping/closing/opening the next real container. */
	public boolean hasOwnPendingWork() {
		if (!InventoryAutomation.canUseOwnInventory() || !effectiveJunkAction().equals("junk_drop")) return false;
		for (int index = 0; index < 36; index++) {
			ItemStack stack = InvUtil.stack(index);
			if (junkSlot(index) && !wasRejected(index, stack)
					&& mc.player.inventoryMenu.getSlot(InvUtil.menuSlot(index)).mayPickup(mc.player)) return true;
		}
		return false;
	}
	public boolean refusedWork() {
		if (mc.player == null || effectiveJunkAction().equals("junk_keep")) return false;
		ChestStealer stealer = ModuleManager.get(ChestStealer.class);
		if (stealer != null && stealer.isEnabled() && stealer.refusedWork()) return true;
		for (int index = 0; index < 36; index++) if (junkSlot(index) && wasRejected(index, InvUtil.stack(index))) return true;
		return false;
	}
	private boolean junkSlot(int index) {
		return !InvUtil.stack(index).isEmpty() && discard(InvUtil.stack(index));
	}
	private String effectiveJunkAction() {
		ChestStealer stealer = ModuleManager.get(ChestStealer.class);
		return stealer != null && stealer.isEnabled() ? stealer.junkAction() : junkAction.get();
	}
	/** Used by ChestStealer before it takes new loot; no background clicks occur in its owned menu. */
	public boolean junkForContainer(ItemStack stack) {
		return !stack.isEmpty()
				
				&& (cleanMode.is("combat_keep") ? !keepCombat(stack)
				: ids().contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
	}
	private void cleanOpenedStorage() {
		AbstractContainerMenu menu = ContainerTransfer.visibleStorage();
		if (menu == null || !InventoryAutomation.idle(menu) || !mc.player.inventoryMenu.getCarried().isEmpty()) return;
		int storageSlots = menu.slots.size() - 36;
		if (returnMenu != menu) { returnMenu = menu; rejected.clear(); }
		if (!instant.on() && !clock.ready()) return;
		boolean claimed = false;
		for (int index = 0; index < 36; index++) {
			ItemStack stack = InvUtil.stack(index);
			int slot = ContainerTransfer.playerSlot(menu, index);
			if (!junkSlot(index) || wasRejected(index, stack) || slot < 0 || !menu.getSlot(slot).mayPickup(mc.player)) continue;
			// A full box leaves the stack in the backpack; it is not a failed/drop transaction.
			if (effectiveJunkAction().equals("junk_return") && !ContainerTransfer.canStore(menu, storageSlots, stack)) continue;
			if (!claimed && !InventoryAutomation.claim(menu, slot)) return;
			claimed = true; InventoryAutomation.highlight(menu, slot);
			ItemStack before = stack.copy();
			boolean returning = effectiveJunkAction().equals("junk_return");
			ContainerTransfer.click(menu, slot, returning ? 0 : 1, returning ? ContainerInput.QUICK_MOVE : ContainerInput.THROW);
			if (ItemStack.matches(before, InvUtil.stack(index))) rejected.put(index, before);
			else {
				int inventoryIndex = index;
				InventoryAutomation.watchOwnChange(this, () -> ItemStack.matches(before, InvUtil.stack(inventoryIndex)),
						() -> rejected.put(inventoryIndex, before));
				InventoryPoses.operated(this, operationMode.get(), null, true);
			}
			clock.reset((long) delay.random());
			if (!menu.getCarried().isEmpty() || !instant.on()) return;
		}
	}
	public record PreviewEntry(int slot, String name, String id, int count) { }
	/** Snapshot only: neither enabling this screen nor refreshing it sends an inventory transaction. */
	public List<PreviewEntry> previewEntries() {
		if (mc.player == null) return List.of();
		List<PreviewEntry> entries = new ArrayList<>();
		for (int i = 0; i < 36; i++) {
			ItemStack stack = InvUtil.stack(i);
			if (stack.isEmpty()) continue;
			String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			if (discard(stack) && mc.player.inventoryMenu.getSlot(InvUtil.menuSlot(i)).mayPickup(mc.player))
				entries.add(new PreviewEntry(i + 1, stack.getHoverName().getString(), id, stack.getCount()));
		}
		return List.copyOf(entries);
	}
	public void preview(Screen parent) { mc.setScreen(new InventoryPreviewScreen(parent, this)); }
	private boolean discard(ItemStack stack) {
		if (!junkForContainer(stack)) return false;
		ChestStealer stealer = ModuleManager.get(ChestStealer.class);
		return stealer == null || !stealer.isEnabled() || !stealer.keepsConfiguredLoot(stack);
	}
	/** Used by nearby looting as well, so it does not immediately take items the cleaner will discard. */
	public boolean keepForLoot(ItemStack stack) {
		return (cleanMode.is("combat_keep") ? keepCombat(stack) : !discard(stack));
	}
	private boolean keepCombat(ItemStack stack) {
		if (CombatInventory.keep(stack, keepTools.on())) return true;
		if (!keepItems.get().equals(parsedKeep)) {
			parsedKeep = keepItems.get(); keepIds = parseIds(parsedKeep);
		}
		return keepIds.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
	}
	private static Set<String> parseIds(String text) {
		Set<String> result = new HashSet<>();
		for (String part : text.split("[,;\\s]+")) {
			String id = part.trim().toLowerCase(Locale.ROOT);
			if (!id.isEmpty()) result.add(id.contains(":") ? id : "minecraft:" + id);
		}
		return Set.copyOf(result);
	}

	private Set<String> ids() {
		if (!junk.get().equals(parsedFrom)) {
			parsedFrom = junk.get();
			rejected.clear();
			junkIds = parseIds(parsedFrom);
		}
		return junkIds;
	}
}
