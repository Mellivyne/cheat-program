package com.cheatprogram.util;

import com.cheatprogram.CheatProgram;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Local observations only: a real opening and its server content packet are required for every entry. */
public final class ContainerIndex {
	public record Item(String id, String name, int count) { }
	public record Entry(int x, int y, int z, String title, long timestamp, List<Item> items) {
		public BlockPos position() { return new BlockPos(x, y, z); }
		String key() { return x + "," + y + "," + z; }
		public int total() { return items.stream().mapToInt(Item::count).sum(); }
	}
	private record Descriptor(String identity, String label) { }
	private record Pending(Object level, BlockPos position, long time) { }
	private static final class Session {
		final Object level, menu; final String context, title; final BlockPos position; final int id, slots;
		List<ItemStack> stacks;
		Session(AbstractContainerScreen<?> screen, Pending origin, String context) {
			level = MC.level; menu = screen.getMenu(); this.context = context; title = screen.getTitle().getString();
			position = origin.position; id = screen.getMenu().containerId; slots = screen.getMenu().slots.size() - 36;
		}
	}
	private static final class Archive {
		final Descriptor descriptor; final Path path; final Map<String, Entry> entries = new HashMap<>();
		boolean loading = true, dirty, writing;
		Archive(Descriptor descriptor) { this.descriptor = descriptor; path = DIR.resolve(hash(descriptor.identity) + ".json"); }
	}
	private static final Minecraft MC = Minecraft.getInstance();
	private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("cheatprogram/container-index").toAbsolutePath().normalize();
	private static final Object LOCK = new Object();
	// Reuse one owner per archive path so a rapid dimension/world return cannot race an older
	// pending write against a newly loaded copy of the same index.
	private static final Map<String, Archive> ARCHIVES = new HashMap<>();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "Cheat Program container index"); thread.setDaemon(true); return thread; });
	private static Archive active;
	private static Pending pending;
	private static Session session;
	private static volatile long revision;
	private static long nextSave;
	private ContainerIndex() { }
	public static long revision() { return revision; }
	public static String context() { return active == null ? "" : active.descriptor.identity; }
	public static String label() { return active == null ? "" : active.descriptor.label; }
	public static boolean loading() { synchronized (LOCK) { return active != null && active.loading; } }
	public static void tick() {
		ensureContext();
		if (session != null && (session.level != MC.level || MC.player == null || MC.player.containerMenu != session.menu)) session = null;
		if (System.currentTimeMillis() >= nextSave) { nextSave = System.currentTimeMillis() + 1000; synchronized (LOCK) { queueSave(active); } }
	}
	private static void ensureContext() {
		Descriptor descriptor = describe();
		if (descriptor == null ? active == null : active != null && active.descriptor.identity.equals(descriptor.identity)) return;
		synchronized (LOCK) {
			queueSave(active); pending = null; session = null;
			if (descriptor == null) active = null;
			else {
				Archive cached = ARCHIVES.get(descriptor.identity);
				if (cached == null) {
					cached = new Archive(descriptor); ARCHIVES.put(descriptor.identity, cached);
					Archive created = cached; IO.execute(() -> load(created));
				}
				active = cached;
			}
			revision++;
		}
	}
	private static Descriptor describe() {
		if (MC.level == null || MC.getConnection() == null) return null;
		String dim = MC.level.dimension().identifier().toString(); var server = MC.getSingleplayerServer();
		if (server != null) return new Descriptor("world:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize() + "|" + dim, server.getWorldData().getLevelName() + " · " + dim);
		var data = MC.getCurrentServer();
		String address = data != null ? data.ip : String.valueOf(MC.getConnection().getConnection().getRemoteAddress());
		return new Descriptor("server:" + address.strip().toLowerCase(Locale.ROOT) + "|" + dim, (data == null ? address : data.name) + " · " + dim);
	}
	public static void interacted(BlockHitResult hit) {
		ensureContext();
		if (active == null || MC.player == null || MC.screen != null || MC.player.isShiftKeyDown() || !MC.level.hasChunkAt(hit.getBlockPos())) return;
		pending = null;
		var block = MC.level.getBlockState(hit.getBlockPos()).getBlock();
		if (!(block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock || block instanceof EnderChestBlock)) return;
		BlockPos position = hit.getBlockPos().immutable();
		if (block instanceof ChestBlock) {
			var state = MC.level.getBlockState(position);
			if (state.getValue(ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
				BlockPos other = position.relative(ChestBlock.getConnectedDirection(state));
				if (other.asLong() < position.asLong()) position = other;
			}
		}
		pending = new Pending(MC.level, position.immutable(), System.currentTimeMillis());
	}
	public static void openedScreen(Screen screen) {
		ensureContext();
		if (active == null || MC.player == null || !(screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen)) return;
		var container = (AbstractContainerScreen<?>) screen;
		if (container.getMenu() != MC.player.containerMenu || session != null && session.menu == container.getMenu()) return;
		Pending origin = pending; pending = null; session = null;
		if (origin == null || origin.level != MC.level || System.currentTimeMillis() - origin.time > 5000) return;
		int slots = container.getMenu().slots.size() - 36;
		if (slots > 0 && slots <= 54) session = new Session(container, origin, context());
	}
	private static boolean matches(int id) {
		return session != null && MC.player != null && session.level == MC.level && session.id == id && MC.player.containerMenu == session.menu && session.context.equals(context());
	}
	public static void receivedContent(ClientboundContainerSetContentPacket packet) {
		if (!matches(packet.containerId()) || packet.items().size() < session.slots) return;
		List<ItemStack> stacks = new ArrayList<>(session.slots);
		for (int i = 0; i < session.slots; i++) stacks.add(packet.items().get(i).copy());
		session.stacks = stacks; record();
	}
	public static void receivedSlot(ClientboundContainerSetSlotPacket packet) {
		if (!matches(packet.getContainerId()) || session.stacks == null || packet.getSlot() < 0 || packet.getSlot() >= session.slots) return;
		session.stacks.set(packet.getSlot(), packet.getItem().copy()); record();
	}
	/** Contents sampled from the player's own currently opened integrated-server menu. */
	public static void receivedOwnedMenu(int menuId, List<ItemStack> items) {
		if (!matches(menuId) || items.size() < session.slots) return;
		List<ItemStack> copied = new ArrayList<>(session.slots);
		for (int i = 0; i < session.slots; i++) copied.add(items.get(i).copy());
		session.stacks = copied; record();
	}
	private static void record() {
		if (active == null || session == null || session.stacks == null) return;
		Map<String, Item> items = new HashMap<>();
		for (ItemStack stack : session.stacks) if (!stack.isEmpty()) {
			String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), name = stack.getHoverName().getString(), key = id + "\n" + name;
			Item previous = items.get(key); items.put(key, new Item(id, name, stack.getCount() + (previous == null ? 0 : previous.count)));
		}
		BlockPos p = session.position; Entry entry = new Entry(p.getX(), p.getY(), p.getZ(), session.title, System.currentTimeMillis(), List.copyOf(items.values()));
		synchronized (LOCK) { active.entries.put(entry.key(), entry); active.dirty = true; revision++; }
	}
	public static List<Entry> search(String text) {
		String query = text.strip().toLowerCase(Locale.ROOT);
		synchronized (LOCK) {
			if (active == null) return List.of();
			return active.entries.values().stream().filter(entry -> query.isEmpty() || entry.title.toLowerCase(Locale.ROOT).contains(query)
					|| entry.items.stream().anyMatch(item -> item.id.toLowerCase(Locale.ROOT).contains(query) || item.name.toLowerCase(Locale.ROOT).contains(query)))
					.sorted(Comparator.comparingLong(Entry::timestamp).reversed()).toList();
		}
	}
	private static void load(Archive archive) {
		try {
			Entry[] entries = Files.isRegularFile(archive.path, LinkOption.NOFOLLOW_LINKS) ? JSON.fromJson(Files.readString(archive.path, StandardCharsets.UTF_8), Entry[].class) : null;
			synchronized (LOCK) {
				if (entries != null) for (Entry entry : entries) if (valid(entry)) archive.entries.merge(entry.key(), entry, (old, loaded) -> old.timestamp >= loaded.timestamp ? old : loaded);
			}
		} catch (Exception failure) { CheatProgram.LOGGER.warn("Could not load container observations", failure); }
		finally { synchronized (LOCK) { archive.loading = false; revision++; queueSave(archive); } }
	}
	private static boolean valid(Entry entry) {
		return entry != null && entry.title != null && entry.timestamp > 0 && entry.items != null
				&& entry.items.stream().allMatch(item -> item != null && item.id != null && item.name != null && item.count >= 0);
	}
	private static void queueSave(Archive archive) {
		if(!com.cheatprogram.core.Config.persistenceAllowed())return;
		if (archive == null || !archive.dirty || archive.loading || archive.writing || IO.isShutdown()) return;
		List<Entry> snapshot = List.copyOf(archive.entries.values()); archive.dirty = false; archive.writing = true;
		IO.execute(() -> {
			synchronized(com.cheatprogram.core.Config.persistenceLock()) {
			if(!com.cheatprogram.core.Config.persistenceAllowed()){synchronized(LOCK){archive.writing=false;archive.dirty=false;}return;}
			boolean success = false;
			try {
				Files.createDirectories(DIR); Path temp = archive.path.resolveSibling(archive.path.getFileName() + ".tmp");
				Files.writeString(temp, JSON.toJson(snapshot), StandardCharsets.UTF_8);
				try { Files.move(temp, archive.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
				catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temp, archive.path, StandardCopyOption.REPLACE_EXISTING); }
				success = true;
			} catch (Exception failure) { synchronized (LOCK) { archive.dirty = true; } CheatProgram.LOGGER.warn("Could not save container observations", failure); }
			finally { synchronized (LOCK) { archive.writing = false; if (success) queueSave(archive); } }
			}
		});
	}
	private static String hash(String text) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
		catch (Exception impossible) { throw new IllegalStateException(impossible); }
	}
	public static void shutdown() {
		try {
			IO.submit(() -> { }).get(2, TimeUnit.SECONDS);
			synchronized (LOCK) { for (Archive archive : ARCHIVES.values()) queueSave(archive); }
			IO.submit(() -> { }).get(2, TimeUnit.SECONDS);
		} catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		catch (Exception failure) { CheatProgram.LOGGER.warn("Container index shutdown flush was incomplete", failure); }
		IO.shutdown(); try { IO.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
	}
}
