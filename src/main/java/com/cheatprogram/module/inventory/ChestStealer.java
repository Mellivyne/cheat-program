package com.cheatprogram.module.inventory;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.RangeSetting;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.render.ChestContainerVisuals;
import com.cheatprogram.render.HudWorldPanel;
import com.cheatprogram.render.R2D;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.Clock;
import com.cheatprogram.util.CombatInventory;
import com.cheatprogram.util.InventoryAutomation;
import com.cheatprogram.util.InventoryPoses;
import com.cheatprogram.util.InteractionLook;
import com.cheatprogram.util.ContainerIndex;
import com.cheatprogram.util.ContainerTransfer;
import com.cheatprogram.util.LootPolicy;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** Only operates a real server-opened menu, with an optional view of its last received contents. */
public final class ChestStealer extends Module {
	private final BoolSetting instant = add(new BoolSetting("instant", false));
	private final RangeSetting delay = add(new RangeSetting("delay", 60, 100, 0, 500, 10, "ms")).visibleWhen(() -> !instant.on());
	private final BoolSetting autoClose = add(new BoolSetting("autoClose", true));
	private final BoolSetting autoDiscover = add(new BoolSetting("autoDiscover", false));
	private final NumberSetting discoverRadius = add(new NumberSetting("discoverRadius", 4, 1, 6, .5, "blocks")).visibleWhen(this::discovering);
	private final NumberSetting discoverDelay = add(new NumberSetting("discoverDelay", 100, 0, 1000, 25, "ms")).visibleWhen(this::discovering);
	private final ModeSetting operationMode = add(new ModeSetting("operationMode", "follow", "silent", "follow"));
	private final BoolSetting worldPanel = add(new BoolSetting("worldPanel", true)).visibleWhen(() -> operationMode.is("silent") || discovering());
	private final NumberSetting panelDistance = add(new NumberSetting("panelDistance", 16, 2, 32, 1)).visibleWhen(() -> (operationMode.is("silent") || discovering()) && worldPanel.on());
	private final ModeSetting lootMode = add(new ModeSetting("lootMode", "loot_combat", "loot_combat", "loot_weapons", "loot_quota", "loot_all"));
	private final TextSetting weaponIds = add(new TextSetting("weaponIds", LootPolicy.DEFAULT_WEAPONS, 4096)).visibleWhen(() -> lootMode.is("loot_weapons"));
	private final TextSetting itemQuotas = add(new TextSetting("itemQuotas", "", 4096)).visibleWhen(() -> lootMode.is("loot_quota"));
	private final ModeSetting junkAction = add(new ModeSetting("junkAction", "junk_return", "junk_return", "junk_drop", "junk_keep"));
	private String parsedWeapons, parsedQuotas, policySignature;
	private Set<String> weaponFilter = Set.of();
	private Map<String, Integer> quotaFilter = Map.of();
	private boolean lastRefused;
	private final Clock clock = new Clock();
	private Pending pending;
	private Session session;
	private Snapshot snapshot;
	private HudWorldPanel.Projection projection;
	private boolean panelVisible;
	private final Set<BlockPos> processed = new HashSet<>();
	private ClientLevel discoveryLevel;
	private Object discoveryPlayer;
	private BlockPos approaching;
	private long nextDiscovery, manualPauseUntil;
	private boolean automaticInteraction, wasDiscovering;
	private boolean wasLinked;
	private Session closingSession;
	private boolean closeCheckPending;
	private long nextCloseCheck;
	private boolean queueIdle;
	public boolean queueCycleIdle(){return !refusedWork()&&queueIdle&&session==null&&pending==null&&closingSession==null&&approaching==null;}
	public boolean refusedWork() {
		if (session != null) refreshRefusals();
		return lastRefused || session != null && (!session.refused.isEmpty() || session.cursorBlocked);
	}
	public String junkAction() { return junkAction.get(); }

	private record Pending(ClientLevel level, ResourceKey<Level> dimension, BlockPos position, BlockPos logicalPosition, long time, boolean automatic) { }
	private record Snapshot(ClientLevel level, ResourceKey<Level> dimension, BlockPos position, List<BlockPos> positions, String title, List<ItemStack> items) { }
	private static final class Session {
		final ClientLevel level;
		final AbstractContainerMenu menu;
		final AbstractContainerScreen<?> screen;
		final Pending origin;
		final int slots;
		final long openedAt = System.currentTimeMillis();
		final Map<Integer, ItemStack> refused = new HashMap<>();
		final Map<Integer, ItemStack> expectedSlots = new HashMap<>();
		final List<ItemStack> returnedItems = new ArrayList<>(), takenItems = new ArrayList<>();
		boolean cursorBlocked;
		long manualRevision = InventoryAutomation.manualRevision();
		List<BlockPos> visualPositions = List.of();
		List<ItemStack> received = List.of();
		ItemStack receivedCarried = ItemStack.EMPTY;
		boolean hidden, ready;
		long lastTransfer, lastReceived;
		long nextSample;
		boolean samplePending;
		int actionRevision;
		int confirmedRevision = -1;
		Session(ClientLevel level, AbstractContainerScreen<?> screen, Pending origin, boolean hidden) {
			this.level = level; this.menu = screen.getMenu(); this.screen = screen; this.origin = origin;
			this.slots = menu.slots.size() - 36; this.hidden = hidden;
		}
	}

