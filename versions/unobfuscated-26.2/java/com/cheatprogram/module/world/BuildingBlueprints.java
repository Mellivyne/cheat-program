package com.cheatprogram.module.world;



import com.cheatprogram.CheatProgram;

import com.cheatprogram.core.Category;

import com.cheatprogram.core.Lang;

import com.cheatprogram.core.Module;

import com.cheatprogram.core.Notifications;

import com.cheatprogram.core.setting.BoolSetting;

import com.cheatprogram.core.setting.ModeSetting;

import com.cheatprogram.core.setting.NumberSetting;

import com.cheatprogram.hud.HudContext;

import com.cheatprogram.render.Fonts;

import com.cheatprogram.render.Render3D;

import com.cheatprogram.render.BuildingModelPreview;

import com.cheatprogram.util.BuildingFiles;

import com.cheatprogram.util.BuildingFiles.Blueprint;

import com.cheatprogram.util.BuildingUndoSnapshot;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.multiplayer.ClientLevel;

import net.minecraft.client.multiplayer.ClientPacketListener;

import net.minecraft.client.server.IntegratedServer;

import net.minecraft.commands.arguments.blocks.BlockStateParser;

import net.minecraft.core.BlockPos;

import net.minecraft.core.registries.BuiltInRegistries;

import net.minecraft.core.registries.Registries;

import net.minecraft.nbt.CompoundTag;

import net.minecraft.nbt.NbtUtils;

import net.minecraft.resources.Identifier;

import net.minecraft.resources.ResourceKey;

import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.Clearable;

import net.minecraft.world.level.ClipContext;

import net.minecraft.world.level.block.Block;

import net.minecraft.world.level.block.Blocks;

import net.minecraft.world.level.Level;

import net.minecraft.world.level.block.entity.BlockEntity;

import net.minecraft.world.level.block.state.BlockState;

import net.minecraft.world.phys.AABB;

import net.minecraft.world.phys.BlockHitResult;

import net.minecraft.world.phys.HitResult;

import net.minecraft.world.phys.Vec3;

import org.lwjgl.glfw.GLFW;



import java.util.ArrayList;

import java.util.BitSet;

import java.util.HashMap;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.UUID;

import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.atomic.AtomicLong;

import java.util.concurrent.ConcurrentHashMap;



/** Visible selection and previews use the camera; actual writes use the world's authoritative server. */

public final class BuildingBlueprints extends Module {

	public final ModeSetting selectionMode = add(new ModeSetting("selectionMode", "building_raycast", "building_raycast", "building_distance"));

	public final NumberSetting selectionDistance = add(new NumberSetting("selectionDistance", 8, 1, 64, 1));

	public final BoolSetting replaceAir = add(new BoolSetting("replaceAir", false));

	public final BoolSetting worldPreview = add(new BoolSetting("worldPreview", true));

	public final NumberSetting previewOpacity = add(new NumberSetting("previewOpacity", 45, 10, 90, 5, "%"));

	public final NumberSetting offsetX = add(new NumberSetting("offsetX", 0, -30_000_000, 30_000_000, 1));

	public final NumberSetting offsetY = add(new NumberSetting("offsetY", 0, -30_000_000, 30_000_000, 1));

	public final NumberSetting offsetZ = add(new NumberSetting("offsetZ", 0, -30_000_000, 30_000_000, 1));

	public final NumberSetting remoteDelay = add(new NumberSetting("remoteDelay", 250, 50, 5000, 50, "ms")).visibleWhen(this::remoteWorld);

	public final NumberSetting remoteTimeout = add(new NumberSetting("remoteTimeout", 8000, 2000, 30000, 500, "ms")).visibleWhen(this::remoteWorld);

	private final AtomicLong generation = new AtomicLong();

	private final AtomicBoolean queued = new AtomicBoolean();

	private final Object jobLock = new Object();

	private ClientLevel world;

	private BlockPos first, second, pasteOrigin, candidate;

	private boolean aHeld, bHeld, originHeld;

	private volatile boolean busy;

	private volatile Job job;

	private volatile int progress, total;

	private String message = "";

	private volatile boolean lastPasteSucceeded;

	public boolean lastPasteSucceeded(){return lastPasteSucceeded;}

	private Blueprint loaded;

	private long libraryRevision;

	private final BuildingModelPreview worldPreviewRenderer = new BuildingModelPreview();

	private final Map<ResourceKey<Level>, BuildingUndoSnapshot> undoSnapshots = new ConcurrentHashMap<>();

	private volatile IntegratedServer undoServer;

	private ClientPacketListener connection;

	private final Map<ResourceKey<Level>, RemoteUndoSnapshot> remoteUndoSnapshots = new HashMap<>();

	private final Map<String, Long> remoteVerificationMessages = new LinkedHashMap<>();



	public BuildingBlueprints() { super("buildingblueprints", Category.WORLD); }

	public boolean busy() { return busy; }

	public Blueprint loaded() { return loaded; }

	public long libraryRevision() { return libraryRevision; }

	public String status() { return busy ? message + " " + progress + " / " + total : message; }

	public String firstLabel() { return coordinate(first); }

	public String secondLabel() { return coordinate(second); }

	public String originLabel() { return coordinate(finalOrigin()); }

	public BlockPos placementOrigin() { return finalOrigin(); }

	public boolean canUndo() {

		RemoteUndoSnapshot remote = currentRemoteUndo();

		if (remote != null) return remote.remaining() > 0;

		BuildingUndoSnapshot snapshot = currentUndo();

		return snapshot != null && snapshot.changed() > 0 && !snapshot.finished();

	}

