package com.cheatprogram.audio;

import com.cheatprogram.core.Config;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Util;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Explicit per-machine audio environment. None of these values are module settings or configuration-code data. */
public final class SpatialAudioEnvironment {
    public record State(String statusKey, boolean busy, boolean driverInstalled, boolean ready,
                        boolean routed, int routedPid, String playbackDevice, String details) {}

    private static final String PREFIX = "ui.spatial.env.status.";
    private static final URI PACKAGE = URI.create("https://download.vb-audio.com/Download_CABLE/VBCABLE_Driver_Pack45.zip");
    private static final long OWNER = ProcessHandle.current().pid();
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(job -> daemon(job, "CheatProgram-audio-environment"));
    private static final ExecutorService IO = Executors.newCachedThreadPool(job -> daemon(job, "CheatProgram-audio-route-io"));
    private static final ScheduledExecutorService WATCH = Executors.newSingleThreadScheduledExecutor(job -> daemon(job, "CheatProgram-audio-route-lease"));
    private static final AtomicBoolean OPERATION = new AtomicBoolean();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final Object LEASE_LOCK = new Object();
    private static volatile State state = new State(PREFIX + "unchecked", false, false, false, false, 0, "", "");
    private static volatile boolean automaticRoutingSuspended;
    private static volatile int desiredPid;
    private static volatile long lastRouteAttempt;
    private static volatile Lease lease;
    private static volatile Path helper;
    private static volatile Process installerProcess;
    private static CompletableFuture<Void> releasing = CompletableFuture.completedFuture(null);

    private SpatialAudioEnvironment() {}
    private static Thread daemon(Runnable job, String name) { Thread thread = new Thread(job, name); thread.setDaemon(true); return thread; }
    public static State state() { return state; }
    public static String statusKey() { return state.statusKey; }
    public static boolean busy() { Process process = installerProcess; return OPERATION.get() || (process != null && process.isAlive()); }
    public static boolean ready() { return state.ready; }
    public static boolean routedFor(int pid) {
        Lease current = lease;
        return state.ready && !automaticRoutingSuspended && current != null && current.pid == pid && current.routed
                && !current.cancelled && current.process != null && current.process.isAlive()
                && System.nanoTime() - current.lastReport < TimeUnit.SECONDS.toNanos(4);
    }
    public static int activeSessions() { Lease current = lease; return current == null ? 0 : current.activeSessions; }
    public static int isolatedSessions() { Lease current = lease; return current == null ? 0 : current.isolatedSessions; }
    public static Path directory() { return FabricLoader.getInstance().getConfigDir().resolve("cheatprogram/spatial-audio").toAbsolutePath().normalize(); }
    public static Path downloadDirectory() { return directory().resolve("downloads"); }
    public static void openFolder() { open(directory()); }
    public static void openDownloadFolder() { open(downloadDirectory()); }
    private static void open(Path path) { IO.execute(() -> { try { allowed(); Files.createDirectories(path); Util.getPlatform().openFile(path.toFile()); } catch (Exception error) { failure(error); } }); }