	public ChestStealer() { super("cheststealer", Category.INVENTORY); }
	@Override public String summary() { return CombatInventory.summary(super.summary()); }
	@Override public String suffix() { return CombatInventory.suffix(); }
	@Override public String description() { return CombatInventory.description(super.description()); }
	private static ChestStealer instance() { return ModuleManager.get(ChestStealer.class); }
	private boolean discovering() { return autoDiscover.on() || CombatInventory.linked(); }
	private void parseFilters() {
		if (!weaponIds.get().equals(parsedWeapons)) { parsedWeapons = weaponIds.get(); weaponFilter = LootPolicy.ids(parsedWeapons); }
		if (!itemQuotas.get().equals(parsedQuotas)) { parsedQuotas = itemQuotas.get(); quotaFilter = LootPolicy.quotas(parsedQuotas); }
	}
	/** Explicitly selected supplies survive the linked cleaner after the box closes. */
	public boolean keepsConfiguredLoot(ItemStack stack) {
		parseFilters();
		return lootMode.is("loot_all") || lootMode.is("loot_weapons") && weaponFilter.contains(LootPolicy.id(stack))
				|| lootMode.is("loot_quota") && quotaFilter.containsKey(LootPolicy.id(stack));
	}
	private boolean explicitlyListed(ItemStack stack) {
		return !lootMode.is("loot_all") && keepsConfiguredLoot(stack);
	}
	private int wantedCount(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		parseFilters();
		if (session != null) for (ItemStack returned : session.returnedItems)
			if (ItemStack.isSameItemSameComponents(returned, stack)) return 0;
		if (lootMode.is("loot_weapons")) return weaponFilter.contains(LootPolicy.id(stack)) ? stack.getCount() : 0;
		if (lootMode.is("loot_quota")) return Math.max(0, Math.min(stack.getCount(), quotaFilter.getOrDefault(LootPolicy.id(stack), 0) - LootPolicy.ownedCount(stack)));
		if (lootMode.is("loot_all")) return stack.getCount();
		InvCleaner cleaner = ModuleManager.get(InvCleaner.class);
		return (cleaner != null && cleaner.isEnabled() ? cleaner.keepForLoot(stack) : CombatInventory.keep(stack)) ? stack.getCount() : 0;
	}
	private boolean junkItem(ItemStack stack) {
		if (stack.isEmpty() || explicitlyListed(stack)
				) return false;
		if (session != null) for (ItemStack taken : session.takenItems)
			if (ItemStack.isSameItemSameComponents(taken, stack)) return false;
		InvCleaner cleaner = ModuleManager.get(InvCleaner.class);
		return cleaner != null && cleaner.isEnabled() ? cleaner.junkForContainer(stack) : !CombatInventory.keep(stack);
	}
	private boolean hasContainerJunkWork() {
		if (junkAction.is("junk_keep") || mc.player == null) return false;
		for (int index = 0; index < 36; index++) if (!false && junkItem(mc.player.getInventory().getItem(index))) return true;
		return false;
	}
	private long discoverWait() { return (long) (instant.on() ? Math.min(100, discoverDelay.val()) : discoverDelay.val()); }
	private Vec3 sessionPoint() { return session != null && session.origin != null ? Vec3.atCenterOf(session.origin.position) : null; }
	private long networkGrace(long base) {
		if (mc.getSingleplayerServer() == null && mc.player != null && mc.getConnection() != null) {
			var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
			if (info != null) return Math.max(base, Math.min(15000, info.getLatency() * 2L + 500));
		}
		return base;
	}
	private void adoptVisibleMenu() {
		if (mc.screen instanceof ContainerScreen || mc.screen instanceof ShulkerBoxScreen) {
			openedScreen(mc.screen);
			if (validSession()) {
				List<ItemStack> known = new ArrayList<>(session.slots);
				for (int index = 0; index < session.menu.slots.size(); index++) known.add(session.menu.slots.get(index).getItem().copy());
				session.received = List.copyOf(known); session.ready = true;
			}
		}
	}
	@Override public void onEnable() { resetDiscovery(); if (inGame()) adoptVisibleMenu(); }

	/** Also called outside a world, so no old menu, world or player is retained after disconnect. */
	public static void checkLifecycle() {
		ChestStealer module = instance();
		ChestContainerVisuals.tickLifecycle(mc.level);
		if (module == null || module.discoveryLevel == mc.level && module.discoveryPlayer == mc.player) return;
		module.pending = null; module.session = null; module.snapshot = null; module.projection = null; module.panelVisible = false;
		module.closingSession = null; module.closeCheckPending = false;
		ChestContainerVisuals.clear();
		module.resetDiscovery();
	}
	private void resetDiscovery() {
		queueIdle=false; lastRefused=false;
		processed.clear(); approaching = null; nextDiscovery = 0; manualPauseUntil = 0;
		automaticInteraction = false; wasDiscovering = discovering(); wasLinked = CombatInventory.linked(); discoveryLevel = mc.level; discoveryPlayer = mc.player;
	}