	public String undoLabel() {

		RemoteUndoSnapshot remote = currentRemoteUndo();

		if (remote != null && remote.remaining() > 0) return remote.name + " · " + remote.remaining() + " · " + Lang.t("ui.building.undoSession");

		BuildingUndoSnapshot snapshot = currentUndo();

		return snapshot == null || snapshot.changed() == 0 ? Lang.t("ui.building.noUndo") : snapshot.name + " · " + snapshot.changed() + " · " + Lang.t("ui.building.undoSession");

	}

	private BuildingUndoSnapshot currentUndo() {

		if (!inGame() || mc.getSingleplayerServer() == null || undoServer != mc.getSingleplayerServer()) return null;

		return undoSnapshots.get(mc.level.dimension());

	}

	private RemoteUndoSnapshot currentRemoteUndo() {

		return inGame() && mc.getSingleplayerServer() == null && connection == mc.getConnection() ? remoteUndoSnapshots.get(mc.level.dimension()) : null;

	}

	public boolean remoteWorld() { return inGame() && mc.getSingleplayerServer() == null; }

	public String placementHint() {

		return Lang.t(remoteWorld() ? (canUseRemoteCommands() ? "ui.building.remoteReady" : "ui.building.remotePermission") : "ui.building.localReady");

	}

	private boolean canUseRemoteCommands() {

		ClientPacketListener active = mc.getConnection();

		// The command tree is filtered by the server for this player. Do not forge permission levels.

		return active != null && active.getCommands().getRoot().getChild("setblock") != null;

	}

	/** Called after vanilla has applied a real server block update, never from a local prediction. */

	public void serverBlockUpdated(BlockPos position, BlockState state) {

		Job running = job;

		if (running instanceof RemotePasteJob paste && paste.pending >= 0 && paste.active()

				&& paste.position(paste.pending).equals(position) && paste.backup.after(paste.pending) == state) paste.acknowledged = true;

		else if (running instanceof RemoteUndoJob undo && undo.pending >= 0 && undo.active()

				&& undo.snapshot.position(undo.pending).equals(position) && undo.snapshot.before(undo.pending) == state) undo.acknowledged = true;

	}

	/** A nonce-bearing server /execute reply is a synchronization barrier for same-state undo commands. */

	public boolean serverVerificationMessage(String message, boolean overlay) {

		if (overlay || remoteVerificationMessages.remove(message) == null) return false;

		Job running = job;

		if (running instanceof RemoteUndoJob undo && undo.active() && undo.pending >= 0 && message.equals(undo.verificationToken)) undo.acknowledged = true;

		return true;

	}

	private boolean canVerifyRemoteCommands() {

		ClientPacketListener active = mc.getConnection();

		return active != null && active.getCommands().getRoot().getChild("execute") != null && active.getCommands().getRoot().getChild("tellraw") != null;

	}

	private void rememberVerification(String marker, long now) {

		remoteVerificationMessages.entrySet().removeIf(entry -> now - entry.getValue() > 60_000);

		while (remoteVerificationMessages.size() >= 32) remoteVerificationMessages.remove(remoteVerificationMessages.keySet().iterator().next());

		remoteVerificationMessages.put(marker, now);

	}

	private BlockPos finalOrigin() { return pasteOrigin == null ? null : pasteOrigin.offset(offsetX.intVal(), offsetY.intVal(), offsetZ.intVal()); }

	private static String coordinate(BlockPos point) { return point == null ? Lang.t("ui.building.unmarked") : point.getX() + ", " + point.getY() + ", " + point.getZ(); }

	public String dimensions() {

		if (first == null || second == null) return Lang.t("ui.building.unmarked");

		return (Math.abs(first.getX() - second.getX()) + 1) + " × " + (Math.abs(first.getY() - second.getY()) + 1) + " × " + (Math.abs(first.getZ() - second.getZ()) + 1);

	}

	@Override public void onEnable() { if (inGame()) ensureWorld(); }

	@Override public void onDisable() { cancel(); worldPreviewRenderer.close(); aHeld = bHeld = originHeld = false; }

	/** Called globally, including while disabled or on the title screen, to release the previous world. */

	public void checkLifecycle() {

		IntegratedServer active = mc.getSingleplayerServer();

		boolean unavailable = mc.level == null || mc.player == null || mc.getConnection() == null;

		boolean differentWorld = world != null && world != mc.level;

		boolean differentSession = connection != null && connection != mc.getConnection() || undoServer != null && undoServer != active;

		if (!unavailable && !differentWorld && !differentSession) return;

		if (world == null && !busy && job == null && undoServer == null && connection == null && undoSnapshots.isEmpty() && remoteUndoSnapshots.isEmpty()) return;

		cancel(); worldPreviewRenderer.close(); world = null; first = second = pasteOrigin = candidate = null;

		aHeld = bHeld = originHeld = false; message = "";

		if (unavailable || differentSession) {

			connection = null; remoteUndoSnapshots.clear(); remoteVerificationMessages.clear();

			synchronized (undoSnapshots) { undoServer = null; undoSnapshots.clear(); }

		}

	}

