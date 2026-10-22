package com.cheatprogram.media;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Read-only Windows media metadata. Render callers only read an immutable snapshot. */
public final class MusicService {
	private static final String RESOURCE = "/assets/cheatprogram/music/windows-media.ps1";
	private static final String COMPAT_RESOURCE = "/assets/cheatprogram/music/cloudmusic-compat.ps1";
	private static final long STALE_NANOS = TimeUnit.SECONDS.toNanos(5);
	private static final long HELPER_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
	private static final AtomicBoolean STARTED = new AtomicBoolean();
	private static final AtomicBoolean STOPPED = new AtomicBoolean();
	private static volatile Track snapshot;
	private static volatile List<Track> sessionSnapshots = List.of();
	private static volatile long lastOutputNanos;
	private static volatile Process helper;
	private static volatile Thread supervisor;
	private static volatile DevOverride devOverride;
	private static volatile Path helperDirectory;

	/** provider is the original Windows SourceAppUserModelId; source is suitable for display. */
	public record Track(String id, String source, String title, String artist, boolean playing, String provider,
			double position, double duration, String album, long sampledNanos, boolean timelineAvailable) {
		public Track(String id, String source, String title, String artist, boolean playing) {
			this(id, source, title, artist, playing, source);
		}

		public Track(String id, String source, String title, String artist, boolean playing, String provider) {
			this(id, source, title, artist, playing, provider, 0, 0, "", System.nanoTime(), false);
		}

		/** Explicit progress supplied by existing callers remains a known timeline. */
		public Track(String id, String source, String title, String artist, boolean playing, String provider,
				double position, double duration, String album, long sampledNanos) {
			this(id, source, title, artist, playing, provider, position, duration, album, sampledNanos, true);
		}

		public Track {
			id = Objects.requireNonNullElse(id, "");
			source = clean(source, 128);
			title = clean(title, 256);
			artist = clean(artist, 256);
			provider = clean(provider, 512);
			album = clean(album, 256);
			duration = Double.isFinite(duration) ? Math.max(0, duration) : 0;
			position = Double.isFinite(position) ? Math.max(0, position) : 0;
			if (duration > 0) position = Math.min(position, duration);
			if (sampledNanos <= 0) sampledNanos = System.nanoTime();
		}

		/** Smooth wall-clock progress between Windows timeline samples; a paused record stays frozen. */
		public double positionNow() {
			double now = position + (timelineAvailable && playing ? Math.max(0, System.nanoTime() - sampledNanos) / 1_000_000_000.0 : 0);
			return duration > 0 ? Math.min(duration, now) : now;
		}
	}

	private record DevOverride(Track track) {
	}

	private MusicService() {
	}

	/** Never waits for Windows, a process or a file. Null means no current track or unavailable metadata. */
	public static Track current() {
		DevOverride injected = devOverride;
		if (injected != null) return injected.track();
		if (STOPPED.get() || !isWindows() || !Boolean.parseBoolean(System.getProperty("cheatprogram.music", "true"))) return null;
		ensureStarted();
		return System.nanoTime() - lastOutputNanos <= STALE_NANOS ? snapshot : null;
	}

	/** All published Windows media sessions, including videos for explicitly selected cast sources. */
	public static List<Track> sessions() {
		DevOverride injected = devOverride;
		if (injected != null) return injected.track() == null ? List.of() : List.of(injected.track());
		if (STOPPED.get() || !isWindows() || !Boolean.parseBoolean(System.getProperty("cheatprogram.music", "true"))) return List.of();
		ensureStarted();
		return System.nanoTime() - lastOutputNanos <= STALE_NANOS ? sessionSnapshots : List.of();
	}

	/** Fixed metadata for development screenshots; inactive in an ordinary game launch. */
	public static void devTrack(Track track) {
		if (development()) devOverride = new DevOverride(Objects.requireNonNull(track));
	}

	/** Force the no-music state while development automation is active. */
	public static void devClear() {
		if (development()) devOverride = new DevOverride(null);
	}

	/** Return to the real Windows sessions after a development injection. */
	public static void devRelease() {
		if (development()) devOverride = null;
	}

	private static boolean development() {
		return !System.getProperty("cheatprogram.dev", "").isBlank();
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	}

	private static void ensureStarted() {
		if (!STARTED.compareAndSet(false, true)) return;
		Thread worker = new Thread(MusicService::supervise, "Cheat Program Windows music");
		worker.setDaemon(true);
		supervisor = worker;
		Runtime.getRuntime().addShutdownHook(new Thread(MusicService::shutdown, "Cheat Program music shutdown"));
		worker.start();
	}

