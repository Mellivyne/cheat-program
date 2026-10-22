package com.cheatprogram.ai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Connection preferences have their own file. Credentials never enter a profile or a log. */
public final class AiConfig {
	private static final java.util.Set<AiConfig> INSTANCES=java.util.concurrent.ConcurrentHashMap.newKeySet();
	private static final ExecutorService STORAGE = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Cheat Program AI preferences");
		thread.setDaemon(true);
		return thread;
	});
	private volatile String endpoint = "https://api.openai.com/v1";
	private volatile String model = "gpt-4.1-mini";
	private volatile String apiKey = "";
	private final Path preferencesFile;
	private final CredentialStore credentials;
	private final String credentialSlot;
	static {
		java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try { STORAGE.submit(() -> { }).get(1500, TimeUnit.MILLISECONDS); } catch (Exception ignored) { }
		}, "Cheat Program AI preference flush"));
	}

	AiConfig() {
		this(FabricLoader.getInstance().getConfigDir().resolve("cheatprogram-open").resolve("ai.json"));
	}
	AiConfig(Path preferencesFile) {
		INSTANCES.add(this);
		this.preferencesFile = preferencesFile;
		credentials = CredentialStore.open(preferencesFile.toAbsolutePath().getParent().resolve("credentials.dpapi"));
		credentialSlot = "connection." + preferencesFile.getFileName();
		try {
			Path file = file();
			if (Files.isRegularFile(file) && Files.size(file) <= 8192) {
				JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				if (json.has("endpoint")) endpoint = clean(json.get("endpoint").getAsString(), 2048);
				if (json.has("model")) model = clean(json.get("model").getAsString(), 128);
			}
		} catch (Exception ignored) { }
		apiKey = credentials.read(credentialSlot, endpoint);
	}

	public String endpoint() { return endpoint; }
	public String model() { return model; }
	public String apiKey() { return apiKey; }
	public boolean hasApiKey() { return !apiKey.isBlank(); }
	public synchronized void endpoint(String value) {
		String next = clean(value, 2048);
		if (endpoint.equals(next)) return;
		if (!CredentialStore.sameEndpoint(endpoint, next)) {
			// A key entered for one provider is never silently sent to a newly selected provider.
			apiKey = ""; credentials.delete(credentialSlot);
		}
		endpoint = next; save();
	}
	public void model(String value) { model = clean(value, 128); }
	public synchronized void apiKey(String value) {
		apiKey = clean(value, 4096); credentials.put(credentialSlot, endpoint, apiKey); save();
	}
	public synchronized void deleteApiKey() { apiKey = ""; credentials.delete(credentialSlot); save(); }
	public String credentialStatusKey() { return credentials.statusKey(credentialSlot); }

	public void preset(String name) {
		if ("deepseek".equalsIgnoreCase(name)) {
			endpoint("https://api.deepseek.com");
			model = "deepseek-flash";
		} else if ("openai".equalsIgnoreCase(name)) {
			endpoint("https://api.openai.com/v1");
			model = "gpt-4.1-mini";
		}
	}

	/** Preferences remain readable JSON; credentials are independently encrypted and never added here. */
	public synchronized void save() {
		if(!com.cheatprogram.core.Config.persistenceAllowed())return;
		if (!apiKey.isBlank()) credentials.put(credentialSlot, endpoint, apiKey);
		String address = endpoint, selectedModel = model;
		STORAGE.execute(() -> {
			synchronized(com.cheatprogram.core.Config.persistenceLock()) {
			if(!com.cheatprogram.core.Config.persistenceAllowed())return;
			try {
				JsonObject json = new JsonObject();
				json.addProperty("endpoint", address);
				json.addProperty("model", selectedModel);
				Path file = file();
				Files.createDirectories(file.getParent());
				Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
				Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			} catch (Exception ignored) { }
			}
		});
	}
	public static void clearMemoryForInitialization(){
		for(AiConfig config:INSTANCES)synchronized(config){config.apiKey="";config.endpoint="https://api.openai.com/v1";config.model="gpt-4.1-mini";}
	}

	URI completionUri() {
		String address = endpoint.strip();
		while (address.endsWith("/")) address = address.substring(0, address.length() - 1);
		URI uri = URI.create(address);
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null
				|| uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null)
			throw new IllegalArgumentException("invalid endpoint");
		if (address.endsWith("/responses")) return uri;
		if ("api.openai.com".equalsIgnoreCase(uri.getHost())) {
			if (address.endsWith("/chat/completions")) address = address.substring(0, address.length() - "/chat/completions".length());
			if (URI.create(address).getPath().isEmpty()) address += "/v1";
			return URI.create(address + "/responses");
		}
		return address.endsWith("/chat/completions") ? uri : URI.create(address + "/chat/completions");
	}

	private Path file() {
		return preferencesFile;
	}

	private static String clean(String value, int maximum) {
		String text = value == null ? "" : value.replaceAll("[\\x00-\\x1f\\x7f]", "").strip();
		return text.length() > maximum ? text.substring(0, maximum) : text;
	}
}