	private void ensureWorld() {

		if (connection != mc.getConnection()) { cancel(); remoteUndoSnapshots.clear(); remoteVerificationMessages.clear(); connection = mc.getConnection(); }

		if (world != mc.level) {

			cancel(); worldPreviewRenderer.close(); world = mc.level; first = second = pasteOrigin = candidate = null;

			aHeld = bHeld = originHeld = false; message = "";

		}

		IntegratedServer active = mc.getSingleplayerServer();

		if (undoServer != active) { synchronized (undoSnapshots) { undoServer = active; undoSnapshots.clear(); } }

	}

	@Override public void onTick() {

		if (!inGame()) return;

		ensureWorld(); candidate = aim();

		if (mc.gui.screen() == null && mc.isWindowActive()) {

			long window = mc.getWindow().handle();

			boolean a = com.cheatprogram.compat.KeyboardBridge.isKeyDown(window, GLFW.GLFW_KEY_F6), b = com.cheatprogram.compat.KeyboardBridge.isKeyDown(window, GLFW.GLFW_KEY_F7);

			boolean origin = com.cheatprogram.compat.KeyboardBridge.isKeyDown(window, GLFW.GLFW_KEY_F8);

			if (a && !aHeld) markFirst(); if (b && !bHeld) markSecond(); if (origin && !originHeld) markOrigin();

			aHeld = a; bHeld = b; originHeld = origin;

		} else { aHeld = bHeld = originHeld = false; }

		Job running = job;

		if (running != null && !mc.isPaused() && queued.compareAndSet(false, true)) {

			Runnable step = () -> {

				try {

					if (running.token == generation.get()) running.step();

				} catch (Exception failure) {

					CheatProgram.LOGGER.warn("Building operation failed", failure);

					complete(running.token, "ui.building.operationFailed", null);

				} finally { queued.set(false); }

			};

			if (running.server == null) step.run(); else running.server.execute(step);

		}

	}

	private BlockPos aim() {

		if (!inGame()) return null;

		var camera = mc.gameRenderer.mainCamera();

		if (!camera.isInitialized()) return null;

		Vec3 from = camera.position(); var look = camera.forwardVector();

		Vec3 end = from.add(look.x() * selectionDistance.val(), look.y() * selectionDistance.val(), look.z() * selectionDistance.val());

		if (selectionMode.is("building_raycast")) {

			BlockHitResult hit = mc.level.clip(new ClipContext(from, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));

			if (hit.getType() == HitResult.Type.BLOCK) return hit.getBlockPos().immutable();

		}

		BlockPos block = BlockPos.containing(end);

		return mc.level.isInWorldBounds(block) ? block : null;

	}

	public void markFirst() { if (!busy && inGame()) { ensureWorld(); first = aim(); message = Lang.t("ui.building.firstMarked"); } }

	public void markSecond() { if (!busy && inGame()) { ensureWorld(); second = aim(); message = Lang.t("ui.building.secondMarked"); } }

	public void markOrigin() { if (!busy && inGame()) { ensureWorld(); pasteOrigin = aim(); message = Lang.t("ui.building.originMarked"); } }

	public void usePlayerOrigin() { if (!busy && inGame()) { ensureWorld(); pasteOrigin = mc.player.blockPosition(); message = Lang.t("ui.building.originMarked"); } }

	public void clearSelection() { if (!busy) { first = second = null; message = ""; } }

	public void cancel() {

		Job running;

		synchronized (jobLock) { running = job; generation.incrementAndGet(); job = null; }

		busy = false; progress = total = 0;

		if (running instanceof UndoJob || running instanceof RemoteUndoJob) message = Lang.t("ui.building.undoPaused");

		else if ((running instanceof PasteJob || running instanceof RemotePasteJob) && canUndo()) message = Lang.t("ui.building.exportCancelled");

	}

	private void publishJob(Job ready) {

		synchronized (jobLock) { if (ready.token == generation.get()) job = ready; }

	}

	private void releaseJob(Job finished) { synchronized (jobLock) { if (job == finished) job = null; } }

	public void save(String name) {

		if (busy || !inGame()) return;

		ensureWorld();

		if (first == null || second == null) { message = Lang.t("ui.building.needCorners"); return; }

		BlockPos minimum = minimum(first, second), maximum = maximum(first, second);

		long count = ((long) maximum.getX() - minimum.getX() + 1) * ((long) maximum.getY() - minimum.getY() + 1) * ((long) maximum.getZ() - minimum.getZ() + 1);

		if (count <= 0 || count > BuildingFiles.MAX_BLOCKS) { message = Lang.t("ui.building.tooLarge"); return; }

		IntegratedServer server = mc.getSingleplayerServer();

		long token = generation.incrementAndGet(); busy = true; progress = 0; total = (int) count; message = Lang.t("ui.building.saving");

		var dimension = mc.level.dimension(); String filename = BuildingFiles.cleanName(name);

		if (server == null) {

			if (!validClientBounds(mc.level, minimum, maximum) || !loadedArea(mc.level, minimum, maximum)) { complete(token, "ui.building.clientAreaUnavailable", null); return; }

			publishJob(new RemoteSaveJob(token, minimum, maximum, filename)); return;

		}

		server.execute(() -> {

			ServerLevel level = server.getLevel(dimension);

			if (token != generation.get()) return;

			if (level == null || !validBounds(level, minimum, maximum)) { complete(token, "ui.building.areaUnavailable", null); return; }

			publishJob(new SaveJob(server, level, token, minimum, maximum, filename));

		});

	}

