package com.cheatprogram.util;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.visual.Personal;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Explicit local report browser and opt-in, white-list-only anonymous report preparation. */
public final class PersonalReports {
	public static final int MAX_NOTE_CHARACTERS = 16_384;
	private static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024, MAX_PAYLOAD_BYTES = 128 * 1024, MAX_STACK_FRAMES = 160;
	private static final Minecraft MC = Minecraft.getInstance();
	private static final Path SOURCE = FabricLoader.getInstance().getGameDir().resolve("crash-reports").toAbsolutePath().normalize();
	private static final Path EXPORT = Config.directory().resolve("personal-reports").toAbsolutePath().normalize();
	private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final ExecutorService IO = Executors.newFixedThreadPool(2, task -> {
		Thread thread = new Thread(task, "Cheat Program personal reports"); thread.setDaemon(true); return thread;
	});
	private static final AtomicBoolean SENDING = new AtomicBoolean();
	private static final DateTimeFormatter EXPORT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);
	private static final Pattern EXCEPTION = Pattern.compile("^(?:(?:Caused by|Suppressed):\\s*)?([A-Za-z_$][A-Za-z0-9_.$]*(?:Exception|Error|Throwable))(?::.*)?$");
	private static final Pattern FRAME = Pattern.compile("^at\\s+([A-Za-z0-9_.$/]+)\\(([^)]*)\\).*$");
	private static final Pattern JAVA_SOURCE = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*\\.java(?::[0-9]{1,9})?");
	private static final Pattern MC_VERSION = Pattern.compile("(?m)^\\s*Minecraft Version:\\s*([A-Za-z0-9_.+\\-]{1,48})\\s*$");
	private static final Pattern JAVA_VERSION = Pattern.compile("(?m)^\\s*Java Version:\\s*([0-9][A-Za-z0-9_.+\\-]{0,47})(?:[, ].*)?$");
	private static final Set<String> ALLOWED_DESCRIPTIONS = Set.of("Initializing game", "Rendering screen", "Rendering entity in world", "Rendering Block Entity", "Unexpected error", "Ticking entity", "Ticking block entity", "Ticking player", "Exception in server tick loop", "Exception ticking world", "Manually triggered debug crash", "Saving entity NBT", "Loading NBT data", "Getting block state", "Updating screen events");
	private static final List<String> TECHNICAL_PREFIXES = List.of("java.", "javax.", "jdk.", "sun.", "com.mojang.", "net.minecraft.", "net.fabricmc.", "org.spongepowered.", "org.lwjgl.", "com.cheatprogram.", "com.llamalad7.");
	public record ReportFile(String name, long modified, long bytes) { }
	private record Environment(String minecraft, String java, String os, List<ModVersion> mods) { }
	private record ModVersion(String id, String version) { }
	private record Summary(String description, List<String> exceptions, List<String> stack) { }
	public static final class Snapshot {
		private final ReportFile file;
		private final String original;
		private final List<String> originalLines;
		private final boolean truncated;
		private final Environment environment;
		private final Redactor redactor;
		private final Summary summary;
		private Snapshot(ReportFile file, String original, boolean truncated, Environment environment, Redactor redactor, Summary summary) {
			this.file = file; this.original = original; originalLines = List.copyOf(original.lines().toList()); this.truncated = truncated;
			this.environment = environment; this.redactor = redactor; this.summary = summary;
		}
		public ReportFile file() { return file; }
		public String original() { return original; }
		public List<String> originalLines() { return originalLines; }
		public boolean truncated() { return truncated; }
	}
	/** Only this generated object can reach save/submit; raw report text has no upload path. */
	public static final class Prepared {
		private final String json, text;
		private final byte[] bytes;
		private Prepared(String json, String text) {
			this.json = json; this.text = text; bytes = json.getBytes(StandardCharsets.UTF_8);
			if (bytes.length > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Report is too large");
		}
		public String json() { return json; }
		public String text() { return text; }
		public int bytes() { return bytes.length; }
	}
	public record Result(String statusKey, int httpStatus, Path savedFile) { }
	private PersonalReports() { }
	public static Path sourceDirectory() { return SOURCE; }
	public static Path exportDirectory() { return EXPORT; }
	public static boolean sending() { return SENDING.get(); }
	public static CompletableFuture<List<ReportFile>> scan() {
		return CompletableFuture.supplyAsync(() -> {
			if (!Files.isDirectory(SOURCE)) return List.of();
			try (Stream<Path> paths = Files.list(SOURCE)) {
				List<ReportFile> result = new ArrayList<>();
				paths.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt") && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
						.forEach(path -> { try { result.add(new ReportFile(path.getFileName().toString(), Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis(), Files.size(path))); } catch (IOException ignored) { } });
				result.sort(Comparator.comparingLong(ReportFile::modified).reversed());
				return List.copyOf(result.subList(0, Math.min(200, result.size())));
			} catch (IOException failure) { throw new IllegalStateException("Could not list local crash reports"); }
		}, IO);
	}
	/** Call on the client thread to capture names without touching live player data from an IO worker. */
	public static CompletableFuture<Snapshot> load(ReportFile file) {
		Environment environment = environment(); Redactor redactor = identities();
		return CompletableFuture.supplyAsync(() -> {
			try {
				Path path = sourcePath(file); byte[] bytes;
				try (InputStream stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(MAX_SOURCE_BYTES + 1); }
				boolean truncated = bytes.length > MAX_SOURCE_BYTES;
				String original = new String(bytes, 0, Math.min(MAX_SOURCE_BYTES, bytes.length), StandardCharsets.UTF_8);
				Matcher mcVersion = MC_VERSION.matcher(original), javaVersion = JAVA_VERSION.matcher(original);
				Environment crashEnvironment = new Environment(mcVersion.find() ? mcVersion.group(1) : environment.minecraft,
						javaVersion.find() ? javaVersion.group(1) : environment.java, environment.os, environment.mods);
				return new Snapshot(file, original, truncated, crashEnvironment, redactor, summary(original, redactor));
			} catch (IOException | IllegalArgumentException failure) { throw new IllegalStateException("Could not read local crash report"); }
		}, IO);
	}
	public static Snapshot empty() { return new Snapshot(null, "", false, environment(), identities(), new Summary("User feedback", List.of(), List.of())); }
	private static Path sourcePath(ReportFile file) throws IOException {
		if (file == null || file.name == null || file.name.contains("/") || file.name.contains("\\") || !file.name.toLowerCase(Locale.ROOT).endsWith(".txt")) throw new IOException("Invalid report file");
		Path path = SOURCE.resolve(file.name).normalize();
		if (!path.getParent().equals(SOURCE) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid report file");
		return path;
	}
	private static Environment environment() {
		var loader = FabricLoader.getInstance();
		List<ModVersion> mods = new ArrayList<>();
		loader.getAllMods().stream().sorted(Comparator.comparing(mod -> mod.getMetadata().getId())).limit(128).forEach(mod -> {
			String id = mod.getMetadata().getId(), version = mod.getMetadata().getVersion().getFriendlyString();
			if (id.matches("[a-z0-9_\\-]{1,64}") && version.matches("[A-Za-z0-9_.+~!\\-]{1,96}")) mods.add(new ModVersion(id, version));
		});
		String minecraft = loader.getModContainer("minecraft").map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
		return new Environment(shortTechnical(minecraft), shortTechnical(System.getProperty("java.version", "unknown")),
				shortTechnical(System.getProperty("os.name", "unknown") + " " + System.getProperty("os.arch", "unknown")), List.copyOf(mods));
	}
	private static String shortTechnical(String value) {
		String text = value.replaceAll("[^A-Za-z0-9_.+() \\-]", ""); return text.substring(0, Math.min(96, text.length()));
	}
	private static Redactor identities() {
		Set<String> values = new LinkedHashSet<>();
		Personal personal=ModuleManager.get(Personal.class);
		if(personal!=null&&!personal.configuredDisplayName().isBlank())values.add(personal.configuredDisplayName());
		String osUser = System.getProperty("user.name", ""); if (!osUser.isBlank()) values.add(osUser);
		if (MC.getUser() != null) { values.add(MC.getUser().getName()); values.add(MC.getUser().getProfileId().toString()); }
		if (MC.player != null) { values.add(MC.player.getName().getString()); values.add(MC.player.getDisplayName().getString()); values.add(MC.player.getUUID().toString()); }
		if (MC.getConnection() != null) for (var info : MC.getConnection().getOnlinePlayers()) { values.add(info.getProfile().name()); values.add(info.getProfile().id().toString()); }
		if (MC.getCurrentServer() != null) { values.add(MC.getCurrentServer().ip); values.add(MC.getCurrentServer().name); }
		values.removeIf(value -> value == null || value.isBlank());
		return new Redactor(List.copyOf(values));
	}
	private static Summary summary(String original, Redactor redactor) {
		String description = "Game crash"; Set<String> exceptions = new LinkedHashSet<>(); List<String> stack = new ArrayList<>();
		for (String line : original.split("\\R", -1)) {
			String clean = line.strip();
			if (clean.startsWith("Description: ") && ALLOWED_DESCRIPTIONS.contains(clean.substring(13))) description = clean.substring(13);
			Matcher type = EXCEPTION.matcher(clean);
			if (type.matches() && exceptions.size() < 12) exceptions.add(redactor.identifiers(technicalClass(type.group(1))));
			if (stack.size() >= MAX_STACK_FRAMES) continue;
			Matcher frame = FRAME.matcher(clean);
			if (frame.matches()) {
				String method = frame.group(1).substring(frame.group(1).lastIndexOf('/') + 1), source = frame.group(2);
				boolean permitted = TECHNICAL_PREFIXES.stream().anyMatch(method::startsWith);
				if (!permitted) { stack.add("at [external code]"); continue; }
				if (!JAVA_SOURCE.matcher(source).matches() && !source.equals("Native Method") && !source.equals("Unknown Source")) source = "source omitted";
				stack.add(redactor.identifiers("at " + method + "(" + source + ")"));
			}
		}
		return new Summary(description, List.copyOf(exceptions), List.copyOf(stack));
	}
	private static String technicalClass(String name) {
		return TECHNICAL_PREFIXES.stream().anyMatch(name::startsWith) ? name : name.substring(name.lastIndexOf('.') + 1);
	}
	public static Prepared prepare(Snapshot snapshot, String note) {
		if (snapshot == null) snapshot = empty();
		String safeNote = snapshot.redactor.text(note == null ? "" : note.substring(0, Math.min(MAX_NOTE_CHARACTERS, note.length())));
		JsonObject object = new JsonObject(); object.addProperty("schema", "cheatprogram-anonymous-report-v1");
		object.addProperty("kind", snapshot.file == null ? "feedback" : "crash");
		object.addProperty("preparedAt", Instant.now().toString());
		JsonObject env = new JsonObject(); env.addProperty("minecraft", snapshot.redactor.identifiers(snapshot.environment.minecraft));
		env.addProperty("java", snapshot.redactor.identifiers(snapshot.environment.java)); env.addProperty("os", snapshot.redactor.identifiers(snapshot.environment.os));
		JsonArray mods = new JsonArray();
		for (ModVersion mod : snapshot.environment.mods) { JsonObject entry = new JsonObject(); entry.addProperty("id", snapshot.redactor.identifiers(mod.id)); entry.addProperty("version", snapshot.redactor.identifiers(mod.version)); mods.add(entry); }
		env.add("currentlyLoadedMods", mods); object.add("environment", env);
		JsonObject crash = new JsonObject(); crash.addProperty("category", snapshot.summary.description);
		crash.add("exceptionTypes", JSON.toJsonTree(snapshot.summary.exceptions)); crash.add("stack", JSON.toJsonTree(snapshot.summary.stack));
		crash.addProperty("sourceTruncated", snapshot.truncated); object.add("diagnostic", crash);
		object.addProperty("userNote", safeNote);
		object.addProperty("privacy", "White-list fields only. Full logs, configuration, chat, account IDs and original exception messages are excluded. The receiver can observe the connection IP.");
		String json = JSON.toJson(object);
		StringBuilder text = new StringBuilder("Cheat Program anonymous report\n\nMinecraft: ").append(env.get("minecraft").getAsString())
				.append("\nJava: ").append(env.get("java").getAsString()).append("\nOS: ").append(env.get("os").getAsString())
				.append("\nCategory: ").append(snapshot.summary.description).append("\n\nException types:\n");
		for (String type : snapshot.summary.exceptions) text.append(type).append('\n');
		text.append("\nSanitized stack:\n"); for (String frame : snapshot.summary.stack) text.append(frame).append('\n');
		text.append("\nCurrently loaded mods:\n"); for (var entry : mods) { var mod = entry.getAsJsonObject(); text.append(mod.get("id").getAsString()).append(' ').append(mod.get("version").getAsString()).append('\n'); }
		text.append("\nUser note (sanitized):\n").append(safeNote).append("\n\nThe receiver can still observe the connection IP.\n");
		return new Prepared(json, text.toString());
	}
	public static CompletableFuture<Result> save(Prepared report, boolean json) {
		return CompletableFuture.supplyAsync(() -> {
			synchronized(com.cheatprogram.core.Config.persistenceLock()) {
			if(!com.cheatprogram.core.Config.persistenceAllowed())return new Result("ui.reports.saveFailed",0,null);
			Path temporary = null;
			try {
				Files.createDirectories(EXPORT);
				if (Files.isSymbolicLink(EXPORT)) throw new IOException("Invalid export directory");
				temporary = Files.createTempFile(EXPORT, ".report-", ".tmp");
				Files.writeString(temporary, json ? report.json : report.text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
				String suffix = Long.toUnsignedString(java.util.concurrent.ThreadLocalRandom.current().nextLong(), 36);
				Path output = EXPORT.resolve("anonymous-" + EXPORT_TIME.format(Instant.now()) + "-" + suffix + (json ? ".json" : ".txt"));
				try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE); }
				catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary, output); }
				return new Result("ui.reports.saved", 0, output);
			} catch (IOException | RuntimeException failure) { return new Result("ui.reports.saveFailed", 0, null); }
			finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
			}
		}, IO);
	}
	/** No redirects, credentials, URL queries or default service. HTTP is restricted to loopback names. */
	public static URI endpoint(String text) {
		try {
			URI uri = URI.create(text.strip()); String scheme = uri.getScheme(), host = uri.getHost();
			if (host == null || host.isBlank() || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
					|| uri.getPort() == 0 || uri.getPort() > 65535 || uri.getPort() < -1) throw new IllegalArgumentException();
			String loopback = host.toLowerCase(Locale.ROOT);
			boolean local = loopback.equals("localhost") || loopback.equals("127.0.0.1") || loopback.equals("::1") || loopback.equals("[::1]");
			if (!"https".equalsIgnoreCase(scheme) && !("http".equalsIgnoreCase(scheme) && local)) throw new IllegalArgumentException();
			return uri.normalize();
		} catch (RuntimeException failure) { throw new IllegalArgumentException("ui.reports.invalidEndpoint"); }
	}
	/** Only an explicit UI submit action invokes this method; errors never print a URL or response body. */
	public static CompletableFuture<Result> submit(URI destination, Prepared report) {
		URI checked;
		try { checked = endpoint(destination.toString()); } catch (RuntimeException invalid) { return CompletableFuture.completedFuture(new Result("ui.reports.invalidEndpoint", 0, null)); }
		if (report.bytes.length > MAX_PAYLOAD_BYTES) return CompletableFuture.completedFuture(new Result("ui.reports.tooLarge", 0, null));
		if (!SENDING.compareAndSet(false, true)) return CompletableFuture.completedFuture(new Result("ui.reports.alreadySending", 0, null));
		return CompletableFuture.supplyAsync(() -> {
			try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
				HttpRequest request = HttpRequest.newBuilder(checked).timeout(Duration.ofSeconds(15))
						.header("Content-Type", "application/json; charset=utf-8").header("User-Agent", "CheatProgramReports/1")
						.POST(HttpRequest.BodyPublishers.ofByteArray(report.bytes)).build();
				HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
				try (InputStream ignored = response.body()) {
					int code = response.statusCode(); return new Result(code >= 200 && code < 300 ? "ui.reports.sent" : "ui.reports.httpFailed", code, null);
				}
			} catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return new Result("ui.reports.sendFailed", 0, null); }
			catch (IOException | RuntimeException failure) { return new Result("ui.reports.sendFailed", 0, null); }
			finally { SENDING.set(false); }
		}, IO);
	}
	/** Raw crash descriptions and exception messages never enter the report; free user notes get these filters too. */
	private static final class Redactor {
		private static final Pattern UUID = Pattern.compile("(?i)\\b(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32})\\b");
		private static final Pattern SECRET_LINE = Pattern.compile("(?im)^.*(?:authorization|api[ _-]?key|access[ _-]?token|refresh[ _-]?token|password|secret|cookie|session[ _-]?id)\\s*[:=].*$");
		private static final Pattern KEY = Pattern.compile("(?i)\\b(?:sk|rk|pk|api)[_-][A-Za-z0-9_-]{12,}\\b|\\bBearer\\s+[^\\s,;]+|\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
		private static final Pattern IPV4 = Pattern.compile("(?<![A-Za-z0-9_])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?(?![A-Za-z0-9_])");
		private static final Pattern IPV6 = Pattern.compile("(?i)(?<![A-Za-z0-9_])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?:%[A-Za-z0-9_.-]+)?");
		private static final Pattern ADDRESS = Pattern.compile("(?i)(?:https?|wss?|ftp)://[^\\s<>]+|\\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,63}(?::[0-9]{1,5})?\\b");
		private static final Pattern PATH = Pattern.compile("(?i)(?:[a-z]:[\\\\/]|\\\\\\\\)[^\\r\\n\"<>|]*|(?<![\\w])/[^\\s\"<>]+");
		private static final Pattern CHAT = Pattern.compile("(?im)^.*(?:^<[^>]+>\\s|chat(?:\\s*message|\\s*content)?\\s*[:=]|signedContent\\s*[:=]|聊天(?:内容|正文|记录)?\\s*[:：=]|发言\\s*[:：=]).*$");
		private static final Pattern LONG_TOKEN = Pattern.compile("(?<![A-Za-z0-9_])[A-Za-z0-9_+/=-]{32,}(?![A-Za-z0-9_])");
		private final List<String> identities;
		Redactor(List<String> identities) { this.identities = identities.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList(); }
		String identifiers(String input) {
			String text = UUID.matcher(input).replaceAll("[UUID]");
			for (String identity : identities) if (!identity.isBlank()) text = Pattern.compile(Pattern.quote(identity), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text).replaceAll("[identity]");
			return KEY.matcher(text).replaceAll("[credential]");
		}
		String text(String input) {
			String text = input.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
			text = CHAT.matcher(text).replaceAll("[chat omitted]"); text = SECRET_LINE.matcher(text).replaceAll("[credential omitted]");
			text = identifiers(text); text = PATH.matcher(text).replaceAll("[path]"); text = ADDRESS.matcher(text).replaceAll("[address]");
			text = IPV4.matcher(text).replaceAll("[IP]"); text = IPV6.matcher(text).replaceAll("[IP]");
			return LONG_TOKEN.matcher(text).replaceAll("[token]");
		}
	}
}