    public static void refresh() { submit("checking", () -> detect(true)); }
    public static void install() {
        submit("checking", () -> { releaseAndWait(); if (!detect(false).installed) installDriver(false); else detect(true); });
    }
    public static void uninstall() {
        automaticRoutingSuspended = true; desiredPid = 0; GENERATION.incrementAndGet(); releaseLease();
        submit("uninstalling", () -> { releaseAndWait(); installDriver(true); });
    }
    /** The user-facing action may install a driver; automatic requestRoute never does. */
    public static void configure(int pid) {
        if (busy()) return;
        if (!validPid(pid)) { set("sourceUnavailable", ""); return; }
        automaticRoutingSuspended = false; desiredPid = pid;
        long generation = GENERATION.incrementAndGet();
        submit("checking", () -> {
            releaseAndWait();
            Detection detection = detect(false);
            if (!detection.installed) { installDriver(false); detection = detect(false); }
            if (generation == GENERATION.get() && desiredPid == pid && !automaticRoutingSuspended && detection.ready) startRoute(pid, generation);
            else if (!detection.ready) publishDetection(detection);
        });
    }
    /** Idempotent module request. Missing drivers are reported, never installed or elevated here. */
    public static void requestRoute(int pid) {
        if (!validPid(pid) || automaticRoutingSuspended || !Config.persistenceAllowed()) return;
        if (!SpatialAudioBackend.playbackReady()) { if (!busy()) set("waitingPlayback", ""); return; }
        boolean changed = desiredPid != pid;
        if (changed) { desiredPid = pid; GENERATION.incrementAndGet(); releaseLease(); }
        Lease current = lease;
        if (current != null && current.pid == pid && !current.cancelled && current.process != null && current.process.isAlive()) {
            long report = current.lastReport;
            long age = System.nanoTime() - (report == 0 ? current.createdAt : report);
            if (age < TimeUnit.SECONDS.toNanos(report == 0 ? 15 : 8)) return;
            // A live process can be stuck in native routing. Stop its lease and restore
            // the selected app before retrying; do not permanently suppress replay.
            releaseLease();
        }
        long now = System.nanoTime();
        if (busy() || (!changed && now - lastRouteAttempt < TimeUnit.SECONDS.toNanos(10))) return;
        lastRouteAttempt = now;
        long generation = GENERATION.get();
        submit("checking", () -> {
            releaseAndWait(); Detection detection = detect(false);
            if (generation != GENERATION.get() || desiredPid != pid || automaticRoutingSuspended) return;
            if (detection.ready) startRoute(pid, generation); else publishDetection(detection);
        });
    }
    /** A user restore suspends automatic routing until an explicit configure/reconnect/enable. */
    public static void restore() {
        automaticRoutingSuspended = true; releaseRoute();
        long generation = GENERATION.get();
        CompletableFuture<Void> completion;
        synchronized (LEASE_LOCK) { completion = releasing; }
        set("restoring", "");
        completion.whenComplete((ignored, error) -> {
            if (generation != GENERATION.get()) return;
            if (error == null) set("restored", ""); else failure(error);
        });
    }
    /** Module lifecycle cleanup, without changing the user's automatic-routing policy. */
    public static void releaseRoute() {
        desiredPid = 0; GENERATION.incrementAndGet(); releaseLease();
    }
    public static void resumeAutomaticRouting() { automaticRoutingSuspended = false; lastRouteAttempt = 0; }

    private interface Job { void run() throws Exception; }
    private static void submit(String status, Job job) {
        if (!OPERATION.compareAndSet(false, true)) return;
        set(status, "");
        WORK.execute(() -> {
            try { allowed(); job.run(); }
            catch (Exception error) { failure(error); }
            finally { OPERATION.set(false); State old = state; state = new State(old.statusKey, false, old.driverInstalled, old.ready, routedFor(old.routedPid), old.routedPid, old.playbackDevice, old.details); }
        });
    }

    private record Detection(boolean installed, boolean ready, boolean routeSupported, boolean defaultCable, String device, String details) {}
    private static Detection detect(boolean publish) throws Exception {
        JsonObject json = helperCommand(15, "check", Long.toString(OWNER));
        boolean installed = bool(json, "driverPresent", false), supported = bool(json, "routeSupported", false);
        boolean defaultCable = bool(json, "defaultRenderCable", false);
        Detection result = new Detection(installed, bool(json, "ready", false) && supported && !defaultCable, supported,
                defaultCable, text(json, "defaultRenderName", ""), text(json, "hresult", ""));
        State old = state;
        state = new State(old.statusKey, busy(), result.installed, result.ready, old.routed, old.routedPid, result.device, result.details);
        if (publish) publishDetection(result);
        return result;
    }
    private static void publishDetection(Detection detection) {
        synchronized (LEASE_LOCK) {
            Lease current = lease;
            if (detection.ready && current != null && !current.cancelled) {
                set(routedFor(current.pid) ? "routed" : current.reportStatus.equals("routed") ? "routing" : current.reportStatus, current.reportDetails);
                return;
            }
            if (!detection.ready) releaseLease();
            set(detection.defaultCable ? "defaultsCable" : !detection.installed ? "missingDriver" : !detection.routeSupported ? "routeUnsupported"
                    : !detection.ready ? "restartRequired" : automaticRoutingSuspended ? "suspended" : "ready", detection.details);
        }
    }

