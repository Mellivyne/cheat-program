package com.cheatprogram.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Crypt32Util;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Windows-current-user DPAPI storage, kept completely outside profiles and share codes. */
public final class CredentialStore {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int UI_FORBIDDEN = 1;
    private static final Map<Path, CredentialStore> STORES = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Cheat Program encrypted credentials"); thread.setDaemon(true); return thread;
    });
    static {
        java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> flushAll(2000), "Cheat Program credential flush"));
    }
    private record Entry(String endpoint, String key) { }
    private final Path file;
    private final Map<String, Entry> entries = new HashMap<>();
    private long revision;
    private volatile String status = "ui.ai.credential.noKey";

    public static CredentialStore open(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        return STORES.computeIfAbsent(absolute, CredentialStore::new);
    }
    private CredentialStore(Path file) {
        this.file = file;
        if (!Platform.isWindows()) { status = "ui.ai.credential.unsupported"; return; }
        byte[] plain = null;
        try {
            if (!Files.isRegularFile(file)) return;
            if (Files.size(file) > MAX_BYTES) throw new IllegalArgumentException();
            plain = Crypt32Util.cryptUnprotectData(Files.readAllBytes(file), UI_FORBIDDEN);
            if (plain.length > MAX_BYTES) throw new IllegalArgumentException();
            JsonObject root = JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException();
            for (Map.Entry<String, JsonElement> value : root.getAsJsonObject("entries").entrySet()) {
                JsonObject item = value.getValue().getAsJsonObject();
                String endpoint = item.get("endpoint").getAsString(), key = item.get("key").getAsString();
                if (value.getKey().length() > 128 || endpoint.length() > 2048 || key.length() > 4096) continue;
                if (!key.isBlank()) entries.put(value.getKey(), new Entry(endpoint, key));
            }
            status = "ui.ai.credential.saved";
        } catch (Exception | LinkageError ignored) {
            // Never log native exceptions or decrypted input; a different Windows account cannot decrypt this file.
            entries.clear();
            status = "ui.ai.credential.failed";
        } finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }
    public synchronized String read(String slot, String endpoint) {
        if(!com.cheatprogram.core.Config.persistenceAllowed())return "";
        Entry value = entries.get(slot);
        return value != null && value.endpoint.equals(endpointIdentity(endpoint)) ? value.key : "";
    }
    public synchronized void put(String slot, String endpoint, String key) {
        if(!com.cheatprogram.core.Config.persistenceAllowed())return;
        if (key == null || key.isBlank()) { if (entries.containsKey(slot)) delete(slot); return; }
        Entry next = new Entry(endpointIdentity(endpoint), key);
        if (next.equals(entries.get(slot))) { if (status.equals("ui.ai.credential.failed")) changed(true); return; }
        entries.put(slot, next); changed(false);
    }
    public synchronized void delete(String slot) {
        if(!com.cheatprogram.core.Config.persistenceAllowed())return;
        entries.remove(slot);
        // Even an apparently empty slot requests a fresh write: this also retries a failed earlier deletion.
        changed(true);
    }
    private void changed(boolean immediate) {
        long expected = ++revision;
        status = Platform.isWindows() ? "ui.ai.credential.saving" : "ui.ai.credential.unsupported";
        WRITER.schedule(() -> persist(expected), immediate ? 0 : 350, TimeUnit.MILLISECONDS);
    }
    private synchronized void persist(long expected) {
        synchronized(com.cheatprogram.core.Config.persistenceLock()) {
        // A queued save from before deletion or provider change can never resurrect an old credential.
        if (expected != revision || !Platform.isWindows() || !com.cheatprogram.core.Config.persistenceAllowed()) return;
        Path temporary = file.resolveSibling(file.getFileName() + ".pending");
        byte[] plain = null, encrypted = null;
        try {
            if (entries.isEmpty()) {
                Files.deleteIfExists(temporary); Files.deleteIfExists(file);
                status = "ui.ai.credential.noKey"; return;
            }
            JsonObject root = new JsonObject(), content = new JsonObject(); root.addProperty("version", 1);
            for (Map.Entry<String, Entry> value : entries.entrySet()) {
                JsonObject item = new JsonObject(); item.addProperty("endpoint", value.getValue().endpoint);
                item.addProperty("key", value.getValue().key); content.add(value.getKey(), item);
            }
            root.add("entries", content);
            plain = root.toString().getBytes(StandardCharsets.UTF_8);
            if (plain.length > MAX_BYTES) throw new IllegalArgumentException();
            encrypted = Crypt32Util.cryptProtectData(plain, UI_FORBIDDEN);
            Files.createDirectories(file.getParent()); Files.write(temporary, encrypted);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            status = "ui.ai.credential.saved";
        } catch (Exception | LinkageError ignored) { status = "ui.ai.credential.failed"; }
        finally {
            if (plain != null) Arrays.fill(plain, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
        }
        }
    }
    /** Invalidates pending jobs without touching the encrypted file that is about to be recycled. */
    public static void clearMemoryForInitialization(){
        for(CredentialStore store:STORES.values())synchronized(store){store.revision++;store.entries.clear();store.status="ui.ai.credential.noKey";}
    }
    public synchronized String statusKey(String slot) {
        if (status.equals("ui.ai.credential.saved") && !entries.containsKey(slot)) return "ui.ai.credential.noKey";
        return status;
    }
    public static boolean sameEndpoint(String first, String second) { return endpointIdentity(first).equals(endpointIdentity(second)); }
    private static String endpointIdentity(String value) {
        String text = value == null ? "" : value.strip();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        try {
            URI uri = URI.create(text);
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT), host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort(); if (port == (scheme.equals("https") ? 443 : 80)) port = -1;
            return new URI(scheme, null, host, port, uri.getPath(), uri.getQuery(), null).toString();
        } catch (Exception ignored) { return text; }
    }
    /** Force the latest in-memory state onto the serial writer, with a bounded shutdown wait. */
    public static void flushAll(long timeoutMillis) {
        try {
            WRITER.submit(() -> {
                for (CredentialStore store : STORES.values()) synchronized (store) { store.persist(store.revision); }
            }).get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) { }
    }
}