	public void load(String name) {

		if (busy || name == null || name.isBlank()) return;

		long token = generation.incrementAndGet(); busy = true; progress = total = 0; message = Lang.t("ui.building.loading");

		BuildingFiles.IO.execute(() -> {

			try { Blueprint blueprint = BuildingFiles.load(name); complete(token, "ui.building.loaded", blueprint); }

			catch (Exception failure) { complete(token, "ui.building.fileError", null); }

		});

	}

	public void delete(String name) {

		if (busy || name == null || name.isBlank()) return;

		long token = generation.incrementAndGet(); busy = true; progress = total = 0; message = Lang.t("ui.building.deleting");

		BuildingFiles.IO.execute(() -> {

			if (token != generation.get()) return;

			try {

				BuildingFiles.delete(name);

				mc.execute(() -> {

					// Once the file has moved, even a cancelled UI request must invalidate its cached blueprint.

					libraryRevision++;

					if (loaded != null && loaded.name().equals(name)) loaded = null;

					if (token != generation.get()) return;

					job = null; busy = false; message = Lang.t("ui.building.deleted") + " · " + name;

					Notifications.push(name(), message, true);

				});

			} catch (Exception failure) { complete(token, "ui.building.deleteFailed", null); }

		});

	}

	public void paste() {

		lastPasteSucceeded=false;

		if (busy || !inGame()) return;

		ensureWorld();

		if (loaded == null || pasteOrigin == null) { message = Lang.t("ui.building.needOrigin"); return; }

		IntegratedServer server = mc.getSingleplayerServer();

		Blueprint blueprint = loaded; BlockPos origin = finalOrigin().immutable();

		BlockPos maximum = origin.offset(blueprint.width() - 1, blueprint.height() - 1, blueprint.depth() - 1);

		if (server == null && !canUseRemoteCommands()) { message = Lang.t("ui.building.remotePermission"); return; }

		long token = generation.incrementAndGet(); busy = true; progress = 0; total = blueprint.count() * 2; message = Lang.t("ui.building.placing");

		boolean air = replaceAir.on(); var dimension = mc.level.dimension();

		if (server == null) {

			if (!validClientBounds(mc.level, origin, maximum) || !loadedArea(mc.level, origin, maximum)) { complete(token, "ui.building.clientAreaUnavailable", null); return; }

			total = blueprint.count(); publishJob(new RemotePasteJob(token, origin, blueprint, air)); return;

		}

		server.execute(() -> {

			ServerLevel level = server.getLevel(dimension);

			if (token != generation.get()) return;

			if (level == null || !validBounds(level, origin, maximum)) { complete(token, "ui.building.areaUnavailable", null); return; }

			publishJob(new PasteJob(server, level, token, origin, blueprint, air));

		});

	}

	public void undo() {

		if (busy || !inGame()) return;

		ensureWorld();

		RemoteUndoSnapshot remote = currentRemoteUndo();

		if (remote != null && remote.remaining() > 0) {

			if (!canUseRemoteCommands()) { message = Lang.t("ui.building.remotePermission"); return; }

			if (!canVerifyRemoteCommands()) { message = Lang.t("ui.building.remoteVerificationUnavailable"); return; }

			long token = generation.incrementAndGet(); busy = true; progress = 0; total = remote.remaining(); message = Lang.t("ui.building.undoing");

			publishJob(new RemoteUndoJob(token, remote)); return;

		}

		BuildingUndoSnapshot snapshot = currentUndo();

		if (snapshot == null || snapshot.changed() == 0 || snapshot.finished()) { message = Lang.t("ui.building.noUndo"); return; }

		IntegratedServer server = mc.getSingleplayerServer(); var dimension = mc.level.dimension();

		long token = generation.incrementAndGet(); busy = true; progress = snapshot.cursor(); total = snapshot.count * 2; message = Lang.t("ui.building.undoing");

		server.execute(() -> {

			if (token != generation.get()) return;

			ServerLevel level = server.getLevel(dimension);

			if (!snapshot.belongsTo(server, level)) { complete(token, "ui.building.undoWrongWorld", null); return; }

			publishJob(new UndoJob(server, level, token, snapshot));

		});

	}

	private void complete(long token, String key, Blueprint blueprint) {

		mc.execute(() -> {

			if (token != generation.get()) return;

			job = null; busy = false; message = Lang.t(key);

			lastPasteSucceeded=key.equals("ui.building.placed");

			if (blueprint != null) loaded = blueprint;

			if (key.equals("ui.building.saved") || key.equals("ui.building.savedClientOnly")) libraryRevision++;

			Notifications.push(name(), message, key.equals("ui.building.saved") || key.equals("ui.building.savedClientOnly") || key.equals("ui.building.loaded") || key.equals("ui.building.placed") || key.equals("ui.building.undone"));

		});

	}