	/** Called before a real interaction. It does not read block entity contents. */
	public static void interacted(BlockHitResult hit) {
		ContainerIndex.interacted(hit);
		ChestStealer module = instance();
		if (module == null || !module.isEnabled() || mc.level == null || mc.player == null || mc.screen != null
				|| mc.player.isShiftKeyDown() || !mc.level.hasChunkAt(hit.getBlockPos())) return;
		var block = mc.level.getBlockState(hit.getBlockPos()).getBlock();
		if (!(block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock || block instanceof EnderChestBlock)) return;
		module.approaching = null;
		module.pending = new Pending(mc.level, mc.level.dimension(), hit.getBlockPos().immutable(),
				module.logicalPosition(hit.getBlockPos()), System.currentTimeMillis(), module.automaticInteraction);
	}

	/** MenuScreens has assigned player.containerMenu before Minecraft receives this screen. */
	public static boolean openedScreen(Screen screen) {
		ContainerIndex.openedScreen(screen);
		ChestStealer module = instance();
		if (module == null || !module.isEnabled() || mc.player == null || mc.level == null
				|| (!(screen instanceof ContainerScreen) && !(screen instanceof ShulkerBoxScreen))) return false;
		AbstractContainerScreen<?> container = (AbstractContainerScreen<?>) screen;
		if (container.getMenu() != mc.player.containerMenu) return false;
		if (module.session != null && module.session.menu == container.getMenu()) return false;
		if (module.session != null) module.finishDiscoverySession(true);
		Pending origin = module.pending;
		if (origin != null && (origin.level != mc.level || !origin.dimension.equals(mc.level.dimension())
				|| System.currentTimeMillis() - origin.time > module.networkGrace(5000))) origin = null;
		boolean hidden = module.operationMode.is("silent") && origin != null && mc.screen == null && mc.getOverlay() == null;
		Session next = new Session(mc.level, container, origin, hidden);
		module.pending = null;
		if (next.slots <= 0 || next.slots > 54) return false;
		module.lastRefused = false; module.session = next; module.snapshot = null; module.projection = null;
		module.panelVisible = false;
		if (origin != null) {
			next.visualPositions = module.containerPositions(origin.position);
			module.refreshSnapshot();
			if (origin.automatic || CombatInventory.linked()) ChestContainerVisuals.open(next.level, next.visualPositions);
			if (origin.automatic) InventoryPoses.operated(module, module.operationMode.get(), Vec3.atCenterOf(origin.position), true);
		}
		module.clock.reset(module.instant.on() ? 0 : 50);
		return hidden;
	}

	public static void receivedContent(ClientboundContainerSetContentPacket packet) {
		ContainerIndex.receivedContent(packet);
		ChestStealer module = instance();
		if (module == null || !module.matchesPacket(packet.getContainerId())) return;
		Session session = module.session;
		if (packet.getItems().size() < session.menu.slots.size()) return;
		List<ItemStack> items = new ArrayList<>(session.menu.slots.size());
		for (int slot = 0; slot < session.menu.slots.size(); slot++) items.add(packet.getItems().get(slot).copy());
		session.received = List.copyOf(items); session.receivedCarried = packet.getCarriedItem().copy(); session.ready = true;
		session.lastReceived = System.currentTimeMillis();
		module.refreshSnapshot();
	}
	public static void receivedSlot(ClientboundContainerSetSlotPacket packet) {
		ContainerIndex.receivedSlot(packet);
		ChestStealer module = instance();
		if (module == null || !module.matchesPacket(packet.getContainerId())) return;
		Session session = module.session;
		if (!session.ready || packet.getSlot() < 0 || packet.getSlot() >= session.menu.slots.size()) return;
		List<ItemStack> items = new ArrayList<>(session.received);
		items.set(packet.getSlot(), packet.getItem().copy()); session.received = List.copyOf(items);
		session.lastReceived = System.currentTimeMillis();
		module.refreshSnapshot();
	}
	private boolean matchesPacket(int id) {
		return isEnabled() && session != null && mc.player != null && mc.level == session.level
				&& mc.player.containerMenu == session.menu && id == session.menu.containerId;
	}
	private void refreshSnapshot() {
		List<ItemStack> visible = session.received.isEmpty() ? List.of() : List.copyOf(session.received.subList(0, session.slots));
		if (session.ready && mc.getSingleplayerServer() == null) {
			// The remote protocol does not resend correctly predicted clicks. Show the same owned-menu
			// replica as the vanilla container screen, while retaining received slots for confirmation.
			List<ItemStack> live = new ArrayList<>(session.slots);
			for (int slot = 0; slot < session.slots; slot++) live.add(session.menu.slots.get(slot).getItem().copy());
			visible = List.copyOf(live);
		}
		if (session.origin != null) snapshot = new Snapshot(session.level, session.origin.dimension, session.origin.position,
				session.visualPositions, session.screen.getTitle().getString(), visible);
	}

