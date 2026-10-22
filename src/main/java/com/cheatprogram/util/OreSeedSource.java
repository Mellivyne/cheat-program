package com.cheatprogram.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Set;

/** Read-only seed resolution. SeedCrackerX remains optional and no server command is sent. */
public final class OreSeedSource implements AutoCloseable {
	public enum Source { NONE, MANUAL, INTEGRATED, CRACKED, AMBIGUOUS, INVALID }
	private static final Minecraft MC = Minecraft.getInstance();
	private static volatile OreSeedSource owner;
	/** Client-thread receipt survives a module off/on; it never stores a mutable SCX set. */
	private static Receipt receipt;
	private record Receipt(Object connection, Object machine, Long hash, Long seed, boolean ambiguous) { }
	private ClientLevel world;
	private Object connection;
	private volatile Object machine;
	private volatile long epoch;
	private Long crackedSeed, resolvedSeed;
	private Source source = Source.NONE;
	private boolean ambiguous, available, collecting;
	private Object controlledConfig;
	private Boolean previousActive;
	private Scx scx;
	private final ArrayDeque<ChunkPos> initialChunks = new ArrayDeque<>();
	private long lastScanTime = Long.MIN_VALUE;

	/**
	 * Call on the client thread while seed prediction is selected. A nonempty manual value
	 * takes precedence. Collection changes only SCX's in-memory active flag, never its file.
	 */
	public void update(ClientLevel level, String manualSeed, boolean autoRead, boolean collect) {
		Object currentConnection = MC.getConnection() == null ? null : MC.getConnection().getConnection();
		if (level == null || level != MC.level || currentConnection == null) { clear(); return; }
		if (world != level || connection != currentConnection) {
			clear();
			world = level;
			connection = currentConnection;
			scx = Scx.find();
			available = scx != null;
			machine = currentMachine();
			epoch++;
		}
		Object currentMachine = currentMachine();
		if (currentMachine != machine) {
			machine = currentMachine;
			crackedSeed = null;
			ambiguous = false;
			epoch++;
		}
		restoreReceipt();
		String manual = manualSeed == null ? "" : manualSeed.trim();
		if (autoRead && available) owner = this;
		else if (owner == this) owner = null;
		setCollecting(autoRead && collect && manual.isEmpty() && crackedSeed == null && available && MC.getSingleplayerServer() == null);
		scanInitialChunk();
		resolvedSeed = null;
		if (!manual.isEmpty()) {
			try { resolvedSeed = Long.parseLong(manual); source = Source.MANUAL; }
			catch (NumberFormatException ignored) { source = Source.INVALID; }
		} else if (!autoRead) source = Source.NONE;
		else if (MC.getSingleplayerServer() != null) {
			// WorldOptions is immutable; reading it never requests/generates a server chunk.
			resolvedSeed = MC.getSingleplayerServer().getWorldData().worldGenOptions().seed();
			source = Source.INTEGRATED;
		} else if (crackedSeed != null) { resolvedSeed = crackedSeed; source = Source.CRACKED; }
		else source = ambiguous ? Source.AMBIGUOUS : Source.NONE;
	}

	public Long seed() { return resolvedSeed; }
	public Source source() { return source; }
	public boolean crackerAvailable() { return available; }
	public boolean collecting() { return collecting; }
	public String statusKey() {
		return switch (source) {
			case MANUAL -> "ui.orexray.seedManual";
			case INTEGRATED -> "ui.orexray.seedIntegrated";
			case CRACKED -> "ui.orexray.seedCracked";
			case AMBIGUOUS -> "ui.orexray.seedAmbiguous";
			case INVALID -> "ui.orexray.seedInvalid";
			case NONE -> !available ? "ui.orexray.seedMissingCracker"
					: collecting ? "ui.orexray.seedWaiting" : "ui.orexray.seedCollectionOff";
		};
	}

