package com.cheatprogram.media;

import com.cheatprogram.core.Config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Read-only song recognition through Windows metadata, with asynchronous timed-lyrics lookup. */
public final class FloatingLyricsService implements AutoCloseable {
	public record Request(String source, String provider, int castPid, String manualTitle, String manualArtist,
			Path lrcPath, Path translatedPath, Path cacheDir, boolean onlineLookup) {
		public Request {
			source = Objects.requireNonNullElse(source, "music"); provider = Objects.requireNonNullElse(provider, "");
			manualTitle = clean(manualTitle); manualArtist = clean(manualArtist);
		}
	}
	public record Snapshot(MusicService.Track track, LrcLyrics lyrics, String statusKey, String detail, String requestIdentity) {
		public boolean ready() { return lyrics != null && !lyrics.isEmpty(); }
	}
	private record Loaded(LrcLyrics lyrics, String status, String detail) { }
	private record HttpResult(int status, String json) { }
	private static final int MAX_BYTES = 2_000_000;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "Cheat Program floating lyrics"); t.setDaemon(true); return t; });
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
	private final Map<String, Loaded> memory = new LinkedHashMap<>(32, .75f, true) {
		@Override protected boolean removeEldestEntry(Map.Entry<String, Loaded> eldest) { return size() > 32; }
	};
	private volatile Snapshot snapshot = new Snapshot(null, null, "ui.floatingLyrics.noTrack", "", "");
	private volatile String identity = "";
	private volatile long generation;
	private long reloadVersion;
	private Future<?> task;
	private volatile boolean closed;
	private double manualPosition;
	private long manualSampled = System.nanoTime();
	private boolean manualPlaying = true;
	private String manualSong = "";
	private int processPid = -1;
	private String processName = "";
	private String explicitChoice = "", explicitProvider = "";
	public Snapshot snapshot() { return snapshot; }
	public List<MusicService.Track> sessions() { return MusicService.sessions(); }
	/** Call on the game tick; all file and network operations run on the private worker. */
	public synchronized void update(Request request) {
		if (closed || request == null) return;
		MusicService.Track track = overrideMetadata(selectTrack(request), request);
		if (track == null) {
			String status = "cast".equals(request.source()) ? "ui.floatingLyrics.castNoSession" : "ui.floatingLyrics.noTrack";
			if (!identity.isEmpty()) { identity = ""; generation++; if (task != null) task.cancel(true); }
			snapshot = new Snapshot(null, null, status, "", ""); return;
		}
		String key = track.id() + "\n" + Math.round(track.duration()) + "\n" + request.lrcPath() + "\n" + request.translatedPath()
				+ "\n" + request.onlineLookup() + "\n" + request.cacheDir() + "\n" + reloadVersion;
		if (key.equals(identity)) {
			Snapshot previous = snapshot;
			snapshot = new Snapshot(track, previous.lyrics(), previous.statusKey(), previous.detail(), key); return;
		}
		identity = key; long serial = ++generation;
		if (task != null) task.cancel(true);
		snapshot = new Snapshot(track, null, "ui.floatingLyrics.loading", "", key);
		task = worker.submit(() -> {
			Loaded loaded;
			try { loaded = load(request, track); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
			catch (Exception failure) { loaded = new Loaded(null, "ui.floatingLyrics.failed", failure.getClass().getSimpleName()); }
			synchronized (this) {
				if (closed || generation != serial || !identity.equals(key)) return;
				Snapshot latest = snapshot;
				snapshot = new Snapshot(latest.track(), loaded.lyrics(), loaded.status(), loaded.detail(), key);
			}
		});
	}
	/** Force a fresh local-file read and retry a failed online lookup without changing the song. */
	public synchronized void reload() { reloadVersion++; synchronized (memory) { memory.clear(); } }
	/** Cancel outstanding work when the feature is turned off, while allowing it to be enabled again. */
	public synchronized void reset() {
		generation++; identity = ""; if (task != null) task.cancel(true);
		snapshot = new Snapshot(null, null, "ui.floatingLyrics.noTrack", "", "");
	}
	/** Explicit manual transport for a source which does not publish a Windows timeline. */
	public void setManualPosition(double seconds, boolean playing) {
		manualPosition = Double.isFinite(seconds) ? Math.max(0, seconds) : 0; manualPlaying = playing; manualSampled = System.nanoTime();
	}
	private MusicService.Track selectTrack(Request request) {
		List<MusicService.Track> all = MusicService.sessions();
		if ("manual".equals(request.source())) {
			String song = request.manualTitle() + "\n" + request.manualArtist() + "\n" + request.lrcPath();
			if (!song.equals(manualSong)) { manualSong = song; setManualPosition(0, true); }
			if (request.manualTitle().isBlank() && request.lrcPath() == null) return null;
			return new MusicService.Track("manual:" + hash(song), "", request.manualTitle(), request.manualArtist(), manualPlaying,
					"manual", manualPosition, 0, "", manualSampled, true);
		}
		if (!request.provider().isBlank()) {
			if (!explicitChoice.equals(request.provider())) { explicitChoice = request.provider(); explicitProvider = ""; }
			MusicService.Track exact = all.stream().filter(track -> track.id().equals(request.provider()) || track.provider().equals(request.provider()))
					.sorted((a, b) -> Boolean.compare(b.playing(), a.playing())).findFirst().orElse(null);
			if (exact != null) { explicitProvider = exact.provider(); return exact; }
			// A song-derived ID cannot identify a browser tab after a song change. Only unambiguous fallback is safe.
			List<MusicService.Track> sameApp = explicitProvider.isBlank() ? List.of() : all.stream().filter(track -> track.provider().equals(explicitProvider)).toList();
			return sameApp.size() == 1 ? sameApp.get(0) : null;
		}
		explicitChoice = explicitProvider = "";
		if ("cast".equals(request.source())) {
			if (request.castPid() <= 0) return null;
			if (processPid != request.castPid()) {
				processPid = request.castPid();
				processName = ProcessHandle.of(processPid).flatMap(p -> p.info().command()).map(command -> {
					try { return Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT).replaceFirst("\\.exe$", ""); }
					catch (RuntimeException ignored) { return ""; }
				}).orElse("");
			}
			if (processName.length() < 3) return null;
			String expected = processName;
			// Windows exposes an app identity, not a particular browser tab/window. A user can select a session explicitly.
			return all.stream().filter(track -> track.provider().toLowerCase(Locale.ROOT).contains(expected))
					.sorted((a, b) -> Boolean.compare(b.playing(), a.playing())).findFirst().orElse(null);
		}
		return MusicService.current();
	}
	private static MusicService.Track overrideMetadata(MusicService.Track track, Request request) {
		if (track == null || "manual".equals(request.source()) || request.manualTitle().isBlank() && request.manualArtist().isBlank()) return track;
		String title = request.manualTitle().isBlank() ? track.title() : request.manualTitle();
		String artist = request.manualArtist().isBlank() ? track.artist() : request.manualArtist();
		return new MusicService.Track(track.id() + ":metadata:" + hash(title + "\n" + artist), track.source(), title, artist,
				track.playing(), track.provider(), track.position(), track.duration(), track.album(), track.sampledNanos(), track.timelineAvailable());
	}
	/** An explicitly named song can differ in length from its video; playback always retains the real media timeline. */
	static MusicService.Track lookupTrack(Request request, MusicService.Track track) {
		if ("manual".equals(request.source()) || request.manualTitle().isBlank() && request.manualArtist().isBlank()) return track;
		return new MusicService.Track(track.id(), track.source(), track.title(), track.artist(), track.playing(), track.provider(),
				track.position(), 0, "", track.sampledNanos(), track.timelineAvailable());
	}
	private Loaded load(Request request, MusicService.Track track) throws IOException, InterruptedException {
		if (request.lrcPath() != null) {
			LrcLyrics lyrics = readLyrics(request.lrcPath());
			if (request.translatedPath() != null) lyrics = lyrics.withTranslation(readLyrics(request.translatedPath()));
			return result(lyrics, "ui.floatingLyrics.localReady", request.lrcPath().getFileName().toString());
		}
		MusicService.Track lookup = lookupTrack(request, track);
		String cacheKey = hash((lookup != track ? "manual-override\n" : "") + lookup.title() + "\n" + lookup.artist() + "\n" + lookup.album() + "\n" + Math.round(lookup.duration()));
		Loaded cached;
		synchronized (memory) { cached = memory.get(cacheKey); }
		if (cached != null) return translate(cached, request.translatedPath());
		Path cacheFile = request.cacheDir() == null ? null : request.cacheDir().resolve(cacheKey + ".json");
		if (cacheFile != null && Files.isRegularFile(cacheFile)) {
			try {
				Loaded fromDisk = decodeResponse(readBounded(cacheFile), lookup);
				if (fromDisk.lyrics() != null) { synchronized (memory) { memory.put(cacheKey, fromDisk); } return translate(fromDisk, request.translatedPath()); }
			} catch (RuntimeException | IOException ignored) { }
		}
		if (!request.onlineLookup()) return new Loaded(null, "ui.floatingLyrics.offlineMissing", "");
		if (lookup.title().isBlank() || lookup.artist().isBlank()) return new Loaded(null, "ui.floatingLyrics.metadataIncomplete", "");
		String query = "track_name=" + encode(lookup.title()) + "&artist_name=" + encode(lookup.artist());
		if (!lookup.album().isBlank()) query += "&album_name=" + encode(lookup.album());
		if (lookup.duration() > 0) query += "&duration=" + Math.round(lookup.duration());
		String json = null; Loaded found = null;
		try {
			// LRCLIB's old /get-cached route no longer resolves songs. /get is the current metadata route.
			HttpResult exact = fetch("get?" + query);
			if (exact.status() == 200) { json = exact.json(); found = decodeResponse(json, lookup); }
			else if (exact.status() != 404) return new Loaded(null, "ui.floatingLyrics.networkFailed", "HTTP " + exact.status());
			if (found == null || !"ui.floatingLyrics.instrumental".equals(found.status()) && (found.lyrics() == null || !found.lyrics().synced())) {
				HttpResult search = fetch("search?track_name=" + encode(lookup.title()) + "&artist_name=" + encode(lookup.artist()));
				if (search.status() == 200) {
					String match = matchingSearchResponse(search.json(), lookup);
					if (match != null) { json = match; found = decodeResponse(match, lookup); }
				} else if (found == null && search.status() != 404) return new Loaded(null, "ui.floatingLyrics.networkFailed", "HTTP " + search.status());
			}
		} catch (IOException failure) { return new Loaded(null, "ui.floatingLyrics.networkFailed", failure.getClass().getSimpleName()); }
		if (found == null) found = new Loaded(null, "ui.floatingLyrics.notFound", "LRCLIB");
		synchronized (memory) { memory.put(cacheKey, found); }
		if (cacheFile != null && found.lyrics() != null) {
			synchronized (Config.persistenceLock()) {
				// A cancelled lookup must never recreate a directory moved to the recycle bin by initialization.
				if (!closed && !Thread.currentThread().isInterrupted() && Config.persistenceAllowed()) {
					try {
						Files.createDirectories(cacheFile.getParent()); Path temporary = Files.createTempFile(cacheFile.getParent(), "lyrics-", ".tmp");
						try { Files.writeString(temporary, json, StandardCharsets.UTF_8); Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING); }
						finally { Files.deleteIfExists(temporary); }
					} catch (IOException ignored) { }
				}
			}
		}
		return translate(found, request.translatedPath());
	}
	private HttpResult fetch(String path) throws IOException, InterruptedException {
		HttpRequest call = HttpRequest.newBuilder(URI.create("https://lrclib.net/api/" + path)).timeout(Duration.ofSeconds(9))
				.header("Accept", "application/json").header("User-Agent", "CheatProgram/1.0 FloatingLyrics").GET().build();
		HttpResponse<InputStream> response = http.send(call, HttpResponse.BodyHandlers.ofInputStream());
		try (InputStream body = response.body()) {
			if (response.statusCode() != 200) return new HttpResult(response.statusCode(), "");
			byte[] bytes = body.readNBytes(MAX_BYTES + 1); if (bytes.length > MAX_BYTES) throw new IOException("Lyrics response exceeds limit");
			return new HttpResult(200, new String(bytes, StandardCharsets.UTF_8));
		}
	}
	/** Search broadens album matching, never the required title, artist or known-duration checks. */
	static String matchingSearchResponse(String json, MusicService.Track track) {
		var candidates = JsonParser.parseString(json).getAsJsonArray(); String best = null; double score = Double.POSITIVE_INFINITY; int count = 0;
		for (var item : candidates) {
			if (++count > 50) break;
			if (!item.isJsonObject()) continue;
			try {
				JsonObject candidate = item.getAsJsonObject(); Loaded decoded = decodeResponse(candidate.toString(), track);
				if (decoded.lyrics() == null) continue;
				double candidateScore = decoded.lyrics().synced() ? 0 : 1000;
				if (!track.album().isBlank() && !normalized(track.album()).equals(normalized(property(candidate, "albumName")))) candidateScore += 10;
				if (track.duration() > 0 && candidate.has("duration") && !candidate.get("duration").isJsonNull()) candidateScore += Math.abs(track.duration() - candidate.get("duration").getAsDouble());
				if (candidateScore < score) { score = candidateScore; best = candidate.toString(); }
			} catch (RuntimeException ignored) { }
		}
		return best;
	}
	private static Loaded translate(Loaded loaded, Path translation) throws IOException {
		return loaded.lyrics() == null || translation == null ? loaded : new Loaded(loaded.lyrics().withTranslation(readLyrics(translation)), loaded.status(), loaded.detail());
	}
	private static Loaded decodeResponse(String json, MusicService.Track track) {
		JsonObject data = JsonParser.parseString(json).getAsJsonObject();
		String name = property(data, "trackName"), artist = property(data, "artistName");
		if (!normalized(name).equals(normalized(track.title())) || !normalized(artist).equals(normalized(track.artist()))) return new Loaded(null, "ui.floatingLyrics.metadataMismatch", "LRCLIB");
		if (track.duration() > 0 && data.has("duration") && !data.get("duration").isJsonNull() && Math.abs(track.duration() - data.get("duration").getAsDouble()) > 3)
			return new Loaded(null, "ui.floatingLyrics.metadataMismatch", "LRCLIB");
		if (data.has("instrumental") && data.get("instrumental").getAsBoolean()) return new Loaded(null, "ui.floatingLyrics.instrumental", "LRCLIB");
		String synced = property(data, "syncedLyrics"), plain = property(data, "plainLyrics");
		if (synced.isBlank() && plain.isBlank()) return new Loaded(null, "ui.floatingLyrics.notFound", "LRCLIB");
		return result(LrcLyrics.parse(synced.isBlank() ? plain : synced), "ui.floatingLyrics.onlineReady", "LRCLIB");
	}
	private static Loaded result(LrcLyrics lyrics, String ready, String detail) {
		return new Loaded(lyrics, lyrics.isEmpty() ? "ui.floatingLyrics.emptyFile" : !lyrics.synced() ? "ui.floatingLyrics.unsynced" : ready, detail);
	}
	private static LrcLyrics readLyrics(Path file) throws IOException {
		byte[] bytes;
		try (InputStream input = Files.newInputStream(file)) { bytes = input.readNBytes(MAX_BYTES + 1); }
		if (bytes.length > MAX_BYTES) throw new IOException("Lyrics file exceeds limit");
		String text;
		if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) text = new String(bytes, StandardCharsets.UTF_16LE);
		else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) text = new String(bytes, StandardCharsets.UTF_16BE);
		else {
			text = new String(bytes, StandardCharsets.UTF_8);
			if (text.indexOf('\ufffd') >= 0) text = new String(bytes, java.nio.charset.Charset.forName("GB18030"));
		}
		return LrcLyrics.parse(text);
	}
	private static String readBounded(Path path) throws IOException { try (InputStream input = Files.newInputStream(path)) { byte[] bytes = input.readNBytes(MAX_BYTES + 1); if (bytes.length > MAX_BYTES) throw new IOException("Cache exceeds limit"); return new String(bytes, StandardCharsets.UTF_8); } }
	private static String property(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
	private static String normalized(String text) { return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}\\p{P}]", ""); }
	private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
	private static String clean(String value) { String text = Objects.requireNonNullElse(value, "").strip().replaceAll("[\\x00-\\x1f\\x7f]", " "); return text.length() > 512 ? text.substring(0, 512) : text; }
	private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); } }
	public void shutdown() { close(); }
	@Override public synchronized void close() {
		closed = true; generation++; if (task != null) task.cancel(true); worker.shutdownNow(); snapshot = new Snapshot(null, null, "ui.floatingLyrics.noTrack", "", "");
	}
}
