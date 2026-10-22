package com.cheatprogram.core;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.setting.Setting;
import com.cheatprogram.hud.HudLayout;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/** Persists client settings, friends and HUD layout in client.json, and module state in profiles/*.json. */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path DIR = FabricLoader.getInstance().getConfigDir().resolve("cheatprogram-open");
	private static final Path CLIENT_FILE = DIR.resolve("client.json");
	private static final Path PROFILE_DIR = DIR.resolve("profiles");

	private static boolean dirty;
	private static long lastChange;
	private static boolean loading;
	private static final Object PERSISTENCE_LOCK = new Object();
	private static volatile boolean persistenceAllowed = true;
	private static volatile boolean restartRequired;
	private static String activeIdentity = UUID.randomUUID().toString();
	/** True when no client.json existed, so defaults such as the language can be detected once. */
	public static boolean firstRun;

	private Config() {
	}

	public static Path directory() {
		return DIR;
	}

	/** Shared by every local writer; reset cannot race a queued settings or credential write. */
	public static Object persistenceLock() { return PERSISTENCE_LOCK; }
	public static boolean persistenceAllowed() { return persistenceAllowed; }
	public static boolean restartRequired() { return restartRequired; }
	static void freezeForInitialization() {
		synchronized (PERSISTENCE_LOCK) { persistenceAllowed = false; restartRequired = true; dirty = false; }
	}
	/** The current session remains inactive until a fresh process loads the first-run defaults. */
	static void resetOptionsInMemory() {
		loading = true;
		try {
			for (Setting<?> setting : ClientSettings.INSTANCE.all) setting.reset();
			ClientSettings.INSTANCE.guiKey = org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
			ClientSettings.INSTANCE.activeProfile = "default";
			Friends.set(List.of()); HudLayout.reset(); activeIdentity = UUID.randomUUID().toString();
			for (Module module : ModuleManager.all()) {
				module.setEnabled(false);
				for (Setting<?> setting : module.settings()) setting.reset();
				module.setKey(module.defaultKey); module.setHoldBind(false); module.setFavorite(false); module.setDrawn(true);
			}
		} finally { loading = false; dirty = false; }
	}

	public static Path profileDirectory() {
		return PROFILE_DIR;
	}

	public static void markDirty() {
		if (loading || !persistenceAllowed) return;
		dirty = true;
		lastChange = System.currentTimeMillis();
	}

	/** Called every client tick; writes changes once they have settled for a second. */
	public static void tick() {
		if (!persistenceAllowed) return;
		if (dirty && System.currentTimeMillis() - lastChange > 1000) {
			if (ClientSettings.INSTANCE.autoSave.on()) saveAll();
			else dirty = false;
		}
	}

	public static void loadAll() {
		loading = true;
		try {
			Files.createDirectories(PROFILE_DIR);
			if (Files.exists(CLIENT_FILE)) {
				readClient(JsonParser.parseString(Files.readString(CLIENT_FILE, StandardCharsets.UTF_8)).getAsJsonObject());
			} else {
				firstRun = true;
			}
			Path profile = profilePath(ClientSettings.INSTANCE.activeProfile);
			if (Files.exists(profile)) {
				JsonObject root = readProfileFile(profile);
				activeIdentity = identity(root);
				readProfile(root, false);
			} else {
				ModuleManager.applyFirstRunDefaults();
			}
		} catch (Exception e) {
			CheatProgram.LOGGER.error("Failed to load Cheat Program config", e);
		} finally {
			loading = false;
		}
	}

	public static void saveAll() {
		synchronized (PERSISTENCE_LOCK) {
		if (!persistenceAllowed) return;
		dirty = false;
		try {
			Files.createDirectories(PROFILE_DIR);
			write(CLIENT_FILE, writeClient());
			write(profilePath(ClientSettings.INSTANCE.activeProfile), writeProfile(activeIdentity));
		} catch (IOException e) {
			CheatProgram.LOGGER.error("Failed to save Cheat Program config", e);
		}
		}
	}

	// ---- profiles ----

	public record ProfileInfo(String name, long modified, int enabledCount) {
	}

	public static List<ProfileInfo> listProfiles() {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if (!persistenceAllowed) return List.of();
		List<ProfileInfo> list = new ArrayList<>();
		try {
			Files.createDirectories(PROFILE_DIR);
			try (Stream<Path> files = Files.list(PROFILE_DIR)) {
				files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
					String name = p.getFileName().toString();
					name = name.substring(0, name.length() - 5);
					long modified = 0;
					int count = 0;
					try {
						FileTime time = Files.getLastModifiedTime(p);
						modified = time.toMillis();
						JsonObject root = readProfileFile(p);
						if (root.has("modules")) {
							for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("modules").entrySet()) {
								JsonObject m = e.getValue().getAsJsonObject();
								if (m.has("enabled") && m.get("enabled").getAsBoolean()) count++;
							}
						}
					} catch (Exception ignored) {
					}
					list.add(new ProfileInfo(name, modified, count));
				});
			}
		} catch (IOException e) {
			CheatProgram.LOGGER.error("Failed to list profiles", e);
		}
		if (list.stream().noneMatch(p -> p.name.equals(ClientSettings.INSTANCE.activeProfile))) {
			list.add(new ProfileInfo(ClientSettings.INSTANCE.activeProfile, System.currentTimeMillis(), (int) ModuleManager.all().stream().filter(Module::isEnabled).count()));
		}
		list.sort(Comparator.comparing(ProfileInfo::name, String.CASE_INSENSITIVE_ORDER));
		return list;
			}
	}

	public static String sanitize(String name) {
		String clean = (name == null ? "" : name).strip().replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_");
		clean = clean.length() > 32 ? clean.substring(0, 32) : clean;
		clean = clean.replaceAll("^[. ]+|[. ]+$", "");
		if (clean.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) clean = "_" + clean;
		return clean;
	}

	public static boolean createProfile(String name) {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if (!persistenceAllowed) return false;
		name = sanitize(name);
		if (name.isEmpty() || Files.exists(profilePath(name))) return false;
		saveAll();
		String previous = ClientSettings.INSTANCE.activeProfile, previousId = activeIdentity;
		try {
			String id = UUID.randomUUID().toString();
			write(profilePath(name), writeProfile(id));
			ClientSettings.INSTANCE.activeProfile = name; activeIdentity = id;
			write(CLIENT_FILE, writeClient()); dirty = false;
			return true;
		} catch (IOException e) {
			ClientSettings.INSTANCE.activeProfile = previous; activeIdentity = previousId;
			CheatProgram.LOGGER.error("Failed to create profile {}", name, e); return false;
		}
			}
	}

	public static boolean saveProfileAs(String name) {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if (!persistenceAllowed) return false;
		name = sanitize(name); if (name.isEmpty()) return false;
		try {
			String id = name.equals(ClientSettings.INSTANCE.activeProfile) ? activeIdentity
					: Files.exists(profilePath(name)) ? identity(readProfileFile(profilePath(name))) : UUID.randomUUID().toString();
			write(profilePath(name), writeProfile(id));
			return true;
		} catch (IOException e) {
			CheatProgram.LOGGER.error("Failed to save profile {}", name, e);
			return false;
		}
			}
	}

	public static boolean loadProfile(String name) {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if (!persistenceAllowed) return false;
		name = sanitize(name); if (name.isEmpty()) return false;
		Path path = profilePath(name);
		if (!Files.exists(path)) return false;
		saveAll();
		try {
			JsonObject root = readProfileFile(path);
			readProfile(root, true);
			ClientSettings.INSTANCE.activeProfile = name; activeIdentity = identity(root);
			write(CLIENT_FILE, writeClient());
			return true;
		} catch (Exception e) {
			CheatProgram.LOGGER.error("Failed to load profile {}", name, e);
			return false;
		}
			}
	}

	/** Current profiles can be deleted too; a valid alternate is selected before any live state changes. */
	public static boolean deleteProfile(String name) {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if (!persistenceAllowed) return false;
		name = sanitize(name); if (name.isEmpty()) return false;
		try {
			Files.createDirectories(PROFILE_DIR);
			if (!name.equals(ClientSettings.INSTANCE.activeProfile)) return Files.deleteIfExists(profilePath(name));
			String replacement = null; JsonObject replacementData = null;
			for (ProfileInfo info : listProfiles()) {
				if (info.name().equals(name) || !Files.exists(profilePath(info.name()))) continue;
				try { replacementData = readProfileFile(profilePath(info.name())); replacement = info.name(); break; }
				catch (IOException ignored) { }
			}
			if (replacement == null) {
				replacement = name.equals("default") ? "default-2" : "default";
				replacement = uniqueProfileName(replacement);
				replacementData = defaultProfile(UUID.randomUUID().toString());
				write(profilePath(replacement), replacementData);
			}
			JsonObject client = writeClient(); client.addProperty("activeProfile", replacement);
			write(CLIENT_FILE, client);
			try { Files.deleteIfExists(profilePath(name)); }
			catch (IOException failure) { write(CLIENT_FILE, writeClient()); throw failure; }
			readProfile(replacementData, true);
			ClientSettings.INSTANCE.activeProfile = replacement; activeIdentity = identity(replacementData); dirty = false;
			return true;
		} catch (IOException e) {
			CheatProgram.LOGGER.error("Failed to delete profile {}", name, e);
			return false;
		}
			}
	}

	/** The identity belongs to the saved profile; its code changes only when portable content changes. */
	public static String shareProfile(String name) throws IOException, ProfileShareCodec.InvalidCode {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if (!persistenceAllowed) throw new IOException("Restart required after initialization");
		name = sanitize(name); if (name.isEmpty()) throw new IOException("Invalid profile name");
		Files.createDirectories(PROFILE_DIR);
		JsonObject profile;
		if (name.equals(ClientSettings.INSTANCE.activeProfile)) {
			profile = writeProfile(activeIdentity); write(profilePath(name), profile);
		} else {
			profile = readProfileFile(profilePath(name));
			String id = identity(profile); profile.addProperty("profileId", id); write(profilePath(name), profile);
		}
		return ProfileShareCodec.encode(profile, identity(profile));
			}
	}

	/** Validated codes always create an independent local profile, preserving all private local resources. */
	public static String importProfile(ProfileShareCodec.Decoded decoded, String requestedName) throws IOException {
		synchronized(com.cheatprogram.core.Config.persistenceLock()) {
		if (!persistenceAllowed) throw new IOException("Restart required after initialization");
		String base = sanitize(requestedName); if (base.isEmpty()) base = "shared-" + decoded.identity().substring(0, 8);
		String name = uniqueProfileName(base), id = UUID.randomUUID().toString();
		JsonObject imported = writeProfile(id), modules = imported.getAsJsonObject("modules");
		for (Map.Entry<String, JsonElement> entry : decoded.modules().entrySet()) {
			JsonObject target = modules.getAsJsonObject(entry.getKey()), source = entry.getValue().getAsJsonObject();
			for (String flag : List.of("enabled", "key", "hold", "favorite", "drawn")) target.add(flag, source.get(flag).deepCopy());
			JsonObject options = target.getAsJsonObject("settings");
			for (Setting<?> setting : ModuleManager.byId(entry.getKey()).settings()) {
				if (!ProfileShareCodec.portable(setting)) continue;
				JsonElement value = source.getAsJsonObject("settings").get(setting.id);
				if (value != null) options.add(setting.id, value.deepCopy());
			}
		}
		// Independent local resources stay configured, but import cannot start capturing a window or a missing particle plan.
		for (String local : List.of("appcast", "spatialaudio", "floatingsubtitles", "particleeffects", "controlpanel", "customcape"))
			if (modules.has(local)) modules.getAsJsonObject(local).addProperty("enabled", false);
		if (modules.has("hud")) modules.getAsJsonObject("hud").getAsJsonObject("settings").addProperty("worldScreen", false);
		Files.createDirectories(PROFILE_DIR);
		write(profilePath(name), imported);
		if (!loadProfile(name)) throw new IOException("Imported profile could not be applied");
		return name;
			}
	}

	private static String uniqueProfileName(String base) {
		String name = sanitize(base); if (name.isEmpty()) name = "shared";
		if (!Files.exists(profilePath(name)) && !name.equals(ClientSettings.INSTANCE.activeProfile)) return name;
		for (int suffix = 2; suffix < 100_000; suffix++) {
			String ending = "-" + suffix, candidate = name.substring(0, Math.min(name.length(), 32 - ending.length())) + ending;
			if (!Files.exists(profilePath(candidate)) && !candidate.equals(ClientSettings.INSTANCE.activeProfile)) return candidate;
		}
		return UUID.randomUUID().toString().substring(0, 32);
	}
	private static String identity(JsonObject root) {
		try { String id = root.get("profileId").getAsString(); if (UUID.fromString(id).toString().equals(id)) return id; }
		catch (Exception ignored) { }
		return UUID.randomUUID().toString();
	}
	private static JsonObject readProfileFile(Path path) throws IOException {
		if (Files.size(path) > 2 * 1024 * 1024) throw new IOException("Profile is too large");
		try {
			JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
			if (!root.has("modules") || !root.get("modules").isJsonObject()) throw new IOException("Invalid profile structure");
			// Validate types before changing any live modules; corrupt alternatives are never selected for deletion fallback.
			for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("modules").entrySet()) {
				if (!entry.getValue().isJsonObject()) throw new IOException("Invalid module structure");
				JsonObject data = entry.getValue().getAsJsonObject();
				for (String flag : List.of("enabled", "hold", "favorite", "drawn"))
					if (data.has(flag) && (!data.get(flag).isJsonPrimitive() || !data.getAsJsonPrimitive(flag).isBoolean())) throw new IOException("Invalid module flag");
				if (data.has("key")) {
					JsonElement key = data.get("key");
					if (!key.isJsonPrimitive() || !key.getAsJsonPrimitive().isNumber() || !Double.isFinite(key.getAsDouble()) || key.getAsDouble() != key.getAsInt()) throw new IOException("Invalid key");
				}
				if (!data.has("settings")) continue;
				if (!data.get("settings").isJsonObject()) throw new IOException("Invalid settings structure");
				Module module = ModuleManager.byId(entry.getKey()); if (module == null) continue;
				JsonObject settings = data.getAsJsonObject("settings");
				for (Setting<?> setting : module.settings()) {
					JsonElement value = settings.get(setting.id); if (value == null) continue;
					if (setting instanceof com.cheatprogram.core.setting.RangeSetting) {
						if (!value.isJsonArray() || value.getAsJsonArray().size() != 2) throw new IOException("Invalid range");
						for (JsonElement number : value.getAsJsonArray()) if (!number.isJsonPrimitive() || !number.getAsJsonPrimitive().isNumber() || !Double.isFinite(number.getAsDouble())) throw new IOException("Invalid number");
					} else {
						if (!value.isJsonPrimitive()) throw new IOException("Invalid setting type");
						if (setting instanceof com.cheatprogram.core.setting.BoolSetting && !value.getAsJsonPrimitive().isBoolean()) throw new IOException("Invalid boolean");
						if (setting instanceof com.cheatprogram.core.setting.NumberSetting && (!value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble()))) throw new IOException("Invalid number");
						if ((setting instanceof com.cheatprogram.core.setting.ModeSetting || setting instanceof com.cheatprogram.core.setting.TextSetting) && !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid string");
					}
				}
			}
			return root;
		} catch (RuntimeException invalid) { throw new IOException("Invalid profile structure", invalid); }
	}

	private static Path profilePath(String name) {
		return PROFILE_DIR.resolve(sanitize(name) + ".json");
	}

	// ---- serialization ----

	private static JsonObject writeClient() {
		JsonObject root = new JsonObject();
		JsonObject settings = new JsonObject();
		for (Setting<?> s : ClientSettings.INSTANCE.all) settings.add(s.id, s.save());
		root.add("settings", settings);
		root.addProperty("guiKey", ClientSettings.INSTANCE.guiKey);
		root.addProperty("activeProfile", ClientSettings.INSTANCE.activeProfile);
		JsonArray friends = new JsonArray();
		for (String f : Friends.all()) friends.add(f);
		root.add("friends", friends);
		root.add("hud", HudLayout.save());
		return root;
	}

	private static void readClient(JsonObject root) {
		if (root.has("settings")) {
			JsonObject settings = root.getAsJsonObject("settings");
			for (Setting<?> s : ClientSettings.INSTANCE.all) s.load(settings.get(s.id));
		}
		if (root.has("guiKey")) ClientSettings.INSTANCE.guiKey = root.get("guiKey").getAsInt();
		if (root.has("activeProfile")) ClientSettings.INSTANCE.activeProfile = sanitize(root.get("activeProfile").getAsString());
		if (ClientSettings.INSTANCE.activeProfile.isEmpty()) ClientSettings.INSTANCE.activeProfile = "default";
		if (root.has("friends")) {
			List<String> names = new ArrayList<>();
			for (JsonElement e : root.getAsJsonArray("friends")) names.add(e.getAsString());
			Friends.set(names);
		}
		if (root.has("hud")) HudLayout.load(root.getAsJsonObject("hud"));
	}

	private static JsonObject writeProfile(String identity) {
		JsonObject root = new JsonObject();
		root.addProperty("profileId", identity);
		JsonObject modules = new JsonObject();
		for (Module m : ModuleManager.all()) {
			JsonObject o = new JsonObject();
			o.addProperty("enabled", m.isEnabled() && !m.isOneShot());
			o.addProperty("key", m.getKey());
			o.addProperty("hold", m.isHoldBind());
			o.addProperty("favorite", m.isFavorite());
			o.addProperty("drawn", m.isDrawn());
			JsonObject settings = new JsonObject();
			for (Setting<?> s : m.settings()) settings.add(s.id, s.save());
			o.add("settings", settings);
			modules.add(m.id, o);
		}
		root.add("modules", modules);
		return root;
	}

	private static JsonObject defaultProfile(String id) {
		JsonObject root = writeProfile(id), modules = root.getAsJsonObject("modules");
		for (Module module : ModuleManager.all()) {
			JsonObject data = modules.getAsJsonObject(module.id);
			data.addProperty("enabled", module.id.equals("hud") || module.id.equals("sprint"));
			data.addProperty("key", module.defaultKey); data.addProperty("hold", false); data.addProperty("favorite", false); data.addProperty("drawn", true);
			JsonObject settings = new JsonObject();
			for (Setting<?> setting : module.settings()) settings.add(setting.id, GSON.toJsonTree(setting.defaultValue));
			data.add("settings", settings);
		}
		return root;
	}

	/** @param live true when switching profiles in game, so enable/disable hooks must run */
	private static void readProfile(JsonObject root, boolean live) {
		boolean wasLoading = loading;
		loading = true;
		try {
			JsonObject modules = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
			for (Module m : ModuleManager.all()) {
				JsonObject o = modules.has(m.id) ? modules.getAsJsonObject(m.id) : null;
				if (o==null && m.id.equals("trainingrobots") && modules.has("robotplayers")) {
					JsonObject legacy=modules.getAsJsonObject("robotplayers");
					if(legacy.has("settings") && legacy.get("settings").isJsonObject()) {
						JsonObject old=legacy.getAsJsonObject("settings"),migrated=new JsonObject();
						for(String key:List.of("trainingTarget","trainingKit","trainingRadius","trainingSpeed","attackInterval","attackCooldown","trainingShield","shieldUptime"))
							if(old.has(key))migrated.add(key,old.get(key).deepCopy());
						if(old.has("behavior") && List.of("bot_duel","bot_strafe","bot_guard").contains(old.get("behavior").getAsString()))migrated.add("behavior",old.get("behavior").deepCopy());
						o=new JsonObject();o.add("settings",migrated);o.addProperty("enabled",false);
					}
				}
				if (o != null && o.has("settings")) {
					JsonObject settings = o.getAsJsonObject("settings");
                    if(m.id.equals("spatialaudio"))settings=com.cheatprogram.audio.AcousticPresets.migrate(settings);
                    if(m.id.equals("floatingsubtitles"))settings=com.cheatprogram.media.SubtitlePresets.migrate(settings);
					if(m.id.equals("trainingrobots")&&!settings.has("targetBehaviorVersion")
							&&settings.has("trainingTarget")&&settings.get("trainingTarget").getAsString().equals("bot_target_robots")
							&&settings.has("count")&&settings.get("count").getAsString().strip().equals("1")) {
						settings=settings.deepCopy();settings.addProperty("trainingTarget","bot_target_all");
					}
					if(m.id.equals("robotplayers") && settings.has("behavior") && List.of("bot_duel","bot_strafe","bot_guard").contains(settings.get("behavior").getAsString())) {
						settings=settings.deepCopy();settings.addProperty("behavior","bot_stand");
					}
					if ((m.id.equals("autoarmor") || m.id.equals("invcleaner"))
							&& !settings.has("operationMode") && settings.has("openInv")) {
						settings = settings.deepCopy();
						settings.addProperty("operationMode", settings.get("openInv").getAsBoolean() ? "follow" : "silent");
					}
					if (m.id.equals("invcleaner") && !settings.has("cleanMode")) {
						settings = settings.deepCopy();
						settings.addProperty("cleanMode", "combat_keep");
					}
					if (m.id.equals("killaura") && !settings.has("rotation") && settings.has("rotate")) {
						settings=settings.deepCopy();
						settings.addProperty("rotation",settings.get("rotate").getAsBoolean()?"follow":"silent");
					}
					if (m.id.equals("killaura") && !settings.has("antiBotNoKnockback") && settings.has("antiBotNoDamage")) {
						settings = settings.deepCopy();
						settings.add("antiBotNoKnockback", settings.get("antiBotNoDamage").deepCopy());
					}
					for (Setting<?> s : m.settings()) {
						if (settings.has(s.id)) s.load(settings.get(s.id));
					}
				}
				boolean enabled = o != null && o.has("enabled") && o.get("enabled").getAsBoolean() && !m.isOneShot();
				if ((m.id.equals("flight") || m.id.equals("airwalk")) && modules.has("flatflight")) {
					JsonObject flat = modules.getAsJsonObject("flatflight");
					if (flat.has("enabled") && flat.get("enabled").getAsBoolean()) enabled = false;
				}
				if (o != null) {
					m.setKey(o.has("key") ? o.get("key").getAsInt() : m.defaultKey);
					m.setHoldBind(o.has("hold") && o.get("hold").getAsBoolean());
					m.setFavorite(o.has("favorite") && o.get("favorite").getAsBoolean());
					m.setDrawn(!o.has("drawn") || o.get("drawn").getAsBoolean());
				}
				if (live) m.setEnabled(enabled);
				else m.setEnabledSilently(enabled);
			}
		} finally {
			loading = wasLoading;
		}
	}

	private static void write(Path path, JsonObject root) throws IOException {
		synchronized (PERSISTENCE_LOCK) {
		if (!persistenceAllowed) throw new IOException("Restart required after initialization");
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
		try {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		}
		}
	}
}