	private static void supervise() {
		try {
			Path script = extractHelper();
			String systemRoot = System.getenv("SystemRoot");
			if (systemRoot == null || systemRoot.isBlank()) return;
			Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
			if (!Files.isRegularFile(powershell)) return;
			while (!STOPPED.get()) {
				Process process = null;
				Thread reader = null;
				try {
					process = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
							"-ExecutionPolicy", "Bypass", "-File", script.toString(), "-ParentProcessId",
							Long.toString(ProcessHandle.current().pid()), "-PollMillis", "1000")
							.redirectError(ProcessBuilder.Redirect.DISCARD).start();
					helper = process;
					lastOutputNanos = System.nanoTime();
					Process running = process;
					reader = new Thread(() -> readSnapshots(running), "Cheat Program music pipe");
					reader.setDaemon(true);
					reader.start();
					while (!STOPPED.get() && !process.waitFor(1, TimeUnit.SECONDS)) {
						if (System.nanoTime() - lastOutputNanos > HELPER_TIMEOUT_NANOS) break;
					}
				} catch (IOException ignored) {
					// An absent/blocked Windows helper leaves the ordinary HUD usable.
				} finally {
					if (helper == process) helper = null;
					snapshot = null;
					sessionSnapshots = List.of();
					stopProcess(process);
					if (reader != null) reader.join(1000);
				}
				if (!STOPPED.get()) Thread.sleep(30_000);
			}
		} catch (IOException ignored) {
			// Missing resources or read-only temporary storage disable metadata without affecting gameplay.
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		} finally {
			snapshot = null;
			cleanupHelper();
		}
	}

	private static Path extractHelper() throws IOException {
		try (InputStream resource = MusicService.class.getResourceAsStream(RESOURCE)) {
			if (resource == null) throw new IOException("Windows music helper resource is missing");
			Path directory = Files.createTempDirectory("cheatprogram-music-");
			helperDirectory = directory;
			Path script = directory.resolve("windows-media.ps1");
			Files.copy(resource, script, StandardCopyOption.REPLACE_EXISTING);
			try (InputStream compatibility = MusicService.class.getResourceAsStream(COMPAT_RESOURCE)) {
				if (compatibility != null) Files.copy(compatibility, directory.resolve("cloudmusic-compat.ps1"), StandardCopyOption.REPLACE_EXISTING);
			}
			return script;
		}
	}

	private static void readSnapshots(Process process) {
		try (BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while (!STOPPED.get() && (line = input.readLine()) != null) {
				if (helper != process) break;
				try {
					JsonObject result = JsonParser.parseString(line).getAsJsonObject();
					Track next = decode(result);
					List<Track> sessions = decodeSessions(result);
					if (helper == process) {
						snapshot = calibrate(snapshot, next);
						List<Track> previous = sessionSnapshots;
						sessionSnapshots = sessions.stream().map(track -> calibrate(previous.stream()
								.filter(old -> old.id().equals(track.id())).findFirst().orElse(null), track)).toList();
						lastOutputNanos = System.nanoTime();
					}
				} catch (RuntimeException ignored) {
					// Ignore diagnostics/malformed data; the watchdog clears a stale snapshot.
				}
			}
		} catch (IOException ignored) {
			// The supervisor closes this stream when the helper exits or times out.
		}
	}

	static Track decode(JsonObject result) {
		if (!result.has("available") || !result.get("available").getAsBoolean()) return null;
		JsonElement value = result.get("track");
		if (value == null || value.isJsonNull() || !value.isJsonObject()) return null;
		return decodeTrack(value.getAsJsonObject(), false);
	}

	static List<Track> decodeSessions(JsonObject result) {
		List<Track> tracks = new ArrayList<>();
		JsonElement array = result.get("sessions");
		if (array != null && array.isJsonArray()) {
			for (JsonElement item : array.getAsJsonArray()) {
				if (tracks.size() >= 16) break;
				if (!item.isJsonObject()) continue;
				try {
					Track track = decodeTrack(item.getAsJsonObject(), true);
					// WinRT can return distinct RCWs for GetCurrentSession and the same GetSessions entry.
					if (track != null && tracks.stream().noneMatch(old -> old.id().equals(track.id()))) tracks.add(track);
				} catch (RuntimeException ignored) { }
			}
		}
		Track selected = decode(result);
		if (selected != null && tracks.stream().noneMatch(track -> track.id().equals(selected.id()))) tracks.add(0, selected);
		return List.copyOf(tracks);
	}

	private static Track decodeTrack(JsonObject track, boolean allowVideo) {
		String status = string(track, "status", 32);
		if ("Closed".equalsIgnoreCase(status)) return null;
		String title = string(track, "title", 256);
		if (title.isBlank()) return null;
		String artist = string(track, "artist", 256);
		String provider = string(track, "provider", 512);
		String mediaType = string(track, "mediaType", 32);
		if ("Image".equalsIgnoreCase(mediaType) || !allowVideo && "Video".equalsIgnoreCase(mediaType)) return null;
		if (!allowVideo && (mediaType.isBlank() || "Unknown".equalsIgnoreCase(mediaType)) && !knownMusicProvider(provider)) return null;
		String source = friendlySource(provider, string(track, "displayName", 128));
		String album = string(track, "album", 256);
		String identity = provider + "\n" + title + "\n" + artist + "\n" + album;
		String id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
		boolean playing = "Playing".equalsIgnoreCase(status);
		boolean timelineAvailable = track.has("timelineAvailable") && !track.get("timelineAvailable").isJsonNull()
				&& track.get("timelineAvailable").getAsBoolean();
		double position = number(track, "position");
		double duration = number(track, "duration");
		if (timelineAvailable && playing && track.has("sampledUtcMillis")) {
			long sampled = track.get("sampledUtcMillis").getAsLong();
			position += Math.max(0, Math.min(5000, System.currentTimeMillis() - sampled)) / 1000.0;
		}
		return new Track(id, source, title, artist, playing, provider, position, duration, album, System.nanoTime(), timelineAvailable);
	}

	static Track calibrate(Track previous, Track next) {
		if (previous == null || next == null || !next.timelineAvailable() || !previous.timelineAvailable() || !next.playing()
				|| !previous.id().equals(next.id()) || previous.playing() != next.playing()) return next;
		double expected = previous.positionNow();
		// Absorb sub-second polling jitter; a real seek is applied immediately.
		if (Math.abs(expected - next.position()) > 0.65) return next;
		return new Track(next.id(), next.source(), next.title(), next.artist(), next.playing(), next.provider(),
				expected, next.duration(), next.album(), System.nanoTime(), next.timelineAvailable());
	}

	private static double number(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value == null || value.isJsonNull() ? 0 : value.getAsDouble();
	}

	private static boolean knownMusicProvider(String provider) {
		String key = provider.toLowerCase(Locale.ROOT);
		return key.contains("cloudmusic") || key.contains("netease") || key.contains("qqmusic") || key.contains("spotify")
				|| key.contains("applemusic") || key.contains("apple-music") || key.contains("zunemusic")
				|| key.contains("foobar2000") || key.contains("aimp") || key.contains("musicbee");
	}

	static String friendlySource(String provider, String displayName) {
		String key = provider.toLowerCase(Locale.ROOT);
		if (key.contains("cloudmusic") || key.contains("netease")) return "网易云音乐";
		if (key.contains("qqmusic")) return "QQ音乐";
		if (key.contains("spotify")) return "Spotify";
		if (key.contains("applemusic") || key.contains("apple-music")) return "Apple Music";
		if (key.contains("zunemusic") || key.contains("mediaplayer")) return "媒体播放器";
		if (key.contains("foobar2000")) return "foobar2000";
		if (key.contains("potplayer")) return "PotPlayer";
		if (key.contains("msedge")) return "Edge";
		if (key.contains("chrome")) return "Chrome";
		if (key.contains("firefox")) return "Firefox";
		if (!displayName.isBlank()) return displayName;
		String fallback = provider.replace('\\', '/');
		fallback = fallback.substring(fallback.lastIndexOf('/') + 1);
		if (fallback.toLowerCase(Locale.ROOT).endsWith(".exe")) fallback = fallback.substring(0, fallback.length() - 4);
		int separator = fallback.indexOf('!');
		if (separator >= 0) fallback = fallback.substring(separator + 1);
		return fallback.isBlank() ? "音乐" : clean(fallback, 128);
	}

	private static String string(JsonObject object, String key, int limit) {
		JsonElement value = object.get(key);
		return value == null || value.isJsonNull() ? "" : clean(value.getAsString(), limit);
	}

	private static String clean(String value, int limit) {
		String text = Objects.requireNonNullElse(value, "").replaceAll("[\\x00-\\x1f\\x7f]", " ").strip();
		return text.length() > limit ? text.substring(0, limit) : text;
	}

	private static void stopProcess(Process process) {
		if (process == null) return;
		process.destroy();
		try {
			if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
			process.getInputStream().close();
		} catch (IOException ignored) {
		} catch (InterruptedException ignored) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
		}
	}

	private static void cleanupHelper() {
		Path directory = helperDirectory;
		if (directory == null) return;
		try {
			Files.deleteIfExists(directory.resolve("windows-media.ps1"));
			Files.deleteIfExists(directory.resolve("cloudmusic-compat.ps1"));
			Files.deleteIfExists(directory);
		} catch (IOException ignored) {
			// Windows can briefly retain an open file handle while a terminated helper exits.
		}
	}

	/** Called automatically during JVM shutdown; also useful for standalone service validation. */
	public static void shutdown() {
		STOPPED.set(true);
		snapshot = null;
		sessionSnapshots = List.of();
		stopProcess(helper);
		Thread worker = supervisor;
		if (worker != null) worker.interrupt();
	}
}
