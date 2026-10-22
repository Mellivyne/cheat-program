package com.cheatprogram.util;

import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.inventory.AutoArmor;
import com.cheatprogram.module.inventory.InvCleaner;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;
import java.util.function.BooleanSupplier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/** Shared pacing and user-input ownership for automatic inventory transactions. */
public final class InventoryAutomation {
	private static final Minecraft MC = Minecraft.getInstance();
	private static long manualUntil, manualRevision;
	private static Object contextPlayer, contextLevel, manualScreen;
	private static boolean claimedThisTick;
	private static AbstractContainerMenu highlightedMenu;
	private static int highlightedSlot = -1;
	private static long highlightUntil;
	private static CloseRequest closeRequest;
	private static long tickSerial, lastOwnActionTick = Long.MIN_VALUE;
	private static final Map<Module, Long> ownChanges = new LinkedHashMap<>();
	private static final List<OwnWatch> ownWatches = new ArrayList<>();
	private record CloseRequest(Object player, Object level, AbstractContainerMenu menu, AbstractContainerScreen<?> screen, Map<Module, BooleanSupplier> owners) { }
	private record OwnWatch(Module owner, BooleanSupplier restored, Runnable refused, long expires) { }

	private InventoryAutomation() { }
	/** Old profiles retain openInv's presentation choice until they explicitly save the new mode. */
	public static ModeSetting operationMode(BooleanSupplier legacyOpenInventory) {
		return new ModeSetting("operationMode", legacyOpenInventory.getAsBoolean() ? "follow" : "silent", "silent", "follow") {
			@Override public void load(JsonElement element) {
				super.load(element == null ? new JsonPrimitive(legacyOpenInventory.getAsBoolean() ? "follow" : "silent") : element);
			}
		};
	}
	/** Called once per Minecraft tick, including ticks while a screen has paused world simulation. */
	public static void beginTick() {
		tickSerial++;
		claimedThisTick = false;
		if (contextPlayer != MC.player || contextLevel != MC.level) {
			contextPlayer = MC.player; contextLevel = MC.level;
			manualUntil = 0; manualScreen = null;
			highlightedMenu = null; highlightedSlot = -1; closeRequest = null;
			lastOwnActionTick = Long.MIN_VALUE;
			ownChanges.clear(); ownWatches.clear();
		}
		long now = System.currentTimeMillis();
		ownWatches.removeIf(watch -> {
			if (!watch.owner.isEnabled() || now >= watch.expires) return true;
			if (watch.restored.getAsBoolean()) { watch.refused.run(); return true; }
			return false;
		});
		if (manualScreen != MC.screen) { manualUntil = 0; manualScreen = null; }
		if (closeRequest != null && !matches(closeRequest)) closeRequest = null;
	}
	public static long manualRevision() { return manualRevision; }
	public static void manualInteraction() {
		manualRevision++;
		manualScreen = MC.screen;
		manualUntil = System.currentTimeMillis() + 900;
		// A user resuming work in this screen cancels a previous completion request.
		closeRequest = null;
		// A deliberate user move can restore a stack too; it is not a rejected automatic transaction.
		ownWatches.clear();
	}
	public static boolean idle(AbstractContainerMenu menu) {
		if (MC.player == null || MC.level == null || MC.gameMode == null || MC.isPaused() || !MC.isWindowActive() || MC.getOverlay() != null
				|| !MC.player.isAlive() || MC.player.isSpectator() || !menu.getCarried().isEmpty()
				|| manualScreen == MC.screen && System.currentTimeMillis() < manualUntil) return false;
		long window = MC.getWindow().handle();
		return GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS
				&& GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) != GLFW.GLFW_PRESS;
	}
	public static boolean ownInventoryReady() {
		return canUseOwnInventory() && idle(MC.player.inventoryMenu)
				&& (MC.screen == null || isOwnInventoryScreen());
	}
	/** Creative's item picker is a different menu, even though its inventory tab edits inventoryMenu. */
	public static boolean canUseOwnInventory() {
		return ownInventoryContext() && MC.player.inventoryMenu.getCarried().isEmpty()
				&& MC.player.containerMenu.getCarried().isEmpty();
	}
	private static boolean ownInventoryContext() {
		if (MC.player == null) return false;
		return MC.player.containerMenu == MC.player.inventoryMenu
				|| MC.gameMode != null && MC.player.hasInfiniteMaterials()
					&& MC.screen instanceof CreativeModeInventoryScreen creative
					&& MC.player.containerMenu == creative.getMenu();
	}
	private static boolean isOwnInventoryScreen() {
		return MC.screen instanceof InventoryScreen || MC.screen instanceof CreativeModeInventoryScreen;
	}
	/** Same inventory-tab transaction paths used by the vanilla creative screen, never item-picker slots. */
	public static void clickOwnInventory(int slot, int button, ContainerInput type) {
		// PICKUP exchanges deliberately hold a stack on the cursor between their three synchronous clicks.
		if (!ownInventoryContext() || MC.gameMode == null) return;
		if (!InventoryProtection.allowClick(MC.player.inventoryMenu, slot, button, type)) return;
		lastOwnActionTick = tickSerial;
		if (MC.screen instanceof CreativeModeInventoryScreen && MC.player.hasInfiniteMaterials()) {
			if (type == ContainerInput.THROW) {
				var source = MC.player.inventoryMenu.getSlot(slot);
				if (!source.hasItem() || !source.mayPickup(MC.player)) return;
				ItemStack dropped = source.remove(button == 0 ? 1 : source.getItem().getCount());
				if (!dropped.isEmpty()) {
					MC.player.drop(dropped, true);
					MC.gameMode.handleCreativeModeItemDrop(dropped);
					MC.gameMode.handleCreativeModeItemAdd(source.getItem().copy(), slot);
				}
			} else MC.player.inventoryMenu.clicked(slot, button, type, MC.player);
			MC.player.inventoryMenu.broadcastChanges();
		} else InvUtil.click(slot, button, type);
	}
	/** Called only after a concrete operation was found; never replaces another kind of screen. */
	public static boolean prepareOwnInventory(boolean follow) {
		if (!ownInventoryReady()) return false;
		if (follow && MC.screen == null) { MC.setScreen(new InventoryScreen(MC.player)); return ownInventoryReady(); }
		return true;
	}
	/** Opening the next box waits for both workers and the previous inventory transaction burst. */
	public static boolean readyForChestCycle() {
		if (!ownInventoryReady() || !ownActionsSettled()) return false;
		AutoArmor armor = ModuleManager.get(AutoArmor.class);
		InvCleaner cleaner = ModuleManager.get(InvCleaner.class);
		return !(armor != null && armor.isEnabled() && (armor.hasPendingWork() || armor.refusedWork()))
				&& !(cleaner != null && cleaner.isEnabled() && (cleaner.hasOwnPendingWork() || cleaner.refusedWork()));
	}
	/** Correctly predicted vanilla clicks may have no acknowledgement; keep a bounded rollback watch. */
	public static void watchOwnChange(Module owner, BooleanSupplier restored, Runnable refused) {
		long now = System.currentTimeMillis();
		ownChanges.put(owner, now);
		if (ownWatches.size() >= 512) ownWatches.remove(0);
		ownWatches.add(new OwnWatch(owner, restored, refused, now + Math.max(5000, ownSettleMillis() * 3)));
	}
	public static void forgetOwnChanges(Module owner) {
		ownChanges.remove(owner);
		ownWatches.removeIf(watch -> watch.owner == owner);
	}
	public static boolean ownChangesSettled(Module owner) {
		// Let predicted changes remain observable through one bounded rollback interval before declaring completion.
		Long changedAt = ownChanges.get(owner);
		return changedAt == null || lastOwnActionTick != tickSerial && System.currentTimeMillis() - changedAt >= ownSettleMillis();
	}
	private static long ownSettleMillis() {
		long quiet = 200;
		if (MC.getSingleplayerServer() == null && MC.player != null && MC.getConnection() != null) {
			var info = MC.getConnection().getPlayerInfo(MC.player.getUUID());
			if (info != null) quiet = Math.max(quiet, Math.min(5000, info.getLatency() * 2L + 200));
		}
		return quiet;
	}
	private static boolean ownActionsSettled() {
		if (lastOwnActionTick == tickSerial) return false;
		long now = System.currentTimeMillis(), quiet = ownSettleMillis();
		for (long changedAt : ownChanges.values()) if (now - changedAt < quiet) return false;
		return true;
	}
	public static boolean claim(AbstractContainerMenu menu, int slot) {
		if (!idle(menu)) return false;
		if (claimedThisTick) return false;
		claimedThisTick = true;
		highlightedMenu = menu; highlightedSlot = slot; highlightUntil = System.currentTimeMillis() + 450;
		return true;
	}
	public static int highlightedSlot(AbstractContainerMenu menu) {
		return menu == highlightedMenu && System.currentTimeMillis() < highlightUntil ? highlightedSlot : -1;
	}
	public static void highlight(AbstractContainerMenu menu, int slot) {
		highlightedMenu = menu; highlightedSlot = slot; highlightUntil = System.currentTimeMillis() + 450;
	}
	public static void requestCloseOwnInventory(Module owner, BooleanSupplier autoClose) {
		if (!isOwnInventoryScreen() || !canUseOwnInventory() || !autoClose.getAsBoolean()) return;
		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) MC.screen;
		if (closeRequest == null || closeRequest.player != MC.player || closeRequest.level != MC.level || closeRequest.screen != screen
				|| closeRequest.menu != MC.player.containerMenu) {
			closeRequest = new CloseRequest(MC.player, MC.level, MC.player.containerMenu, screen, new LinkedHashMap<>());
		}
		closeRequest.owners.put(owner, autoClose);
	}
	public static void cancelCloseOwnInventory(Module owner) {
		if (closeRequest == null) return;
		closeRequest.owners.remove(owner);
		if (closeRequest.owners.isEmpty()) closeRequest = null;
	}
	private static boolean matches(CloseRequest request) {
		return MC.player == request.player && MC.level == request.level && MC.screen == request.screen
				&& MC.player != null && MC.player.containerMenu == request.menu;
	}
	/** ModuleManager calls this after every module's post-tick: both inventory workers get their turn first. */
	public static void finishTick() {
		CloseRequest request = closeRequest;
		if (request == null) return;
		if (!matches(request)) {
			closeRequest = null; return;
		}
		request.owners.entrySet().removeIf(entry -> !entry.getKey().isEnabled() || !entry.getValue().getAsBoolean());
		if (request.owners.isEmpty()) { closeRequest = null; return; }
		if (!idle(request.menu)) return;
		if (!ownActionsSettled()) return;
		AutoArmor armor = ModuleManager.get(AutoArmor.class);
		InvCleaner cleaner = ModuleManager.get(InvCleaner.class);
		if (armor != null && armor.isEnabled() && (armor.hasPendingWork() || armor.refusedWork())
				|| cleaner != null && cleaner.isEnabled() && (cleaner.hasOwnPendingWork() || cleaner.refusedWork())) return;
		closeRequest = null;
		MC.player.closeContainer();
	}
}