	/** Integrated-server predictions need no reply packet when correct. Copy the owned menu, never scan chests. */
	private void sampleOwnedServerMenu() {
		var server = mc.getSingleplayerServer();
		if (server == null || session.samplePending || System.currentTimeMillis() < session.nextSample) return;
		Session expected = session;
		var uuid = mc.player.getUUID(); var dimension = mc.level.dimension();
		int menuId = expected.menu.containerId, count = expected.menu.slots.size(), revision = expected.actionRevision;
		expected.samplePending = true; expected.nextSample = System.currentTimeMillis() + 50;
		server.execute(() -> {
			List<ItemStack> copied = null;
			ItemStack copiedCarried = ItemStack.EMPTY;
			var player = server.getPlayerList().getPlayer(uuid);
			if (player != null && player.serverLevel().dimension().equals(dimension)
					&& player.containerMenu.containerId == menuId && player.containerMenu.slots.size() == count) {
				List<ItemStack> items = new ArrayList<>(count);
				for (int index = 0; index < count; index++) items.add(player.containerMenu.slots.get(index).getItem().copy());
				copied = List.copyOf(items); copiedCarried = player.containerMenu.getCarried().copy();
			}
			List<ItemStack> result = copied;
			ItemStack resultCarried = copiedCarried;
			mc.execute(() -> {
				expected.samplePending = false;
				if (result != null && session == expected && validSession() && isEnabled() && expected.actionRevision == revision) {
					expected.received = result; expected.receivedCarried = resultCarried; expected.ready = true; expected.lastReceived = System.currentTimeMillis();
					expected.confirmedRevision = revision;
					ContainerIndex.receivedOwnedMenu(expected.menu.containerId, List.copyOf(result.subList(0, expected.slots))); refreshSnapshot();
				}
			});
		});
	}

	@Override public void onDisable() {
		if (session != null && session.hidden && validSession()) {
			if (mc.screen == null || !session.menu.getCarried().isEmpty()) { session.hidden = false; mc.setScreen(session.screen); }
			else closeOwned();
		}
		pending = null; session = null; snapshot = null; projection = null;
		closingSession = null; closeCheckPending = false; panelVisible = false;
		ChestContainerVisuals.close();
		resetDiscovery();
	}
	private boolean validSession() { return session != null && mc.level == session.level && mc.player != null && mc.player.containerMenu == session.menu; }
	private void closeOwned() {
		if (!validSession() || !session.menu.getCarried().isEmpty()) return;
		Session completed = session;
		boolean automatic = automaticSession();
		finishDiscoverySession(false);
		if (mc.screen == null || mc.screen == session.screen) mc.player.closeContainer();
		else {
			// Preserve a pause/chat/inventory screen opened while our menu was hidden.
			mc.player.connection.send(new ServerboundContainerClosePacket(session.menu.containerId));
			session.menu.removed(mc.player); mc.player.containerMenu = mc.player.inventoryMenu;
		}
		session = null;
		snapshot = null; projection = null; panelVisible = false;
		ChestContainerVisuals.close();
		if (automatic && mc.getSingleplayerServer() != null) {
			closingSession = completed; nextCloseCheck = 0; closeCheckPending = false;
		}
	}
	/** Wait for the integrated server to release our real menu before opening another one. */
	private void checkClosedMenu() {
		if (closingSession == null) return;
		var server = mc.getSingleplayerServer();
		if (server == null || mc.player == null || mc.level != closingSession.level) { closingSession = null; return; }
		if (closeCheckPending || System.currentTimeMillis() < nextCloseCheck) return;
		Session expected = closingSession;
		var uuid = mc.player.getUUID();
		closeCheckPending = true; nextCloseCheck = System.currentTimeMillis() + 50;
		server.execute(() -> {
			var player = server.getPlayerList().getPlayer(uuid);
			boolean closed = player == null || player.containerMenu == player.inventoryMenu
					|| player.containerMenu.containerId != expected.menu.containerId;
			mc.execute(() -> {
				if (closingSession != expected) return;
				closeCheckPending = false;
				if (closed) closingSession = null;
			});
		});
	}
	private boolean automaticSession() { return session != null && session.origin != null && session.origin.automatic; }
	private void finishDiscoverySession(boolean manual) {
		if (discovering() && session != null && session.origin != null) {
			// Count manually opened chests as visited too; do not immediately reopen a chest the user just left.
			processed.add(session.origin.logicalPosition);
		}
		approaching = null;
		nextDiscovery = System.currentTimeMillis() + discoverWait();
		if (manual) manualPauseUntil = System.currentTimeMillis() + 1600;
	}