	public void clear() {
		if (owner == this) owner = null;
		epoch++;
		restoreActive();
		world = null;
		connection = machine = null;
		crackedSeed = resolvedSeed = null;
		ambiguous = available = collecting = false;
		initialChunks.clear();
		lastScanTime = Long.MIN_VALUE;
		source = Source.NONE;
		scx = null;
	}
	@Override public void close() { clear(); }

	private Object currentMachine() {
		if (scx == null) return null;
		try { return scx.timeMachine(); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
	}
	private void restoreReceipt() {
		Receipt saved = receipt;
		if (saved == null) return;
		if (saved.connection != connection || saved.machine != machine) { receipt = null; return; }
		try {
			Long hash = scx == null ? null : scx.hashedSeed();
			if ((saved.hash != null && hash != null && !saved.hash.equals(hash))
					|| (saved.seed != null && hash != null && hash != 0 && BiomeManager.obfuscateSeed(saved.seed) != hash)) {
				crackedSeed = null;
				ambiguous = false;
				return;
			}
			crackedSeed = saved.seed;
			ambiguous = saved.ambiguous;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
	}
	private void setCollecting(boolean value) {
		if (!value) { restoreActive(); collecting = false; initialChunks.clear(); return; }
		try {
			boolean beginning = !collecting;
			Object config = scx.config();
			if (controlledConfig != config) {
				restoreActive();
				controlledConfig = config;
				previousActive = scx.active.getBoolean(config);
			}
			scx.active.setBoolean(config, true);
			collecting = true;
			if (beginning) queueInitialChunks();
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
			restoreActive();
			collecting = false;
		}
	}
	private void queueInitialChunks() {
		initialChunks.clear();
		if (MC.player == null || world == null) return;
		ChunkPos center = MC.player.chunkPosition();
		int radius = Math.max(2, Math.min(32, MC.options.getEffectiveRenderDistance()));
		// Near to far and only already loaded columns. Never request an unknown chunk.
		for (int distance = 0; distance <= radius; distance++)
			for (int x = -distance; x <= distance; x++) for (int z = -distance; z <= distance; z++) {
				if (Math.max(Math.abs(x), Math.abs(z)) != distance) continue;
				int cx = center.x + x, cz = center.z + z;
				if (world.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null)
					initialChunks.add(new ChunkPos(cx, cz));
			}
	}
	private void scanInitialChunk() {
		if (!collecting || initialChunks.isEmpty() || world == null || world.getGameTime() == lastScanTime) return;
		lastScanTime = world.getGameTime();
		ChunkPos next = initialChunks.removeFirst();
		if (world.getChunkSource().getChunk(next.x, next.z, ChunkStatus.FULL, false) == null) return;
		try { scx.scan(world, next); }
		catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { initialChunks.clear(); }
	}
	private void restoreActive() {
		if (controlledConfig != null && previousActive != null && scx != null) {
			try {
				// Preserve a manual SCX switch-off made while we were collecting.
				if (scx.config() == controlledConfig && scx.active.getBoolean(controlledConfig))
					scx.active.setBoolean(controlledConfig, previousActive);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
		}
		controlledConfig = null;
		previousActive = null;
	}

	/** Captured at poke HEAD on SCX's worker, before the set can be populated. */
	public record CrackTicket(OreSeedSource source, Object machine, long epoch) { }
	public static CrackTicket captureCrack(Object machine) {
		OreSeedSource current = owner;
		return current == null || current.machine != machine ? null : new CrackTicket(current, machine, current.epoch);
	}

	/** Called at poke RETURN on the same worker that owns TimeMachine.worldSeeds. */
	public static void finishedCrack(CrackTicket ticket, Object machine) {
		if (ticket == null || ticket.machine != machine) return;
		try {
			if (machine.getClass().getField("shouldTerminate").getBoolean(machine)) return;
			Object value = machine.getClass().getField("worldSeeds").get(machine);
			if (!(value instanceof Set<?> seeds)) return;
			// Deliberately never read this HashSet from the client thread.
			int count = seeds.size();
			Long seed = count == 1 && seeds.iterator().next() instanceof Number n ? n.longValue() : null;
			MC.execute(() -> ticket.source.acceptCrack(ticket, seed, count > 1));
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
	}
	private void acceptCrack(CrackTicket ticket, Long seed, boolean many) {
		Object currentConnection = MC.getConnection() == null ? null : MC.getConnection().getConnection();
		if (owner != this || ticket.epoch != epoch || world == null || world != MC.level || connection != currentConnection
				|| ticket.machine != machine || currentMachine() != machine) return;
		Long hash;
		try {
			// SCX can keep a machine across dimension changes. Reject a result for a different
			// server-world seed, even if that old machine returns early with a unique old result.
			hash = scx.hashedSeed();
			if (seed != null && hash != null && hash != 0 && BiomeManager.obfuscateSeed(seed) != hash) {
				crackedSeed = null;
				ambiguous = false;
				return;
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return; }
		crackedSeed = seed;
		ambiguous = many;
		receipt = new Receipt(connection, machine, hash, seed, many);
	}

	/** Only public, unmapped SCX-owned members are reflected. */
	private static final class Scx {
		private final Method getCracker, getStorage, getMachine, getConfig, getQueue, onChunkData, getHash;
		private final Field active, hashedSeed;
		private Scx(Method getCracker, Method getStorage, Method getMachine, Method getConfig, Field active,
				Method getQueue, Method onChunkData, Field hashedSeed, Method getHash) {
			this.getCracker = getCracker;
			this.getStorage = getStorage;
			this.getMachine = getMachine;
			this.getConfig = getConfig;
			this.active = active;
			this.getQueue = getQueue;
			this.onChunkData = onChunkData;
			this.hashedSeed = hashedSeed;
			this.getHash = getHash;
		}
		private static Scx find() {
			if (!FabricLoader.getInstance().isModLoaded("seedcrackerx")) return null;
			try {
				ClassLoader loader = OreSeedSource.class.getClassLoader();
				Class<?> cracker = Class.forName("kaptainwutax.seedcrackerX.SeedCracker", false, loader);
				Class<?> storage = Class.forName("kaptainwutax.seedcrackerX.cracker.storage.DataStorage", false, loader);
				Class<?> config = Class.forName("kaptainwutax.seedcrackerX.config.Config", false, loader);
				Class<?> queue = Class.forName("kaptainwutax.seedcrackerX.finder.FinderQueue", false, loader);
				Class<?> hash = Class.forName("kaptainwutax.seedcrackerX.cracker.HashedSeedData", false, loader);
				Method scan = null;
				for (Method method : queue.getMethods()) if (method.getName().equals("onChunkData") && method.getParameterCount() == 2) {
					scan = method;
					break;
				}
				if (scan == null) return null;
				return new Scx(cracker.getMethod("get"), cracker.getMethod("getDataStorage"), storage.getMethod("getTimeMachine"),
						config.getMethod("get"), config.getField("active"), queue.getMethod("get"), scan,
						storage.getField("hashedSeedData"), hash.getMethod("getHashedSeed"));
			} catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
		}
		private Object timeMachine() throws ReflectiveOperationException {
			Object instance = getCracker.invoke(null);
			return instance == null ? null : getMachine.invoke(getStorage.invoke(instance));
		}
		private Object config() throws ReflectiveOperationException { return getConfig.invoke(null); }
		private void scan(ClientLevel level, ChunkPos position) throws ReflectiveOperationException {
			onChunkData.invoke(getQueue.invoke(null), level, position);
		}
		private Long hashedSeed() throws ReflectiveOperationException {
			Object instance = getCracker.invoke(null);
			Object data = instance == null ? null : hashedSeed.get(getStorage.invoke(instance));
			Object value = data == null ? null : getHash.invoke(data);
			return value instanceof Number number ? number.longValue() : null;
		}
	}
}
