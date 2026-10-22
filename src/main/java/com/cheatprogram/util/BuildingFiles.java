package com.cheatprogram.util;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local, versioned buildings. Callers capture server data or synchronized client data on its owning thread. */
public final class BuildingFiles {
	public static final int MAX_BLOCKS = 4_000_000;
	public static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Cheat Program buildings"); thread.setDaemon(true); return thread;
	});
	private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("cheatprogram/buildings").toAbsolutePath().normalize();
	private BuildingFiles() { }
	public record Blueprint(String name, int width, int height, int depth, List<CompoundTag> palette, int[] blocks,
							Map<Integer, CompoundTag> blockEntities) {
		public int count() { return blocks.length; }
		public String dimensions() { return width + " × " + height + " × " + depth; }
	}
	public static String cleanName(String text) {
		String name = text == null ? "" : text.strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
		while (name.endsWith(".") || name.endsWith(" ")) name = name.substring(0, name.length() - 1);
		if (name.length() > 100) name = name.substring(0, 100);
		while (name.endsWith(".") || name.endsWith(" ")) name = name.substring(0, name.length() - 1);
		if (name.isBlank()) name = "building";
		if (name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) name = "_" + name;
		return name;
	}
	public static List<String> list() throws IOException {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if(!com.cheatprogram.core.Config.persistenceAllowed())throw new IOException("Restart required after initialization");
		ensureDirectory(DIRECTORY);
		try (var stream = Files.list(DIRECTORY)) {
			return stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).map(path -> path.getFileName().toString())
					.filter(name -> name.endsWith(".cpbuilding")).map(name -> name.substring(0, name.length() - 11))
					.filter(name -> name.equals(cleanName(name)))
					.sorted(String.CASE_INSENSITIVE_ORDER).toList();
		}
			}
	}
	/** Every save publishes a new file; a same-name building is retained as an earlier copy. */
	public static Blueprint save(Blueprint building) throws IOException {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if(!com.cheatprogram.core.Config.persistenceAllowed())throw new IOException("Restart required after initialization");
		ensureDirectory(DIRECTORY);
		CompoundTag root = encode(building);
		Path temporary = Files.createTempFile(DIRECTORY, ".building-", ".pending");
		try {
			String requested = cleanName(building.name()), base = requested;
			int nextCopy = 1;
			var numbered = Pattern.compile("^(.*) \\(([2-9]|[1-9][0-9]+)\\)$").matcher(requested);
			if (numbered.matches() && Files.exists(namedFile(requested), LinkOption.NOFOLLOW_LINKS)) {
				try {
					int previous = Integer.parseInt(numbered.group(2));
					if (previous < Integer.MAX_VALUE) { base = cleanName(numbered.group(1)); nextCopy = previous + 1; }
				} catch (NumberFormatException ignored) { }
			}
			for (int copy = nextCopy; copy > 0; copy++) {
				String suffix = copy == 1 ? "" : " (" + copy + ")";
				String name = base.substring(0, Math.min(base.length(), 100 - suffix.length())) + suffix;
				Path destination = namedFile(name);
				if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) continue;
				root.putString("name", name); NbtIo.writeCompressed(root, temporary);
				try {
					Files.move(temporary, destination);
					return new Blueprint(name, building.width(), building.height(), building.depth(), building.palette(), building.blocks(), building.blockEntities());
				} catch (FileAlreadyExistsException collision) { /* Pick the next unused suffix. */ }
			}
			throw new IOException("No unused building name remains");
		} finally { Files.deleteIfExists(temporary); }
			}
	}
	public static Path directory() { return DIRECTORY; }
	/** The caller explicitly chooses the external file; exporting never removes its library copy. */
	public static Path export(Blueprint building, Path chosen) throws IOException {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if(!com.cheatprogram.core.Config.persistenceAllowed())throw new IOException("Restart required after initialization");
		if (building == null || chosen == null) throw new IOException("No building or destination selected");
		Path destination = chosen.toAbsolutePath().normalize();
		String filename = destination.getFileName().toString();
		if (!filename.toLowerCase(Locale.ROOT).endsWith(".cpbuilding")) destination = destination.resolveSibling(filename + ".cpbuilding");
		Path parent = destination.getParent();
		if (parent == null || Files.isSymbolicLink(destination) || Files.isDirectory(destination)) throw new IOException("Invalid export destination");
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".building-export-", ".pending");
		try {
			NbtIo.writeCompressed(encode(building), temporary);
			try { Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
			catch (AtomicMoveNotSupportedException unavailable) { Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING); }
			return destination;
		} finally { Files.deleteIfExists(temporary); }
			}
	}
	private static CompoundTag encode(Blueprint building) {
		CompoundTag root = new CompoundTag(); root.putInt("version", 1);
		root.putString("name", building.name());
		root.putIntArray("size", new int[]{building.width(), building.height(), building.depth()});
		ListTag palette = new ListTag(); for (CompoundTag state : building.palette()) palette.add(state.copy());
		root.put("palette", palette); root.putIntArray("blocks", building.blocks());
		ListTag entities = new ListTag();
		for (var entry : building.blockEntities().entrySet()) {
			CompoundTag entity = new CompoundTag(); entity.putInt("index", entry.getKey()); entity.put("data", entry.getValue().copy()); entities.add(entity);
		}
		root.put("blockEntities", entities);
		return root;
	}
	/** Reversible deletion moves exactly one regular building into the local recovery directory. */
	public static Path delete(String name) throws IOException {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if(!com.cheatprogram.core.Config.persistenceAllowed())throw new IOException("Restart required after initialization");
		ensureDirectory(DIRECTORY);
		Path source = namedFile(name);
		if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid building file");
		Path trash = DIRECTORY.resolve(".trash"); ensureDirectory(trash);
		Path destination = trash.resolve(System.currentTimeMillis() + "-" + UUID.randomUUID() + ".cpb").normalize();
		if (!destination.getParent().equals(trash)) throw new IOException("Invalid building recovery path");
		Files.move(source, destination);
		return destination;
			}
	}
	public static Blueprint load(String name) throws IOException {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if(!com.cheatprogram.core.Config.persistenceAllowed())throw new IOException("Restart required after initialization");
		ensureDirectory(DIRECTORY);
		Path file = namedFile(name);
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 128L * 1024 * 1024) throw new IOException("Invalid building file");
		CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(192L * 1024 * 1024));
		int[] size = root.getIntArray("size");
		if (root.getInt("version") != 1 || size.length != 3 || size[0] <= 0 || size[1] <= 0 || size[2] <= 0
				|| (long) size[0] * size[1] * size[2] > MAX_BLOCKS) throw new IOException("Invalid building dimensions");
		int[] blocks = root.getIntArray("blocks");
		if (blocks.length != (long) size[0] * size[1] * size[2]) throw new IOException("Invalid block count");
		ListTag stored = root.getList("palette", Tag.TAG_COMPOUND); List<CompoundTag> palette = new ArrayList<>();
		if (stored.isEmpty() || stored.size() > MAX_BLOCKS) throw new IOException("Invalid palette");
		for (int index = 0; index < stored.size(); index++) palette.add(stored.getCompound(index).copy());
		for (int value : blocks) if (value < 0 || value >= palette.size()) throw new IOException("Invalid block palette index");
		Map<Integer, CompoundTag> entities = new TreeMap<>(); ListTag data = root.getList("blockEntities", Tag.TAG_COMPOUND);
		for (int index = 0; index < data.size(); index++) {
			CompoundTag entry = data.getCompound(index); int location = entry.getInt("index");
			if (location < 0 || location >= blocks.length || !entry.contains("data", Tag.TAG_COMPOUND)) throw new IOException("Invalid block entity");
			entities.put(location, entry.getCompound("data").copy());
		}
		return new Blueprint(cleanName(name), size[0], size[1], size[2], List.copyOf(palette), blocks, Map.copyOf(entities));
			}
	}
	private static Path namedFile(String name) throws IOException {
		if (name == null || !name.equals(cleanName(name))) throw new IOException("Invalid building name");
		Path file = DIRECTORY.resolve(name + ".cpbuilding").normalize();
		if (!file.getParent().equals(DIRECTORY)) throw new IOException("Invalid building path");
		return file;
	}
	private static void ensureDirectory(Path directory) throws IOException {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if(!com.cheatprogram.core.Config.persistenceAllowed())throw new IOException("Restart required after initialization");
		for (Path part = directory; part != null; part = part.getParent())
			if (Files.isSymbolicLink(part)) throw new IOException("Building storage cannot use symbolic links");
		Files.createDirectories(directory);
		for (Path part = directory; part != null; part = part.getParent())
			if (Files.isSymbolicLink(part)) throw new IOException("Building storage cannot use symbolic links");
		if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Building storage is not a directory");
			}
	}
}