	@Override public void onTick() {
		checkLifecycle();
		checkClosedMenu();
		if (wasDiscovering != discovering() || wasLinked != CombatInventory.linked()) {
			processed.clear(); approaching = null; nextDiscovery = System.currentTimeMillis();
			wasDiscovering = discovering(); wasLinked = CombatInventory.linked();
		}
		if (session != null && !validSession()) {
			if (!session.expectedSlots.isEmpty() && session.manualRevision == InventoryAutomation.manualRevision()
					&& (mc.getSingleplayerServer() != null ? session.confirmedRevision < session.actionRevision
					: System.currentTimeMillis() - session.lastTransfer < networkGrace(150))) lastRefused = true;
			lastRefused |= !session.refused.isEmpty() || session.cursorBlocked;
			finishDiscoverySession(true); session = null; snapshot = null; projection = null;
			ChestContainerVisuals.close();
		}
		if (session == null && mc.screen != null) adoptVisibleMenu();
		panelVisible = false;
		if (snapshot != null && (!validSession() || mc.level != snapshot.level || !mc.level.dimension().equals(snapshot.dimension))) { snapshot = null; projection = null; }
		if (snapshot != null && showPanel()) {
			Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
			Vec3 anchor = panelAnchor(snapshot);
			if (camera.distanceTo(anchor) <= panelDistance.val() && mc.level.hasChunkAt(snapshot.position)) {
				HitResult sight = mc.level.clip(new ClipContext(camera, anchor, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
				panelVisible = sight.getType() == HitResult.Type.MISS;
			}
		}
		if (!validSession()) { session = null; clock.reset(0); discoverNearby(); return; }
		sampleOwnedServerMenu();
		refreshSnapshot();
		refreshRefusals();
		if (session.hidden && session.cursorBlocked && mc.screen == null) {
			session.hidden = false; mc.setScreen(session.screen); return;
		}
		if (!mc.player.isAlive()) { closeOwned(); return; }
		if (session.hidden && mc.screen != null) {
			if (!session.menu.getCarried().isEmpty()) { session.hidden = false; mc.setScreen(session.screen); return; }
			finishDiscoverySession(true); closeOwned(); return;
		}
		if (session.hidden && operationMode.is("follow")) {
			session.hidden = false; mc.setScreen(session.screen); clock.reset(0); return;
		}
		if (!session.hidden && mc.screen != session.screen) return;
		if (automaticSession() && (AiController.isRunning() || Freecam.isActive() || !mc.isWindowActive() || mc.isPaused())) return;
		if (automaticSession() && !session.ready && System.currentTimeMillis() - session.openedAt > networkGrace(5000)) { lastRefused = true; closeOwned(); return; }
		if (!session.ready || !InventoryAutomation.idle(session.menu)) return;
		String currentPolicy = lootMode.get() + "|" + weaponIds.get() + "|" + itemQuotas.get() + "|" + junkAction.get();
		if (!currentPolicy.equals(policySignature)) {
			policySignature = currentPolicy; session.refused.clear(); session.expectedSlots.clear(); lastRefused = false;
		}
		refreshRefusals();
		if (session.cursorBlocked || refusedWork()) return;
		if (!instant.on() && !clock.ready()) return;
		if (session.lastTransfer != 0 && System.currentTimeMillis() - session.lastTransfer < (instant.on() ? 50 : 0)) return;
		transferPass(instant.on());
	}
	/** Only slots changed by our clicks are checked; subsequent automatic clicks update their expected state. */
	private void refreshRefusals() {
		if (session == null || mc.player == null) return;
		if (session.manualRevision != InventoryAutomation.manualRevision()) {
			session.manualRevision = InventoryAutomation.manualRevision();
			session.expectedSlots.clear(); session.refused.clear(); lastRefused = false;
			if (session.menu.getCarried().isEmpty()) session.cursorBlocked = false;
		}
		boolean confirmed = mc.getSingleplayerServer() != null && session.confirmedRevision >= session.actionRevision;
		if (!session.expectedSlots.isEmpty() && (!session.menu.getCarried().isEmpty() || confirmed && !session.receivedCarried.isEmpty())) {
			session.cursorBlocked = true; lastRefused = true;
		}
		for (var entry : session.expectedSlots.entrySet()) {
			int slot = entry.getKey();
			ItemStack predicted = entry.getValue(), live = session.menu.getSlot(slot).getItem();
			boolean rollback = !ItemStack.matches(predicted, live);
			boolean serverDiffers = confirmed && session.received.size() > slot && !ItemStack.matches(predicted, session.received.get(slot));
			if (rollback || serverDiffers) {
				session.refused.put(slot, live.copy()); lastRefused = true;
			}
		}
	}
	private List<ItemStack> menuSnapshot() {
		List<ItemStack> copy = new ArrayList<>(session.menu.slots.size());
		for (var slot : session.menu.slots) copy.add(slot.getItem().copy());
		return copy;
	}
	private void recordMove(int slot, ItemStack before, List<ItemStack> beforeSlots) {
		ItemStack after = session.menu.getSlot(slot).getItem().copy();
		session.actionRevision++;
		session.lastTransfer = System.currentTimeMillis();
		if (ItemStack.matches(before, after)) { session.refused.put(slot, before); lastRefused = true; }
		else {
			for (int index = 0; index < session.menu.slots.size(); index++) {
				ItemStack changed = session.menu.getSlot(index).getItem();
				if (!ItemStack.matches(beforeSlots.get(index), changed)) session.expectedSlots.put(index, changed.copy());
			}
			InventoryPoses.operated(this, operationMode.get(), sessionPoint(), true);
		}
		if (!session.menu.getCarried().isEmpty()) {
			session.cursorBlocked = true; lastRefused = true;
			if (session.hidden && mc.screen == null) { session.hidden = false; mc.setScreen(session.screen); }
		}
		refreshSnapshot();
		clock.reset((long) delay.random());
	}
	/** One action in normal mode; a bounded vanilla transaction burst in instant mode. */
	private void transferPass(boolean burst) {
		boolean claimed = false;
		if (!junkAction.is("junk_keep")) {
			for (int index = 0; index < 36; index++) {
				int slot = ContainerTransfer.playerSlot(session.menu, index);
				ItemStack stack = mc.player.getInventory().getItem(index);
				if (slot < 0 || !junkItem(stack)
						|| !session.menu.getSlot(slot).mayPickup(mc.player)) continue;
				if (junkAction.is("junk_return") && !ContainerTransfer.canStore(session.menu, session.slots, stack)) continue;
				if (!claimed && !InventoryAutomation.claim(session.menu, slot)) return;
				claimed = true; InventoryAutomation.highlight(session.menu, slot);
				ItemStack before = stack.copy();
				List<ItemStack> beforeSlots = menuSnapshot();
				ContainerTransfer.click(session.menu, slot, junkAction.is("junk_drop") ? 1 : 0,
						junkAction.is("junk_drop") ? ClickType.THROW : ClickType.QUICK_MOVE);
				if (junkAction.is("junk_return") && !ItemStack.matches(before, session.menu.getSlot(slot).getItem())) session.returnedItems.add(before.copy());
				recordMove(slot, before, beforeSlots);
				if (!burst || refusedWork() || !session.menu.getCarried().isEmpty()) return;
			}
		}
		for (int slot = 0; slot < session.slots; slot++) {
			var source = session.menu.getSlot(slot);
			int count = source.hasItem() ? Math.min(wantedCount(source.getItem()), ContainerTransfer.capacity(session.menu, source.getItem())) : 0;
			if (count <= 0 || !source.mayPickup(mc.player)) continue;
			if (!claimed && !InventoryAutomation.claim(session.menu, slot)) return;
			claimed = true; InventoryAutomation.highlight(session.menu, slot);
			ItemStack before = source.getItem().copy();
			List<ItemStack> beforeSlots = menuSnapshot();
			ContainerTransfer.take(session.menu, slot, count);
			if (!ItemStack.matches(before, source.getItem())) session.takenItems.add(before.copy());
			recordMove(slot, before, beforeSlots);
			if (!burst || refusedWork() || !session.menu.getCarried().isEmpty()) return;
		}
		if (shouldCloseCompleted()) closeOwned();
		else if (!claimed) clock.reset(50);
	}
	private boolean shouldCloseCompleted() {
		if (!(autoClose.on() || automaticSession() || CombatInventory.linked()) || !session.menu.getCarried().isEmpty()
				|| refusedWork() || session.cursorBlocked) return false;
		if ((automaticSession() || CombatInventory.linked()) && System.currentTimeMillis() - session.openedAt < 250) return false;
		if (session.lastTransfer != 0) {
			long age = System.currentTimeMillis() - session.lastTransfer;
			// Local server confirmation covers both chest and player source slots. Remote predicted clicks
			// may have no success reply, so allow a bounded network rollback interval before completion.
			if (mc.getSingleplayerServer() != null ? age < 50 || session.confirmedRevision < session.actionRevision : age < networkGrace(150)) return false;
		}
		for (int slot = 0; slot < session.slots; slot++) {
			var source = session.menu.getSlot(slot);
			if (source.hasItem() && wantedCount(source.getItem()) > 0 && source.mayPickup(mc.player)
					&& ContainerTransfer.capacity(session.menu, source.getItem()) > 0) return false;
		}
		if (!junkAction.is("junk_keep")) for (int index = 0; index < 36; index++) {
			int slot = ContainerTransfer.playerSlot(session.menu, index);
			ItemStack stack = mc.player.getInventory().getItem(index);
			if (slot >= 0 && junkItem(stack) && session.menu.getSlot(slot).mayPickup(mc.player)
					&& (junkAction.is("junk_drop") || ContainerTransfer.canStore(session.menu, session.slots, stack))) return false;
		}
		return true;
	}

	/** Discovery only sees block states; the menu and every item still have to arrive through normal interaction. */
	private void discoverNearby() {
		if (!discovering() || !inGame() || mc.gameMode == null || closingSession != null || lastRefused) return;
		long now = System.currentTimeMillis();
		if (pending != null) {
			if (now - pending.time < networkGrace(pending.automatic ? 2400 : 5000)) return;
			if (pending.automatic) { processed.add(pending.logicalPosition); lastRefused = true; }
			pending = null; approaching = null; nextDiscovery = now + discoverWait();
			if (lastRefused) return;
		}
		if (!discoveryReady()) {
			approaching = null;
			if (mc.options.keyAttack.isDown() || mc.options.keyUse.isDown()) manualPauseUntil = now + 900;
			return;
		}
		// The other workers prepare/equip/clean with the own menu before another real chest is opened.
		if (CombatInventory.linked() && !InventoryAutomation.readyForChestCycle()) return;
		if (now < manualPauseUntil || now < nextDiscovery) return;
		if (!inventoryHasSpace() && !hasContainerJunkWork()) { queueIdle=true; approaching=null; return; }
		double radius = Math.min(discoverRadius.val(), mc.player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE));
		if (!Double.isFinite(radius) || radius <= 0) return;
		Vec3 eye = mc.player.getEyePosition();
		double forgetRange = radius + 2;
		processed.removeIf(position -> Vec3.atCenterOf(position).distanceToSqr(eye) > forgetRange * forgetRange);
		BlockHitResult hit = approaching == null ? null : reachableChest(approaching, radius);
		if (hit == null || processed.contains(logicalPosition(approaching))) {
			hit = null;
			approaching = null;
			int span = (int) Math.ceil(radius + 1);
			BlockPos center = BlockPos.containing(eye);
			double closest = Double.POSITIVE_INFINITY;
			for (BlockPos position : BlockPos.betweenClosed(center.offset(-span, -span, -span), center.offset(span, span, span))) {
				if (!mc.level.hasChunkAt(position) || !isChest(mc.level.getBlockState(position)) || processed.contains(logicalPosition(position))) continue;
				BlockHitResult candidate = reachableChest(position, radius);
				if (candidate == null) continue;
				double distance = candidate.getLocation().distanceToSqr(eye);
				if (distance < closest) { closest = distance; hit = candidate; approaching = position.immutable(); }
			}
			if (hit == null) { queueIdle=true; nextDiscovery = now + Math.max(50, discoverWait()); return; }
		}
		queueIdle=false;
		if (!aimReady(hit.getLocation())) return;
		if (!InventoryAutomation.claim(mc.player.inventoryMenu, -1)) return;
		BlockHitResult selected = hit;
		// Assign origin before the normal call too: the interaction mixin keeps the same ownership marker.
		pending = new Pending(mc.level, mc.level.dimension(), selected.getBlockPos().immutable(),
				logicalPosition(selected.getBlockPos()), now, true);
		snapshot = null; projection = null; panelVisible = false;
		approaching = null;
		automaticInteraction = true;
		try {
			var result = InteractionLook.interact(selected.getLocation(), operationMode.get(),
					() -> mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, selected));
			if (!result.consumesAction() && pending != null) {
				processed.add(pending.logicalPosition); pending = null; lastRefused = true;
			}
		} finally {
			automaticInteraction = false;
			nextDiscovery = now + discoverWait();
		}
	}
	private boolean discoveryReady() {
		return mc.screen == null && mc.getOverlay() == null && mc.isWindowActive() && !mc.isPaused()
				&& mc.mouseHandler.isMouseGrabbed() && mc.player.containerMenu == mc.player.inventoryMenu
				&& mc.player.isAlive() && !mc.player.isSpectator() && !mc.player.isPassenger() && !mc.player.isShiftKeyDown()
				&& !mc.player.isUsingItem() && !mc.gameMode.isDestroying() && !AiController.isRunning() && !Freecam.isActive()
				&& !mc.options.keyAttack.isDown() && !mc.options.keyUse.isDown() && InventoryAutomation.idle(mc.player.inventoryMenu);
	}
	private boolean inventoryHasSpace() {
		for (int index = 0; index < 36; index++) {

			ItemStack item = mc.player.getInventory().getItem(index);
			if (item.isEmpty() || item.isStackable() && item.getCount() < item.getMaxStackSize()) return true;
		}
		return false;
	}
	private static boolean isChest(BlockState state) {
		return state.getBlock() instanceof ChestBlock || state.getBlock() instanceof BarrelBlock
				|| state.getBlock() instanceof ShulkerBoxBlock || state.getBlock() instanceof EnderChestBlock;
	}
	/** Normalise both halves without reading either container's inventory. */
	private BlockPos logicalPosition(BlockPos position) {
		if (position == null) return BlockPos.ZERO;
		BlockState state = mc.level.getBlockState(position);
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos other = position.relative(ChestBlock.getConnectedDirection(state));
			if (mc.level.hasChunkAt(other)) {
				BlockState neighbour = mc.level.getBlockState(other);
				if (neighbour.is(state.getBlock()) && neighbour.getValue(ChestBlock.FACING) == state.getValue(ChestBlock.FACING)
						&& neighbour.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE).getOpposite()
						&& other.asLong() < position.asLong()) return other.immutable();
			}
		}
		return position.immutable();
	}
	private List<BlockPos> containerPositions(BlockPos position) {
		BlockPos first = position.immutable();
		BlockState state = mc.level.getBlockState(first);
		if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
			BlockPos other = first.relative(ChestBlock.getConnectedDirection(state));
			if (mc.level.hasChunkAt(other)) {
				BlockState neighbour = mc.level.getBlockState(other);
				if (neighbour.is(state.getBlock()) && neighbour.getValue(ChestBlock.FACING) == state.getValue(ChestBlock.FACING)
						&& neighbour.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE).getOpposite()) return List.of(first, other.immutable());
			}
		}
		return List.of(first);
	}
	private boolean showPanel() { return worldPanel.on() && (operationMode.is("silent") || automaticSession() || CombatInventory.linked()); }
	private static Vec3 panelAnchor(Snapshot snapshot) {
		double x = 0, y = 0, z = 0;
		for (BlockPos position : snapshot.positions) { x += position.getX() + .5; y += position.getY() + 1.35; z += position.getZ() + .5; }
		return new Vec3(x / snapshot.positions.size(), y / snapshot.positions.size(), z / snapshot.positions.size());
	}
	private BlockHitResult reachableChest(BlockPos position, double radius) {
		if (!mc.level.hasChunkAt(position) || !mc.level.getWorldBorder().isWithinBounds(position)) return null;
		BlockState state = mc.level.getBlockState(position);
		if (!isChest(state)) return null;
		if (state.getBlock() instanceof ChestBlock) {
			if (ChestBlock.isChestBlockedAt(mc.level, position)) return null;
			if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
				BlockPos other = position.relative(ChestBlock.getConnectedDirection(state));
				if (!mc.level.hasChunkAt(other) || ChestBlock.isChestBlockedAt(mc.level, other)) return null;
			}
		}
		Vec3 eye = mc.player.getEyePosition(), center = Vec3.atCenterOf(position);
		if (center.distanceToSqr(eye) > (radius + 1) * (radius + 1)) return null;
		BlockHitResult best = null;
		double bestDistance = Double.POSITIVE_INFINITY;
		// Face samples allow a visible side/top to be used even when the centre line crosses a fence or slab.
		for (int face = -1; face < 6; face++) {
			Vec3 point = center;
			if (face >= 0) {
				Direction direction = Direction.values()[face];
				point = point.add(direction.getStepX() * .43, direction.getStepY() * .43, direction.getStepZ() * .43);
			}
			BlockHitResult ray = mc.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
			double distance = ray.getLocation().distanceToSqr(eye);
			if (ray.getType() == HitResult.Type.BLOCK && ray.getBlockPos().equals(position) && distance <= radius * radius && distance < bestDistance) {
				best = ray; bestDistance = distance;
			}
		}
		return best;
	}
	private boolean aimReady(Vec3 point) {
		if (!operationMode.is("follow")) return true;
		Vec3 delta = point.subtract(mc.player.getEyePosition());
		float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
		float pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z))), -90, 90);
		float dy = Mth.wrapDegrees(yaw - mc.player.getYRot()), dp = pitch - mc.player.getXRot();
		if (Math.abs(dy) < 1.5f && Math.abs(dp) < 1.5f) return true;
		mc.player.turn(Mth.clamp(dy * .34f, -18, 18) / .15, Mth.clamp(dp * .34f, -12, 12) / .15);
		return false;
	}
	@Override public void onRender3D(Render3D renderer) {
		if ((automaticSession() || CombatInventory.linked()) && validSession()) ChestContainerVisuals.outline(renderer);
		projection = panelVisible && snapshot != null ? HudWorldPanel.project(renderer,
				panelAnchor(snapshot), panelDistance.val()) : null;
	}
	@Override public void onRender2D(HudContext hud) {
		if (mc.screen == null && projection != null && snapshot != null && mc.level == snapshot.level
				&& showPanel()) drawPanel(projection);
	}
	private void drawPanel(HudWorldPanel.Projection at) {
		if (snapshot.items.isEmpty()) ChestContainerVisuals.drawWaiting(at, snapshot.title);
		else HudWorldPanel.draw(at, snapshot.title, snapshot.items);
	}
	/** In follow mode the actual container screen stays open; its live glass copy is drawn alongside it. */
	public static void renderContainerPanel(GuiGraphics graphics, AbstractContainerScreen<?> screen, int left, int top, int width, int height) {
		ChestStealer module = instance();
		if (module == null || !module.isEnabled() || !module.validSession() || !(module.automaticSession() || CombatInventory.linked())
				|| module.session.screen != screen || module.snapshot == null || !module.showPanel()) return;
		float guiScale = (float) mc.getWindow().getGuiScale();
		float framebufferWidth = mc.getWindow().getWidth(), framebufferHeight = mc.getWindow().getHeight();
		int rows = Math.max(1, (module.session.slots + 8) / 9);
		float panelHeight = module.snapshot.items.isEmpty() ? 48 : 26 + rows * 20;
		float scale = Math.max(.65f, Math.min(1.7f, guiScale));
		float right = (left + width) * guiScale + 12;
		float available = framebufferWidth - right - 12;
		float x, y;
		if (available >= 190 * .65f) {
			scale = Math.min(scale, available / 190);
			x = right + 95 * scale;
			y = Math.min(framebufferHeight - 12, top * guiScale + panelHeight * scale);
		} else {
			scale = Math.min(scale, (framebufferWidth - 24) / 190);
			x = framebufferWidth / 2;
			y = Math.max(panelHeight * scale + 6, top * guiScale - 10);
		}
		boolean ownPass = !R2D.isActive();
		if (ownPass) R2D.begin(graphics);
		try { module.drawPanel(new HudWorldPanel.Projection(x, y, scale, System.nanoTime())); }
		finally { if (ownPass) R2D.end(); }
	}
}