    private static void startRoute(int pid, long generation) throws Exception {
        if (!validPid(pid) || generation != GENERATION.get() || automaticRoutingSuspended) return;
        if (!SpatialAudioBackend.playbackReady()) { set("waitingPlayback", ""); return; }
        recoverPreviousLeases();
        Path stateFile = stateDirectory().resolve("route-" + UUID.randomUUID() + ".json");
        Lease next = new Lease(pid, generation, stateFile);
        Process process = new ProcessBuilder(helper().toString(), "route", Integer.toString(pid), Long.toString(OWNER), stateFile.toString()).start();
        next.process = process;
        next.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.US_ASCII));
        synchronized (LEASE_LOCK) {
            if (generation != GENERATION.get() || automaticRoutingSuspended || desiredPid != pid) { next.close(); IO.execute(() -> stopLease(next)); return; }
            lease = next;
        }
        set("routing", "");
        IO.execute(() -> drain(process.getErrorStream()));
        next.heartbeat = WATCH.scheduleAtFixedRate(() -> {
            if (next.cancelled || lease != next || next.generation != GENERATION.get() || automaticRoutingSuspended
                    || desiredPid != pid || !Config.persistenceAllowed()) { next.close(); return; }
            try { next.send("P"); } catch (IOException error) { next.close(); }
        }, 0, 1, TimeUnit.SECONDS);
        if (next.cancelled) next.heartbeat.cancel(false);
        IO.execute(() -> readRoute(next));
    }
    private static void readRoute(Lease current) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(current.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 65_536) throw new IOException("Oversize route response");
                JsonObject json;
                try { json = JsonParser.parseString(line).getAsJsonObject(); } catch (RuntimeException ignored) { continue; }
                synchronized (LEASE_LOCK) {
                    if (lease != current || current.cancelled) continue;
                    String status = text(json, "status", "failed");
                    current.activeSessions = count(json, "activeSessions");
                    current.isolatedSessions = count(json, "verifiedSessions");
                    current.routed = status.equals("routed") && bool(json, "ready", false);
                    current.reportStatus = status.equals("routed") && current.routed ? "routed" : status.equals("waitingApp") ? "waitingApp"
                            : status.equals("waitingRestart") ? "waitingRestart" : status.equals("restored") ? "restored" : "failed";
                    current.reportDetails = text(json, "hresult", "");
                    current.lastReport = System.nanoTime();
                    set(current.reportStatus, current.reportDetails);
                }
            }
        } catch (Exception error) {
            synchronized (LEASE_LOCK) { if (lease == current && !current.cancelled) failure(error); }
        }
        finally {
            synchronized (LEASE_LOCK) {
                current.routed = false;
                if (lease == current && !current.cancelled) set("failed", "Route helper stopped");
                current.close();
            }
        }
    }

    private static void releaseLease() {
        Lease old;
        synchronized (LEASE_LOCK) {
            old = lease; lease = null;
            if (old == null) return;
            old.routed = false;
            releasing = releasing.handle((ignored, error) -> null).thenRunAsync(() -> stopLease(old), IO);
        }
    }
    private static void releaseAndWait() throws Exception {
        releaseLease(); CompletableFuture<Void> current;
        synchronized (LEASE_LOCK) { current = releasing; }
        current.handle((ignored,error)->null).get(15, TimeUnit.SECONDS);
    }
    private static void stopLease(Lease old) {
        old.close(); Process process = old.process;
        try {
            if (process != null && !process.waitFor(6, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS); }
            if (Files.isRegularFile(old.stateFile)) helperCommand(12, "restore", old.stateFile.toString(), Long.toString(OWNER));
        } catch (Exception error) { failure(error); throw new CompletionException(error); }
    }
    private static void recoverPreviousLeases() throws Exception {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(stateDirectory(), "route-*.json")) {
            for (Path file : files) {
                if (Files.size(file) > 1_048_576) continue;
                try {
                    JsonObject json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                    // Completed journals are history, not pending work. Starting a helper for every
                    // completed lease made reconnect progressively slower on long-lived installs.
                    if (bool(json, "restored", false) && "restored".equals(text(json, "status", ""))) continue;
                    long owner = json.has("ownerPid") ? json.get("ownerPid").getAsLong() : -1;
                    long ownerStart = json.has("ownerStart") ? json.get("ownerStart").getAsLong() : 0;
                    if (owner > 0 && owner != OWNER && sameOwner(owner, ownerStart)) continue;
                    helperCommand(12, "restore", file.toString(), Long.toString(OWNER));
                } catch (RuntimeException ignored) { /* Never apply an unrecognized local state file. */ }
            }
        }
    }

    private static void installDriver(boolean remove) throws Exception {
        Path setup = packageInstaller();
        set("verifying", setup.toString()); verifyPackage(setup);
        Path snapshot = stateDirectory().resolve("defaults-" + UUID.randomUUID() + ".json");
        helperCommand(15, "snapshot", snapshot.toString());
        boolean defaultsRestored = false;
        try {
            set(remove ? "uninstalling" : "installing", setup.toString());
            String code = "$ErrorActionPreference='Stop';$result=@{};try{$p=Start-Process -FilePath " + quote(setup.toString())
                    + " -WorkingDirectory " + quote(setup.getParent().toString()) + " -ArgumentList @('" + (remove ? "-u" : "-i")
                    + "','-h') -Verb RunAs -WindowStyle Hidden -Wait -PassThru;$result=@{exitCode=$p.ExitCode;cancelled=$false}}"
                    + "catch{$result=@{exitCode=-1;cancelled=($_.Exception.NativeErrorCode -eq 1223)}}"
                    + "finally{try{$r=Start-Process -FilePath " + quote(helper().toString())
                    + " -ArgumentList " + quote("restore-defaults \"" + snapshot + "\"")
                    + " -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput " + quote(snapshot + ".restore.json")
                    + " -RedirectStandardError " + quote(snapshot + ".restore.err")
                    + ";$result.defaultsRestored=($r.ExitCode -eq 0)}catch{$result.defaultsRestored=$false}};$result|ConvertTo-Json -Compress";
            Process powershell = powershell(code).start(); installerProcess = powershell;
            JsonObject result = readProcess(powershell, 600, false);
            if (bool(result, "cancelled", false)) { set("administratorCancelled", ""); return; }
            int exitCode = result.has("exitCode") ? result.get("exitCode").getAsInt() : -1;
            if (exitCode < 0) throw new EnvironmentFailure("failed", "Driver installer did not start");
            // Device detection, not undocumented installer exit values, determines success.
            Detection after = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                after = detect(false);
                if (remove ? !after.installed : after.ready) break;
                TimeUnit.SECONDS.sleep(1);
            }
            defaultsRestored = restoreDefaults(snapshot);
            if (!defaultsRestored) { set("defaultsRestoreFailed", snapshot.toString()); return; }
            after = detect(false);
            if (remove) set(after != null && !after.installed ? "driverRemoved" : "restartRequired", "Installer exit: " + exitCode);
            else if (after != null && after.ready) publishDetection(after);
            else set("restartRequired", "Installer exit: " + exitCode);
        } finally {
            // The PowerShell wrapper also restores after UAC completion if Minecraft exits meanwhile.
            if (!defaultsRestored) try { if (!restoreDefaults(snapshot)) set("defaultsRestoreFailed", snapshot.toString()); } catch (Exception error) { set("defaultsRestoreFailed", snapshot.toString()); }
            Process process = installerProcess; if (process == null || !process.isAlive()) installerProcess = null;
        }
    }
    private static boolean restoreDefaults(Path snapshot) throws Exception {
        JsonObject before = JsonParser.parseString(Files.readString(snapshot)).getAsJsonObject();
        JsonObject after = helperCommand(15, "restore-defaults", snapshot.toString());
        if (!before.has("defaults") || !after.has("defaults")) return false;
        Map<String, String> restored = new HashMap<>();
        for (JsonElement element : after.getAsJsonArray("defaults")) {
            JsonObject item = element.getAsJsonObject(); restored.put(text(item, "flow", "") + ":" + text(item, "role", ""), text(item, "id", ""));
        }
        for (JsonElement element : before.getAsJsonArray("defaults")) {
            JsonObject item = element.getAsJsonObject(); String id = text(item, "id", "");
            if (!id.isEmpty() && !id.equals(restored.get(text(item, "flow", "") + ":" + text(item, "role", "")))) return false;
        }
        return true;
    }

    private static Path packageInstaller() throws Exception {
        allowed(); Path downloads = downloadDirectory(); Files.createDirectories(downloads);
        Path contents = downloads.resolve("VBCABLE_Driver_Pack45"), setup = contents.resolve(System.getProperty("os.arch", "").contains("64") ? "VBCABLE_Setup_x64.exe" : "VBCABLE_Setup.exe");
        Path complete = contents.resolve("package.complete");
        if (Files.isRegularFile(setup) && Files.isRegularFile(complete)) return setup;
        set("downloading", downloads.toString());
        Path archive = downloads.resolve("VBCABLE_Driver_Pack45.zip"), pending = downloads.resolve("VBCABLE_Driver_Pack45.zip.pending");
        if (!Files.isRegularFile(archive)) {
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build()) {
                HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(PACKAGE).timeout(Duration.ofMinutes(3)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(pending)) {
                    if (response.statusCode() != 200) throw new EnvironmentFailure("downloadFailed", "HTTP " + response.statusCode());
                    long total = 0; byte[] bytes = new byte[32_768]; int count;
                    while ((count = input.read(bytes)) >= 0) { allowed(); total += count; if (total > 32L * 1024 * 1024) throw new EnvironmentFailure("downloadFailed", "Package too large"); output.write(bytes, 0, count); }
                }
                Files.move(pending, archive, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception error) { Files.deleteIfExists(pending); if (error instanceof EnvironmentFailure known) throw known; throw new EnvironmentFailure("downloadFailed", safe(error)); }
        }
        Files.createDirectories(contents); long total = 0; int entries = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry; byte[] bytes = new byte[32_768];
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > 1024) throw new IOException("Too many package files");
                Path target = contents.resolve(entry.getName()).normalize(); if (!target.startsWith(contents)) throw new IOException("Invalid package path");
                if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                Files.createDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(target)) {
                    int count; while ((count = input.read(bytes)) >= 0) { total += count; if (total > 128L * 1024 * 1024) throw new IOException("Expanded package too large"); output.write(bytes, 0, count); }
                }
            }
        }
        if (!Files.isRegularFile(setup)) throw new EnvironmentFailure("downloadFailed", "Official installer missing");
        Files.writeString(complete, PACKAGE.toString(), StandardCharsets.UTF_8);
        return setup;
    }
    private static void verifyPackage(Path setup) throws Exception {
        String script = "$ErrorActionPreference='Stop';$ok=$true;$s=Get-AuthenticodeSignature -LiteralPath " + quote(setup.toString())
                + ";if($s.Status -ne 'Valid' -or $s.SignerCertificate.Subject -notmatch '(?i)(burel.*vincent|vincent.*burel)'){$ok=$false};"
                + "$cats=Get-ChildItem -LiteralPath " + quote(setup.getParent().toString()) + " -Filter '*.cat';if($cats.Count -eq 0){$ok=$false};"
                + "foreach($c in $cats){$s=Get-AuthenticodeSignature -LiteralPath $c.FullName;if($s.Status -ne 'Valid' -or $s.SignerCertificate.Subject -notmatch '(?i)(burel.*vincent|vincent.*burel|Microsoft Windows Hardware Compatibility Publisher)'){$ok=$false}};@{ok=$ok}|ConvertTo-Json -Compress";
        JsonObject result = readProcess(powershell(script).start(), 45, true);
        if (!bool(result, "ok", false)) throw new EnvironmentFailure("signatureFailed", setup.toString());
    }

    private static Path stateDirectory() throws IOException { allowed(); Path path = directory().resolve("state"); Files.createDirectories(path); return path; }
    private static synchronized Path helper() throws Exception {
        allowed(); if (helper != null && Files.isRegularFile(helper)) return helper;
        byte[] bytes;
        try (InputStream input = SpatialAudioEnvironment.class.getResourceAsStream("/assets/cheatprogram/audio/audio-route.exe")) {
            if (input == null) throw new IOException("Audio routing helper missing"); bytes = input.readAllBytes();
        }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        Path runtime = directory().resolve("runtime"); Files.createDirectories(runtime); Path target = runtime.resolve("audio-route-" + hash + ".exe");
        if (!Files.isRegularFile(target) || !Arrays.equals(Files.readAllBytes(target), bytes)) Files.write(target, bytes);
        helper = target; return target;
    }
    private static JsonObject helperCommand(int timeout, String... args) throws Exception {
        List<String> command = new ArrayList<>(); command.add(helper().toString()); command.addAll(Arrays.asList(args));
        return readProcess(new ProcessBuilder(command).redirectErrorStream(true).start(), timeout, true);
    }
    private static ProcessBuilder powershell(String script) {
        script = "[Console]::OutputEncoding=[System.Text.UTF8Encoding]::new($false);$OutputEncoding=[Console]::OutputEncoding;" + script;
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded).redirectErrorStream(true);
    }
    private static JsonObject readProcess(Process process, int timeout, boolean killOnTimeout) throws Exception {
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> { try (InputStream input = process.getInputStream()) { return input.readNBytes(1_048_577); } catch (IOException error) { return new byte[0]; } }, IO);
        if (!process.waitFor(timeout, TimeUnit.SECONDS)) { if (killOnTimeout) process.destroyForcibly(); throw new IOException("Audio environment operation timed out"); }
        byte[] bytes = output.get(3, TimeUnit.SECONDS); if (bytes.length > 1_048_576) throw new IOException("Oversize helper response");
        String content = new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", "");
        if (process.exitValue() != 0) throw new IOException("Audio helper exit " + process.exitValue() + ": " + content.substring(0, Math.min(400, content.length())).replace('\r', ' ').replace('\n', ' '));
        for (String line : content.lines().toList().reversed()) {
            try { JsonElement json = JsonParser.parseString(line); if (json.isJsonObject()) return json.getAsJsonObject(); } catch (RuntimeException ignored) {}
        }
        throw new IOException("Audio helper exit " + process.exitValue());
    }
    private static void allowed() throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) throw new EnvironmentFailure("unsupported", "Windows required");
        if (!Config.persistenceAllowed()) throw new IOException("Restart required after configuration reset");
    }
    private static boolean validPid(int pid) {
        if (pid <= 4 || pid == OWNER || !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) return false;
        ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
        while (parent != null) { if (parent.pid() == pid) return false; parent = parent.parent().orElse(null); }
        return true;
    }
    private static boolean sameOwner(long pid, long startedTicks) {
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).flatMap(process -> process.info().startInstant())
                .map(start -> {
                    long ticks = 621355968000000000L + start.getEpochSecond() * 10_000_000L + start.getNano() / 100;
                    return Math.abs(ticks - startedTicks) < 10_000;
                }).orElse(false);
    }
    private static String quote(String text) { return "'" + text.replace("'", "''") + "'"; }
    private static boolean bool(JsonObject object, String key, boolean fallback) { try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; } catch (RuntimeException error) { return fallback; } }
    private static int count(JsonObject object, String key) { try { return Math.max(0, object.get(key).getAsInt()); } catch (RuntimeException error) { return 0; } }
    private static String text(JsonObject object, String key, String fallback) { try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : fallback; } catch (RuntimeException error) { return fallback; } }
    private static void drain(InputStream input) { try (input) { input.transferTo(OutputStream.nullOutputStream()); } catch (IOException ignored) {} }
    private static String safe(Throwable error) { String message = error == null ? "" : error.getMessage(); if (message == null) message = error.getClass().getSimpleName(); return message.replace('\r', ' ').replace('\n', ' ').substring(0, Math.min(300, message.length())); }
    private static void failure(Throwable error) { set(error instanceof EnvironmentFailure known ? known.status : "failed", safe(error)); }
    private static void set(String status, String details) {
        State old = state; Lease current = lease; boolean routed = current != null && routedFor(current.pid);
        state = new State(PREFIX + status, busy(), old.driverInstalled, old.ready, routed, current == null ? 0 : current.pid, old.playbackDevice, details == null ? "" : details);
    }
    private static final class EnvironmentFailure extends IOException { final String status; EnvironmentFailure(String status, String detail) { super(detail); this.status = status; } }
    private static final class Lease {
        final int pid; final long generation; final Path stateFile;
        final AtomicBoolean closing = new AtomicBoolean();
        volatile Process process; volatile boolean cancelled, routed; volatile BufferedWriter writer; volatile ScheduledFuture<?> heartbeat;
        final long createdAt = System.nanoTime();
        volatile String reportStatus = "routing", reportDetails = "";
        volatile long lastReport; volatile int activeSessions, isolatedSessions;
        Lease(int pid, long generation, Path stateFile) { this.pid = pid; this.generation = generation; this.stateFile = stateFile; }
        synchronized void send(String command) throws IOException { if (writer != null) { writer.write(command); writer.newLine(); writer.flush(); } }
        void close() {
            if (!closing.compareAndSet(false, true)) return; cancelled = true; routed = false;
            ScheduledFuture<?> task = heartbeat; if (task != null) task.cancel(false);
            // releaseRoute is called from the client thread. Pipe writes and flushes belong on IO.
            IO.execute(() -> { synchronized (this) { try { send("Q"); if (writer != null) writer.close(); } catch (IOException ignored) {} } });
        }
    }
}