	private static BlockPos minimum(BlockPos a, BlockPos b) { return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())); }

	private static BlockPos maximum(BlockPos a, BlockPos b) { return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())); }

	private static boolean validBounds(ServerLevel level, BlockPos minimum, BlockPos maximum) {

		return level.isInWorldBounds(minimum) && level.isInWorldBounds(maximum) && level.getWorldBorder().isWithinBounds(minimum) && level.getWorldBorder().isWithinBounds(maximum);

	}

	private static boolean validClientBounds(ClientLevel level, BlockPos minimum, BlockPos maximum) {

		return level.isInWorldBounds(minimum) && level.isInWorldBounds(maximum) && level.getWorldBorder().isWithinBounds(minimum) && level.getWorldBorder().isWithinBounds(maximum);

	}

	private static boolean loadedArea(ClientLevel level, BlockPos minimum, BlockPos maximum) {

		for (int x = minimum.getX() >> 4; x <= maximum.getX() >> 4; x++)

			for (int z = minimum.getZ() >> 4; z <= maximum.getZ() >> 4; z++)

				if (!level.getChunkSource().hasChunk(x, z)) return false;

		return true;

	}

	@Override public void onRender3D(Render3D renderer) {

		if (!inGame() || world != mc.level) return;

		BlockPos previewOrigin = finalOrigin();

		if (worldPreview.on() && loaded != null && previewOrigin != null) {

			worldPreviewRenderer.set(loaded); worldPreviewRenderer.drawWorld(renderer, previewOrigin, previewOpacity.floatVal() / 100f);

		}

		renderer.begin();

		try {

			BlockPos aimed = aim();

			if (aimed != null && mc.gui.screen() == null) renderer.box(new AABB(aimed).inflate(0.003), 0x12FFFFFF, 0xEFFFFFFF, 2);

			if (first != null) renderer.box(new AABB(first).inflate(0.006), 0x16FFFFFF, 0xFFFFFFFF, 2.5f);

			if (second != null) renderer.box(new AABB(second).inflate(0.006), 0x16FFFFFF, 0xFFFFFFFF, 2.5f);

			if (first != null && second != null) {

				BlockPos min = minimum(first, second), max = maximum(first, second);

				renderer.box(new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1d, max.getY() + 1d, max.getZ() + 1d), 0, 0xEEFFFFFF, 2);

			}

			BlockPos imported = finalOrigin();

			if (loaded != null && imported != null) renderer.box(new AABB(imported.getX(), imported.getY(), imported.getZ(),

					imported.getX() + (double) loaded.width(), imported.getY() + (double) loaded.height(), imported.getZ() + (double) loaded.depth()), 0, 0xDC92E6FF, 2.5f);

		} finally { renderer.end(); }

	}

	@Override public void onRender2D(HudContext hud) {

		if (!inGame() || mc.gui.screen() != null) return;

		String line = Lang.t("ui.building.keys") + "  ·  " + dimensions();

		float width = Math.min(hud.width - 24, hud.c.textWidth(Fonts.Face.REGULAR, line, 10) + 24), x = (hud.width - width) / 2, y = hud.height - 88;

		hud.panel(x, y, width, 34, 12); hud.c.textMid(Fonts.Face.REGULAR, line, hud.width / 2, y + 10, 10, 0xF0FFFFFF);

		hud.c.textMid(Fonts.Face.REGULAR, status().isEmpty() ? Lang.t("ui.building.aimHint") : status(), hud.width / 2, y + 25, 8.5f, 0xBAFFFFFF);

	}

	private abstract class Job {

		final IntegratedServer server; final ServerLevel level; final long token; final BlockPos origin; final int width, height, depth;

		Job(IntegratedServer server, ServerLevel level, long token, BlockPos origin, int width, int height, int depth) {

			this.server = server; this.level = level; this.token = token; this.origin = origin; this.width = width; this.height = height; this.depth = depth;

		}

		BlockPos position(int index) { return origin.offset(index % width, index / (width * depth), index / width % depth); }

		abstract void step();

	}

	/** Remote work stays on the client thread and never changes ClientLevel to imitate a server result. */

	private abstract class RemoteJob extends Job {

		final ClientLevel clientLevel = mc.level;

		final ClientPacketListener clientConnection = mc.getConnection();

		RemoteJob(long token, BlockPos origin, int width, int height, int depth) { super(null, null, token, origin, width, height, depth); }

		boolean active() { return token == generation.get() && clientLevel == mc.level && clientConnection == mc.getConnection(); }

		boolean available(BlockPos position) { return clientLevel.isInWorldBounds(position) && clientLevel.hasChunkAt(position) && clientLevel.getWorldBorder().isWithinBounds(position); }

		void fail(String key) { releaseJob(this); complete(token, key, null); }

	}

	private final class RemoteSaveJob extends RemoteJob {

		final String name; final int[] blocks; final List<CompoundTag> palette = new ArrayList<>();

		final Map<BlockState, Integer> indices = new HashMap<>(); final Map<Integer, CompoundTag> entities = new LinkedHashMap<>(); int cursor;

		RemoteSaveJob(long token, BlockPos minimum, BlockPos maximum, String name) {

			super(token, minimum, maximum.getX() - minimum.getX() + 1, maximum.getY() - minimum.getY() + 1, maximum.getZ() - minimum.getZ() + 1);

			this.name = name; blocks = new int[width * height * depth];

		}

		@Override void step() {

			long end = System.nanoTime() + 4_000_000L; int work = 0;

			while (cursor < blocks.length && work++ < 768 && System.nanoTime() < end && active()) {

				BlockPos position = position(cursor);

				if (!available(position)) { fail("ui.building.clientAreaUnavailable"); return; }

				BlockState state = clientLevel.getBlockState(position); Integer index = indices.get(state);

				if (index == null) { index = palette.size(); indices.put(state, index); palette.add(NbtUtils.writeBlockState(state)); }

				blocks[cursor] = index;

				BlockEntity entity = clientLevel.getBlockEntity(position);

				if (entity != null) {

					// Save only synchronized presentation data. An unopened server chest's inventory is not known here.

					CompoundTag data = entity.getUpdateTag(clientLevel.registryAccess());

					if (!data.isEmpty()) {

						data = data.copy(); data.remove("x"); data.remove("y"); data.remove("z");

						data.putString("id", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).toString()); entities.put(cursor, data);

					}

				}

				cursor++; progress = cursor;

			}

			if (cursor == blocks.length) {

				releaseJob(this); Blueprint blueprint = new Blueprint(name, width, height, depth, List.copyOf(palette), blocks, Map.copyOf(entities));

				BuildingFiles.IO.execute(() -> {

					if (token != generation.get()) return;

					try { complete(token, "ui.building.savedClientOnly", BuildingFiles.save(blueprint)); }

					catch (Exception failure) { complete(token, "ui.building.fileError", null); }

				});

			}

		}

	}

	/** Only commands sent by this operation are eligible for undo, including one whose reply arrived after cancellation. */

	private static final class RemoteUndoSnapshot {

		final String name; final BlockPos origin; final int width, height, depth;

		final int[] original, expected; final List<BlockState> originalPalette = new ArrayList<>(), expectedPalette;

		final Map<BlockState, Integer> indices = new HashMap<>(); final BitSet requested, confirmed;

		RemoteUndoSnapshot(BlockPos origin, Blueprint building, List<BlockState> states) {

			this.origin = origin; name = building.name(); width = building.width(); height = building.height(); depth = building.depth();

			original = new int[building.count()]; expected = building.blocks(); expectedPalette = List.copyOf(states); requested = new BitSet(building.count()); confirmed = new BitSet(building.count());

		}

		BlockPos position(int index) { return origin.offset(index % width, index / (width * depth), index / width % depth); }

		void sent(int index, BlockState previous) {

			Integer paletteIndex = indices.get(previous);

			if (paletteIndex == null) { paletteIndex = originalPalette.size(); indices.put(previous, paletteIndex); originalPalette.add(previous); }

			original[index] = paletteIndex; requested.set(index);

		}

		BlockState before(int index) { return originalPalette.get(original[index]); }

		BlockState after(int index) { return expectedPalette.get(expected[index]); }

		int remaining() { return requested.cardinality(); }

	}

	private final class RemotePasteJob extends RemoteJob {

		final Blueprint building; final boolean includeAir; final List<BlockState> states = new ArrayList<>();

		RemoteUndoSnapshot backup; int paletteCursor, checked, cursor, pending = -1; long sentAt, nextCommandAt; boolean published, acknowledged;

		RemotePasteJob(long token, BlockPos origin, Blueprint building, boolean includeAir) {

			super(token, origin, building.width(), building.height(), building.depth()); this.building = building; this.includeAir = includeAir;

			total = building.count() * 2;

		}

		@Override void step() {

			if (!active()) return;

			if (!canUseRemoteCommands()) { fail("ui.building.remotePermission"); return; }

			long end = System.nanoTime() + 4_000_000L; int work = 0;

			while (paletteCursor < building.palette().size() && work++ < 256 && System.nanoTime() < end) {

				CompoundTag tag = building.palette().get(paletteCursor++); Identifier name = Identifier.tryParse(tag.getStringOr("Name", ""));

				var registry = clientLevel.registryAccess().lookupOrThrow(Registries.BLOCK);

				if (name == null || !registry.containsKey(name)) { fail("ui.building.missingBlocks"); return; }

				states.add(NbtUtils.readBlockState(registry, tag));

			}

			if (paletteCursor < building.palette().size()) return;

			// Validate all destinations and command lengths before sending the first world-changing command.

			while (checked < building.count() && work++ < 768 && System.nanoTime() < end && active()) {

				BlockPos position = position(checked); BlockState desired = states.get(building.blocks()[checked]);

				if (includeAir || !desired.isAir()) {

					if (!available(position)) { fail("ui.building.clientAreaUnavailable"); return; }

					BlockState previous = clientLevel.getBlockState(position);

					if (previous != desired || building.blockEntities().containsKey(checked)) {

						// Server-only inventories cannot be backed up from the client's block entity.

						if (previous.hasBlockEntity()) { fail("ui.building.remoteOccupiedEntity"); return; }

						if (command(position, desired, building.blockEntities().get(checked)).length() > 32_000) { fail("ui.building.remoteCommandTooLong"); return; }

					}

				}

				checked++; progress = checked;

			}

			if (checked < building.count()) return;

			if (backup == null) backup = new RemoteUndoSnapshot(origin, building, states);

			long now = System.currentTimeMillis();

			if (pending >= 0) {

				BlockPos position = position(pending);

				if (!available(position)) { fail("ui.building.clientAreaUnavailable"); return; }

				if (acknowledged && clientLevel.getBlockState(position) == backup.after(pending)) {

					backup.confirmed.set(pending); pending = -1; cursor++; progress = building.count() + cursor;

				} else if (now - sentAt >= remoteTimeout.val()) { fail("ui.building.remoteUnconfirmed"); return; }

				else return;

			}

			while (cursor < building.count() && work++ < 768 && System.nanoTime() < end && active()) {

				BlockState desired = states.get(building.blocks()[cursor]); BlockPos position = position(cursor);

				if (!includeAir && desired.isAir()) { cursor++; progress = building.count() + cursor; continue; }

				if (!available(position)) { fail("ui.building.clientAreaUnavailable"); return; }

				BlockState previous = clientLevel.getBlockState(position); CompoundTag data = building.blockEntities().get(cursor);

				if (previous == desired && data == null) { cursor++; progress = building.count() + cursor; continue; }

				if (previous.hasBlockEntity()) { fail("ui.building.remoteOccupiedEntity"); return; }

				if (now < nextCommandAt) return;

				if (!published) { remoteUndoSnapshots.put(clientLevel.dimension(), backup); published = true; }

				backup.sent(cursor, previous); pending = cursor; acknowledged = false; sentAt = now; nextCommandAt = now + remoteDelay.intVal();

				clientConnection.sendCommand(command(position, desired, data)); return;

			}

			if (cursor == building.count()) { releaseJob(this); complete(token, "ui.building.placed", null); }

		}

	}

	private final class RemoteUndoJob extends RemoteJob {

		final RemoteUndoSnapshot snapshot; int cursor, pending = -1, conflicts; long sentAt, nextCommandAt; boolean acknowledged; String verificationToken;

		RemoteUndoJob(long token, RemoteUndoSnapshot snapshot) { super(token, snapshot.origin, snapshot.width, snapshot.height, snapshot.depth); this.snapshot = snapshot; }

		@Override void step() {

			if (!active()) return;

			if (!canUseRemoteCommands()) { fail("ui.building.remotePermission"); return; }

			long now = System.currentTimeMillis();

			if (pending >= 0) {

				BlockPos position = position(pending);

				if (!available(position)) { fail("ui.building.clientAreaUnavailable"); return; }

				if (acknowledged && clientLevel.getBlockState(position) == snapshot.before(pending)) {

					snapshot.requested.clear(pending); cursor = pending + 1; pending = -1; progress++;

				} else if (now - sentAt >= remoteTimeout.val()) { fail("ui.building.remoteUnconfirmed"); return; }

				else {

					if (!acknowledged && verificationToken == null && now >= nextCommandAt) {

						if (canVerifyRemoteCommands()) {

							// /setblock on an unchanged block legitimately produces no update packet. This command

							// executes after it on the same connection and replies only if the server has the original state.

							verificationToken = "CPBUILD-" + UUID.randomUUID(); rememberVerification(verificationToken, now);

							String verify = "execute if block " + position.getX() + " " + position.getY() + " " + position.getZ() + " "

									+ BlockStateParser.serialize(snapshot.before(pending)) + " run tellraw @s {\"text\":\"" + verificationToken + "\"}";

							clientConnection.sendCommand(verify); nextCommandAt = now + remoteDelay.intVal();

						} else if (clientLevel.getBlockState(position) == snapshot.before(pending)) { fail("ui.building.remoteVerificationUnavailable"); return; }

					}

					return;

				}

			}

			long end = System.nanoTime() + 4_000_000L; int work = 0;

			while (work++ < 768 && System.nanoTime() < end && active()) {

				int index = snapshot.requested.nextSetBit(cursor);

				if (index < 0) {

					if (snapshot.remaining() == 0) remoteUndoSnapshots.remove(clientLevel.dimension(), snapshot);

					releaseJob(this); complete(token, conflicts > 0 ? "ui.building.remoteUndoConflict" : "ui.building.undone", null); return;

				}

				BlockPos position = snapshot.position(index);

				if (!available(position)) { fail("ui.building.clientAreaUnavailable"); return; }

				BlockState current = clientLevel.getBlockState(position);

				// Keep conflicting entries available for retry; never overwrite another player's later edit.

				if (current != snapshot.after(index) && current != snapshot.before(index)) { conflicts++; cursor = index + 1; progress++; continue; }

				if (now < nextCommandAt) return;

				pending = index; acknowledged = false; verificationToken = null; sentAt = now; nextCommandAt = now + remoteDelay.intVal();

				// The condition also closes the race between checking a client block and another player's server edit.

				String undo = "execute if block " + position.getX() + " " + position.getY() + " " + position.getZ() + " "

						+ BlockStateParser.serialize(snapshot.after(index)) + " run " + command(position, snapshot.before(index), null);

				clientConnection.sendCommand(undo); return;

			}

		}

	}

	private static String command(BlockPos position, BlockState state, CompoundTag data) {

		String block = BlockStateParser.serialize(state);

		if (data != null && state.hasBlockEntity()) {

			CompoundTag copy = data.copy(); copy.remove("x"); copy.remove("y"); copy.remove("z"); block += copy;

		}

		return "setblock " + position.getX() + " " + position.getY() + " " + position.getZ() + " " + block + " replace";

	}

	private final class SaveJob extends Job {

		final String name; final int[] blocks; final List<CompoundTag> palette = new ArrayList<>();

		final Map<BlockState, Integer> indices = new HashMap<>(); final Map<Integer, CompoundTag> entities = new LinkedHashMap<>(); int cursor;

		SaveJob(IntegratedServer server, ServerLevel level, long token, BlockPos minimum, BlockPos maximum, String name) {

			super(server, level, token, minimum, maximum.getX() - minimum.getX() + 1, maximum.getY() - minimum.getY() + 1, maximum.getZ() - minimum.getZ() + 1);

			this.name = name; blocks = new int[width * height * depth];

		}

		@Override void step() {

			long end = System.nanoTime() + 4_000_000L; int work = 0;

			while (cursor < blocks.length && work++ < 768 && System.nanoTime() < end && token == generation.get()) {

				BlockPos position = position(cursor);

				level.getChunkAt(position);

				BlockState state = level.getBlockState(position); Integer index = indices.get(state);

				if (index == null) { index = palette.size(); indices.put(state, index); palette.add(NbtUtils.writeBlockState(state)); }

				blocks[cursor] = index;

				BlockEntity entity = level.getBlockEntity(position);

				if (entity != null) {

					CompoundTag data = entity.saveWithFullMetadata(level.registryAccess()); data.remove("x"); data.remove("y"); data.remove("z"); entities.put(cursor, data);

				}

				cursor++; progress = cursor;

			}

			if (cursor == blocks.length) {

				releaseJob(this); Blueprint blueprint = new Blueprint(name, width, height, depth, List.copyOf(palette), blocks, Map.copyOf(entities));

				BuildingFiles.IO.execute(() -> {

					if (token != generation.get()) return;

					try { Blueprint saved = BuildingFiles.save(blueprint); complete(token, "ui.building.saved", saved); }

					catch (Exception failure) { complete(token, "ui.building.fileError", null); }

				});

			}

		}

	}

	private final class PasteJob extends Job {

		final Blueprint building; final boolean includeAir; final List<BlockState> states = new ArrayList<>(); final BuildingUndoSnapshot backup;

		int paletteCursor, cursor; boolean published;

		PasteJob(IntegratedServer server, ServerLevel level, long token, BlockPos origin, Blueprint building, boolean includeAir) {

			super(server, level, token, origin, building.width(), building.height(), building.depth()); this.building = building; this.includeAir = includeAir;

			// Allocate the complete backup index before a new export can replace the previous undo.

			backup = new BuildingUndoSnapshot(server, level, origin, width, height, depth, building.name());

		}

		@Override void step() {

			long end = System.nanoTime() + 4_000_000L; int work = 0;

			while (paletteCursor < building.palette().size() && work++ < 256 && System.nanoTime() < end) {

				CompoundTag tag = building.palette().get(paletteCursor++);

				Identifier name = Identifier.tryParse(tag.getStringOr("Name", ""));

				var registry = level.registryAccess().lookupOrThrow(Registries.BLOCK);

				if (name == null || !registry.containsKey(name)) { releaseJob(this); complete(token, "ui.building.missingBlocks", null); return; }

				states.add(NbtUtils.readBlockState(registry, tag));

			}

			if (paletteCursor < building.palette().size()) return;

			while (cursor < building.count() * 2 && work++ < 768 && System.nanoTime() < end && token == generation.get()) {

				int index = cursor % building.count(); BlockPos position = position(index); BlockState state = states.get(building.blocks()[index]);

				if (includeAir || !state.isAir()) {

					// Import may extend beyond the player's view distance: load one needed chunk, never a whole region at once.

					level.getChunkAt(position);

					if (cursor < building.count()) {

						backup.capture(index);

						if (!published) {

							synchronized (undoSnapshots) {

								if (token != generation.get() || undoServer != server) return;

								undoSnapshots.put(level.dimension(), backup); published = true;

							}

						}

						placeState(level, position, state, building.blockEntities().get(index));

					} else {

						BlockState current = level.getBlockState(position), shaped = Block.updateFromNeighbourShapes(current, level, position);

						if (current != shaped) level.setBlock(position, shaped, 18);

						level.updateNeighborsAt(position, shaped.getBlock(), null);

					}

				}

				cursor++; progress = cursor;

			}

			if (cursor == building.count() * 2) { releaseJob(this); complete(token, "ui.building.placed", null); }

		}

	}

	private final class UndoJob extends Job {

		final BuildingUndoSnapshot snapshot;

		UndoJob(IntegratedServer server, ServerLevel level, long token, BuildingUndoSnapshot snapshot) {

			super(server, level, token, snapshot.origin, snapshot.width, snapshot.height, snapshot.depth); this.snapshot = snapshot;

		}

		@Override void step() {

			long end = System.nanoTime() + 4_000_000L; int work = 0;

			while (!snapshot.finished() && work++ < 768 && System.nanoTime() < end && token == generation.get()) {

				snapshot.skipUntouched(); if (snapshot.finished()) break;

				int cursor = snapshot.cursor(), index = cursor % snapshot.count; BlockPos position = snapshot.position(index);

				level.getChunkAt(position);

				if (cursor < snapshot.count) placeState(level, position, snapshot.state(index), snapshot.entityData(index));

				else level.updateNeighborsAt(position, level.getBlockState(position).getBlock(), null);

				snapshot.advance(); progress = snapshot.cursor();

			}

			if (snapshot.finished()) { undoSnapshots.remove(level.dimension(), snapshot); releaseJob(this); complete(token, "ui.building.undone", null); }

		}

	}

	private static void placeState(ServerLevel level, BlockPos position, BlockState state, CompoundTag data) {

		BlockState previous = level.getBlockState(position);

		com.cheatprogram.compat.WorldBridge.clear(level.getBlockEntity(position));

		// Recreate a block entity even when replacing an identical block state; stale inventories,

		// custom names or sign text must not survive an export or a subsequent undo.

		if (previous.hasBlockEntity() || state.hasBlockEntity()) {

			level.removeBlockEntity(position); level.setBlock(position, Blocks.BARRIER.defaultBlockState(), 18);

		}

		level.setBlock(position, state, 18);

		BlockEntity entity = level.getBlockEntity(position);

		if (data != null && entity != null) {

			CompoundTag copy = data.copy(); copy.putInt("x", position.getX()); copy.putInt("y", position.getY()); copy.putInt("z", position.getZ());

			try (var errors = new net.minecraft.util.ProblemReporter.ScopedCollector(entity.problemPath(), com.cheatprogram.CheatProgram.LOGGER)) {

                entity.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(errors, level.registryAccess(), copy));

            } entity.setChanged(); level.sendBlockUpdated(position, state, state, 3);

		}

	}

}

