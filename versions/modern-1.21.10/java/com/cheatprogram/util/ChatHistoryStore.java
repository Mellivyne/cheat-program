package com.cheatprogram.util;

import com.cheatprogram.CheatProgram;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.storage.LevelResource;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An append-only local archive of displayed chat, isolated by actual world or server identity. */
public final class ChatHistoryStore {
	public record Entry(long timestamp, String sender, String senderId, String text, boolean direct, boolean outgoing) { }
	private record PendingOutgoing(Entry entry, long recordedAt) { }
	private record Descriptor(String identity, String label) { }
	private static final Object LOCK = new Object();
	private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("cheatprogram/chat-history").toAbsolutePath().normalize();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "Cheat Program chat history"); thread.setDaemon(true); return thread;
	});
	private static final ThreadLocal<Integer> PLAYER_MESSAGE_DEPTH = ThreadLocal.withInitial(() -> 0);
	private static final Pattern PLAIN_CHAT = Pattern.compile("^<([^<>\\r\\n]{1,80})> ([\\s\\S]+)$");
	private static final Set<Archive> DIRTY = new HashSet<>();
	private static volatile Archive active;
	private static volatile long revision;
	private static long nextRetry;

	private static final class Archive {
		final Descriptor descriptor;
		final Path path;
		final ArrayList<Entry> entries = new ArrayList<>();
		final ArrayDeque<Entry> pending = new ArrayDeque<>();
		final ArrayList<PendingOutgoing> outgoingEchoes = new ArrayList<>();
		volatile boolean loading = true;
		boolean writeQueued;
		boolean warned;
		Archive(Descriptor descriptor) {
			this.descriptor = descriptor;
			this.path = DIRECTORY.resolve(hash(descriptor.identity()) + ".jsonl");
		}
	}
	private ChatHistoryStore() { }

	/** Called on the client tick. File loading and writing run exclusively on the IO worker. */
	public static void tick() {
		ensureContext();
		long now = System.currentTimeMillis();
		if (now < nextRetry) return;
		nextRetry = now + 5_000;
		synchronized (LOCK) {
			for (Archive archive : List.copyOf(DIRTY)) queueWrite(archive);
		}
	}
	public static long revision() { return revision; }
	public static boolean loading() { Archive archive = active; return archive != null && archive.loading; }
	public static String contextLabel() { Archive archive = active; return archive == null ? "" : archive.descriptor.label(); }

	/** A name also follows UUID-backed aliases already seen in this archive. Empty input never lists everybody. */
	public static List<Entry> entries(String playerId) {
		String query = clean(playerId).toLowerCase(Locale.ROOT);
		Archive archive = active;
		if (query.isEmpty() || archive == null) return List.of();
		String uuidQuery = uuidKey(query);
		synchronized (LOCK) {
			Set<String> identities = new HashSet<>();
			if (!uuidQuery.isEmpty()) identities.add(uuidQuery);
			else for (Entry entry : archive.entries) if (entry.sender().equalsIgnoreCase(query) && !entry.senderId().isEmpty()) identities.add(uuidKey(entry.senderId()));
			ArrayList<Entry> result = new ArrayList<>();
			for (Entry entry : archive.entries) {
				if (entry.sender().equalsIgnoreCase(query) || (!entry.senderId().isEmpty() && identities.contains(uuidKey(entry.senderId())))) result.add(entry);
			}
			return List.copyOf(result);
		}
	}

	/** The chat-listener wrapper suppresses the generic display hook during a signed/player message. */
	public static void beginPlayerMessage() { PLAYER_MESSAGE_DEPTH.set(PLAYER_MESSAGE_DEPTH.get() + 1); }
	public static void endPlayerMessage() {
		int depth = PLAYER_MESSAGE_DEPTH.get() - 1;
		if (depth <= 0) PLAYER_MESSAGE_DEPTH.remove(); else PLAYER_MESSAGE_DEPTH.set(depth);
	}

	/** Invoked only after vanilla accepted and displayed the message. Filtered content stays filtered here. */
	public static void capturePlayer(PlayerChatMessage message, GameProfile profile, ChatType.Bound bound, Instant receivedAt) {
		if (message == null || profile == null || bound == null || message.isFullyFiltered()) return;
		Component visible;
		if (!message.filterMask().isEmpty()) visible = message.filterMask().applyWithFormatting(message.signedContent());
		else visible = Minecraft.getInstance().options.onlyShowSecureChat().get() ? Component.literal(message.signedContent()) : message.decoratedContent();
		if (visible == null) return;
		boolean outgoingDirect = bound.chatType().is(ChatType.MSG_COMMAND_OUTGOING);
		boolean direct = outgoingDirect || bound.chatType().is(ChatType.MSG_COMMAND_INCOMING);
		String sender = outgoingDirect ? bound.targetName().map(Component::getString).orElse("") : profile.name();
		String senderId = outgoingDirect ? resolveId(sender) : message.sender().toString();
		if (sender.isBlank()) return;
		Instant timestamp = message.timeStamp() == null ? receivedAt : message.timeStamp();
		boolean outgoing = outgoingDirect || isLocalIdentity(sender, senderId);
		append(new Entry(timestamp == null ? System.currentTimeMillis() : timestamp.toEpochMilli(), sender, senderId, visible.getString(), direct, outgoing), outgoingDirect);
	}

	/** Only recognized chat decorations or the explicit <name> form are attributed to a player. */
	public static void captureDisplayed(Component component) {
		if (component == null || PLAYER_MESSAGE_DEPTH.get() > 0) return;
		if (component.getContents() instanceof TranslatableContents translated) {
			Object[] args = translated.getArgs();
			String key = translated.getKey();
			boolean incoming = key.equals("commands.message.display.incoming");
			boolean outgoing = key.equals("commands.message.display.outgoing");
			if ((incoming || outgoing || key.equals("chat.type.text") || key.equals("chat.type.emote") || key.equals("chat.type.announcement")) && args.length >= 2) {
				String sender = argument(args[0]);
				String text = argument(args[1]);
				String senderId = resolveId(sender);
				if (!sender.isBlank() && !text.isBlank()) append(new Entry(System.currentTimeMillis(), sender, senderId, text, incoming || outgoing, outgoing || isLocalIdentity(sender, senderId)), outgoing);
				return;
			}
		}
		Matcher matcher = PLAIN_CHAT.matcher(component.getString());
		if (matcher.matches()) {
			String sender = matcher.group(1).strip(), text = matcher.group(2), senderId = resolveId(sender);
			if (!sender.isBlank()) append(new Entry(System.currentTimeMillis(), sender, senderId, text, false, isLocalIdentity(sender, senderId)), false);
		}
	}

	/** The UI calls this only after the user explicitly sends the corresponding /msg command. */
	public static void recordOutgoing(String recipient, String text) {
		String sender = clean(recipient);
		if (sender.isBlank() || text == null || text.isBlank()) return;
		Archive archive = ensureContext();
		if (archive == null) return;
		Entry entry = new Entry(System.currentTimeMillis(), sender, resolveId(sender), text, true, true);
		synchronized (LOCK) {
			archive.outgoingEchoes.add(new PendingOutgoing(entry, System.currentTimeMillis()));
			add(archive, entry);
		}
	}

	private static void append(Entry entry, boolean echoCandidate) {
		Archive archive = ensureContext();
		if (archive == null || entry.text() == null || entry.text().isBlank()) return;
		synchronized (LOCK) {
			long now = System.currentTimeMillis();
			for (Iterator<PendingOutgoing> iterator = archive.outgoingEchoes.iterator(); iterator.hasNext();) {
				PendingOutgoing pending = iterator.next();
				if (now - pending.recordedAt() > 120_000) { iterator.remove(); continue; }
				if (echoCandidate && pending.entry().sender().equalsIgnoreCase(entry.sender()) && pending.entry().text().equals(entry.text())) {
					iterator.remove(); return;
				}
			}
			add(archive, entry);
		}
	}
	private static void add(Archive archive, Entry entry) {
		archive.entries.add(entry);
		// Message timestamps can arrive out of order when vanilla's chat delay queue is enabled.
		if (archive.entries.size() > 1 && archive.entries.get(archive.entries.size() - 2).timestamp() > entry.timestamp()) archive.entries.sort(Comparator.comparingLong(Entry::timestamp));
		archive.pending.addLast(entry); DIRTY.add(archive); revision++;
		queueWrite(archive);
	}
	private static void queueWrite(Archive archive) {
		if(!com.cheatprogram.core.Config.persistenceAllowed())return;
		if (archive.pending.isEmpty() || archive.writeQueued) return;
		archive.writeQueued = true;
		IO.execute(() -> write(archive));
	}
	private static void write(Archive archive) {
		List<Entry> batch;
		synchronized (LOCK) { batch = new ArrayList<>(archive.pending); archive.pending.clear(); }
		boolean success = false;
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if(!com.cheatprogram.core.Config.persistenceAllowed()){
			synchronized(LOCK){archive.writeQueued=false;archive.pending.clear();DIRTY.remove(archive);}return;
		}
		try {
			if (!batch.isEmpty()) {
				Files.createDirectories(DIRECTORY);
				if (Files.isSymbolicLink(archive.path) || Files.isDirectory(archive.path)) throw new IllegalStateException("Invalid chat archive file");
				try (BufferedWriter writer = Files.newBufferedWriter(archive.path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
					// A leading newline isolates any partially written last record after an abrupt previous exit.
					writer.newLine();
					for (Entry entry : batch) { writer.write(JSON.toJson(entry)); writer.newLine(); }
				}
			}
			success = true;
		} catch (Exception failure) { warn(archive, failure); }
		}
		synchronized (LOCK) {
			archive.writeQueued = false;
			if (!success) for (int index = batch.size() - 1; index >= 0; index--) archive.pending.addFirst(batch.get(index));
			if (archive.pending.isEmpty()) DIRTY.remove(archive);
			else if (success) queueWrite(archive);
		}
	}
	private static Archive ensureContext() {
		Descriptor descriptor = describe();
		Archive archive = active;
		if (descriptor == null) {
			if (archive != null) synchronized (LOCK) { active = null; revision++; }
			return null;
		}
		if (archive != null && archive.descriptor.identity().equals(descriptor.identity())) return archive;
		synchronized (LOCK) {
			archive = active;
			if (archive != null && archive.descriptor.identity().equals(descriptor.identity())) return archive;
			Archive created = new Archive(descriptor); active = created; revision++;
			IO.execute(() -> load(created)); return created;
		}
	}
	private static Descriptor describe() {
		Minecraft client = Minecraft.getInstance();
		if (client.level == null || client.getConnection() == null) return null;
		IntegratedServer server = client.getSingleplayerServer();
		if (server != null) return new Descriptor("world:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize(), server.getWorldData().getLevelName());
		ServerData serverData = client.getCurrentServer();
		if (serverData != null && serverData.ip != null && !serverData.ip.isBlank()) return new Descriptor("server:" + serverData.ip.strip().toLowerCase(Locale.ROOT), serverData.name);
		String address = String.valueOf(client.getConnection().getConnection().getRemoteAddress());
		return new Descriptor("server:" + address.toLowerCase(Locale.ROOT), address);
	}
	private static void load(Archive archive) {
		ArrayList<Entry> loaded = new ArrayList<>();
		try {
			if (Files.isRegularFile(archive.path, LinkOption.NOFOLLOW_LINKS)) try (BufferedReader reader = Files.newBufferedReader(archive.path, StandardCharsets.UTF_8)) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.isBlank()) continue;
					try {
						Entry entry = JSON.fromJson(line, Entry.class);
						if (entry != null && entry.timestamp() > 0 && entry.sender() != null && entry.senderId() != null && entry.text() != null) loaded.add(entry);
					} catch (RuntimeException malformedRecord) { /* Keep the rest of an archive after a partial write. */ }
				}
			}
		} catch (Exception failure) { warn(archive, failure); }
		synchronized (LOCK) {
			loaded.addAll(archive.entries); loaded.sort(Comparator.comparingLong(Entry::timestamp));
			archive.entries.clear(); archive.entries.addAll(loaded); archive.loading = false;
			if (active == archive) revision++;
		}
	}
	private static String resolveId(String name) {
		String normalizedUuid = uuidKey(name);
		if (!normalizedUuid.isEmpty()) return normalizedUuid;
		Minecraft client = Minecraft.getInstance(); ClientPacketListener connection = client.getConnection();
		if (connection != null) for (PlayerInfo info : connection.getOnlinePlayers()) {
			GameProfile profile = info.getProfile();
			if (profile.name().equalsIgnoreCase(name)) return profile.id().toString();
		}
		if (client.level != null) for (var player : client.level.players()) if (player.getGameProfile().name().equalsIgnoreCase(name)) return player.getUUID().toString();
		return "";
	}
	private static boolean isLocalIdentity(String name, String uuid) {
		var player = Minecraft.getInstance().player;
		return player != null && (player.getGameProfile().name().equalsIgnoreCase(name) || player.getUUID().toString().equalsIgnoreCase(uuid));
	}
	private static String argument(Object value) { return value instanceof Component component ? component.getString() : value == null ? "" : value.toString(); }
	private static String clean(String text) { return text == null ? "" : text.strip(); }
	private static String uuidKey(String text) {
		if (text == null) return "";
		String value = text.strip();
		if (value.matches("(?i)[0-9a-f]{32}")) value = value.substring(0, 8) + "-" + value.substring(8, 12) + "-" + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20);
		if (!value.matches("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return "";
		return UUID.fromString(value).toString();
	}
	private static String hash(String text) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
		catch (Exception unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
	}
	private static void warn(Archive archive, Exception failure) {
		if (archive.warned) return;
		archive.warned = true;
		CheatProgram.LOGGER.warn("Local chat history IO failed ({})", failure.getClass().getSimpleName());
	}
	/** Intended for the shutdown hook: finish queued append jobs without discarding them. */
	public static void flush() {
		try {
			synchronized (LOCK) { for (Archive archive : List.copyOf(DIRTY)) queueWrite(archive); }
			IO.submit(() -> {
				List<Archive> remaining;
				synchronized (LOCK) { remaining = List.copyOf(DIRTY); }
				// A current writer may have queued a second batch behind this barrier. Drain it here too.
				for (Archive archive : remaining) write(archive);
			}).get(5, TimeUnit.SECONDS);
		} catch (Exception ignored) { /* The archive remains append-only and tolerates an interrupted final record. */ }
	}
}
