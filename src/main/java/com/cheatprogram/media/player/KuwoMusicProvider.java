package com.cheatprogram.media.player;

import com.cheatprogram.ai.CredentialStore;
import com.cheatprogram.core.Config;
import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.player.MusicModels.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Direct HTTPS Kuwo web client. Endpoint reference: Lin-M11/MusicUnlock/KuwoApi.kt;
 * session validation is independently checked with the platform's /api/user/checkLogin.
 * No browser cookie extraction, public proxy, account inference from public lists, or song matching.
 */
public final class KuwoMusicProvider implements MusicProvider {
    private static final String HOME = "https://www.kuwo.cn/", SLOT = "music.kuwo.cookie";
    private static final String ANTI_COOKIE = "Hm_Iuvt_cdb524f42f23cer9b268564v7y735ewrq2324";
    private static final Set<String> COOKIE_KEYS = Set.of("userid", "t3kwid", "uid", "kwid", "loginUid", "sid", "websid", "loginSid",
            "JSESSIONID", "kw_token", ANTI_COOKIE);
    private static final int PAGE_SIZE = 200, MAX_LISTS = 5000, MAX_TRACKS = 20000, MAX_JSON = 8 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final CredentialStore credentials = CredentialStore.open(Config.directory().resolve("music/credentials.dpapi"));
    private final Map<String, String> cookies = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> playlistTypes = new java.util.concurrent.ConcurrentHashMap<>();
    private Account currentAccount;
    private String notice = "ui.musicPlayer.cookieRequired";

    public KuwoMusicProvider() {
        MusicNetwork.clearCookies(provider());
        String saved = credentials.read(SLOT, HOME);
        if (!saved.isBlank()) try { cookies.putAll(parseCookies(saved)); } catch (IOException invalid) { }
    }
    @Override public Provider provider() { return Provider.KUWO; }
    @Override public String requirement() { return notice; }

    /** Called only for a Cookie explicitly supplied by the user; no cookie or account value is logged. */
    public Account importCookie(String input) throws Exception {
        Map<String, String> candidate = parseCookies(input);
        Account confirmed = verifyAccount(candidate);
        String header = cookieHeader(candidate);
        if (header.length() > 4096) throw failure("credentialTooLarge");
        cookies.clear(); cookies.putAll(candidate); playlistTypes.clear(); currentAccount = confirmed;
        credentials.put(SLOT, HOME, header); notice = "ui.musicPlayer.compatibility";
        MusicNetwork.clearCookies(provider());
        return confirmed;
    }
    @Override public Login beginLogin() throws IOException { throw failure("cookieRequired"); }
    @Override public Login pollLogin(Login login) throws IOException { throw failure("cookieRequired"); }
    @Override public Account account() throws Exception {
        if (cookies.isEmpty()) return null;
        if (currentAccount == null) currentAccount = verifyAccount(cookies);
        notice = "ui.musicPlayer.compatibility";
        return currentAccount;
    }
    private Account verifyAccount(Map<String, String> candidate) throws Exception {
        String uid = first(candidate, "userid", "t3kwid", "uid", "kwid", "loginUid");
        String rawSid = first(candidate, "sid", "websid", "loginSid");
        if (!validId(uid) || rawSid.isBlank() || rawSid.equals("0")) throw failure("loginRequired");
        String sid = rawSid.split("_", 2)[0];
        if (sid.isBlank()) throw failure("loginRequired");
        Map<String, String> headers = headers(candidate, false);
        headers.put("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        headers.put("Referer", HOME + "www/user/register");
        JsonObject result = MusicNetwork.json(provider(), URI.create(HOME + "api/user/checkLogin"), "POST", headers,
                form("uid", uid, "sid", sid).getBytes(StandardCharsets.UTF_8), 256 * 1024);
        // This endpoint validates the exact UID/SID pair; a public getuserlist result is never accepted as login proof.
        if (number(result, "status", -1) != 200) throw failure("loginRequired");
        JsonObject data = child(result, "data");
        String returnedUid = first(data, "userid", "uid");
        if (!returnedUid.isBlank() && !returnedUid.equals(uid)) throw failure("protocolChanged");
        return new Account(uid, first(data, "nickname", "uname", "username"), artwork(first(data, "pic", "avatar", "userpic")));
    }

    @Override public List<Playlist> playlists() throws Exception {
        requireAccount(); notice = "ui.musicPlayer.compatibility"; playlistTypes.clear();
        Map<String, Playlist> result = new LinkedHashMap<>();
        String previous = ""; int physical = 0;
        for (int page = 0; page <= MAX_LISTS / PAGE_SIZE; page++) {
            interrupted();
            JsonObject root = legacy("getuserlist", page, "uid", currentAccount.id(), "userid", currentAccount.id());
            requireLegacy(root);
            if (!root.has("plist") || !root.get("plist").isJsonArray()) throw failure("protocolChanged");
            JsonArray list = root.getAsJsonArray("plist");
            StringBuilder pageIds = new StringBuilder();
            for (JsonElement value : list) {
                if (!value.isJsonObject()) { partialNotice(); continue; }
                JsonObject item = value.getAsJsonObject();
                String id = text(item, "id"), type = text(item, "type").toUpperCase(Locale.ROOT);
                pageIds.append(type).append(':').append(id).append(',');
                if (!type.equals("GENERAL") && !type.equals("MYFAVORITE")) continue;
                if (!validPlaylistId(id)) { partialNotice(); continue; }
                if (result.size() >= MAX_LISTS && !result.containsKey(id)) { limitNotice(); return List.copyOf(result.values()); }
                playlistTypes.put(id, type);
                result.put(id, new Playlist(id, text(item, "title"), artwork(text(item, "pic")), (int) Math.max(0, Math.min(Integer.MAX_VALUE, number(item, "musicnum", 0)))));
            }
            physical += list.size();
            boolean more = root.has("total") ? number(root, "total", physical) > physical : list.size() == PAGE_SIZE;
            if (!more) return List.copyOf(result.values());
            if (list.isEmpty() || pageIds.toString().equals(previous)) { partialNotice(); return List.copyOf(result.values()); }
            previous = pageIds.toString();
        }
        limitNotice(); return List.copyOf(result.values());
    }
    @Override public List<Track> liked() throws Exception {
        List<Playlist> lists = playlists();
        Playlist favorite = null;
        for (Playlist list : lists) if ("MYFAVORITE".equals(playlistTypes.get(list.id()))) {
            if (favorite != null) throw failure("protocolChanged");
            favorite = list;
        }
        if (favorite == null) throw failure("unavailable");
        String libraryNotice = notice;
        List<Track> tracks = tracks(favorite);
        if (!libraryNotice.endsWith("compatibility")) notice = libraryNotice;
        return tracks;
    }
    @Override public List<Track> tracks(Playlist playlist) throws Exception {
        if (playlist == null || !validPlaylistId(playlist.id())) throw failure("invalidTrack");
        requireAccount(); notice = "ui.musicPlayer.compatibility";
        List<Track> tracks = new ArrayList<>(); String previous = ""; int physical = 0;
        for (int page = 0; page <= MAX_TRACKS / PAGE_SIZE; page++) {
            interrupted();
            JsonObject root = legacy("getlistinfo", page, "pid", playlist.id()); requireLegacy(root);
            if (root.has("id") && !playlist.id().equals(text(root, "id"))) throw failure("protocolChanged");
            if (!root.has("musiclist") || !root.get("musiclist").isJsonArray()) throw failure("protocolChanged");
            JsonArray list = root.getAsJsonArray("musiclist"); StringBuilder pageIds = new StringBuilder();
            for (JsonElement value : list) {
                Track track = value.isJsonObject() ? track(value.getAsJsonObject()) : null;
                if (track == null) { partialNotice(); continue; }
                pageIds.append(track.id()).append(',');
                if (tracks.size() >= MAX_TRACKS) { limitNotice(); return List.copyOf(tracks); }
                tracks.add(track);
            }
            physical += list.size();
            long total = number(root, "total", number(root, "validtotal", playlist.count()));
            boolean more = total > physical || !root.has("total") && total == 0 && list.size() == PAGE_SIZE;
            if (!more) return List.copyOf(tracks);
            if (list.isEmpty() || pageIds.isEmpty() || pageIds.toString().equals(previous)) throw failure("partialLibrary");
            previous = pageIds.toString();
        }
        limitNotice(); return List.copyOf(tracks);
    }
    private JsonObject legacy(String operation, int page, String... extra) throws Exception {
        List<String> pairs = new ArrayList<>(List.of("op", operation, "pn", Integer.toString(page), "rn", Integer.toString(PAGE_SIZE),
                "encode", "utf8", "keyset", "pl2012", "identity", "kuwo", "pcmp4", "1", "vipver", "MUSIC_9.0.5.0_W1", "newver", "1"));
        pairs.addAll(List.of(extra));
        pairs.addAll(List.of("loginUid", currentAccount.id(), "loginSid", first(cookies, "sid", "websid", "loginSid"),
                "sid", first(cookies, "sid", "websid", "loginSid")));
        URI uri = URI.create("https://nplserver.kuwo.cn/pl.svc?" + form(pairs.toArray(String[]::new)));
        return MusicNetwork.json(provider(), uri, "GET", headers(cookies, false), null, MAX_JSON);
    }

    @Override public LrcLyrics lyrics(Track track) throws Exception {
        requireTrack(track);
        URI uri = URI.create("https://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=" + track.id());
        // Original lyrics are public. Account cookies are unnecessary on the mobile lyric endpoint.
        JsonObject result = MusicNetwork.json(provider(), uri, "GET", headers(Map.of(), false), null, MAX_JSON);
        JsonObject data = child(result, "data");
        JsonArray lines = array(data, "lrclist");
        if (lines.isEmpty()) throw failure("originalLyricsUnavailable");
        String returnedId = first(child(data, "songinfo"), "rid", "id", "musicId");
        if (!returnedId.isBlank() && !track.id().equals(returnedId)) throw failure("protocolChanged");
        StringBuilder lrc = new StringBuilder(); int count = 0;
        for (JsonElement element : lines) {
            if (!element.isJsonObject()) continue;
            JsonObject line = element.getAsJsonObject();
            double seconds;
            try { seconds = Double.parseDouble(text(line, "time")); } catch (NumberFormatException invalid) { continue; }
            if (!Double.isFinite(seconds) || seconds < 0 || seconds > 604800 || ++count > 20000) continue;
            long millis = Math.round(seconds * 1000);
            lrc.append(String.format(Locale.ROOT, "[%02d:%02d.%03d]", millis / 60000, millis / 1000 % 60, millis % 1000))
                    .append(text(line, "lineLyric").replace('\r', ' ').replace('\n', ' ')).append('\n');
        }
        LrcLyrics lyrics = LrcLyrics.parse(lrc.toString());
        if (lyrics.isEmpty() || !lyrics.synced()) throw failure("originalLyricsUnavailable");
        return lyrics;
    }
    @Override public List<Quality> qualities(Track track) {
        return List.of(new Quality("128kmp3","高品 · 128 kbps"),
                new Quality("320kmp3","超品 · 320 kbps"),new Quality("flac","无损"));
    }
    @Override public Media media(Track track) throws Exception { return media(track, ""); }
    @Override public Media media(Track track,String quality) throws Exception {
        requireTrack(track);
        String requested=quality==null?"":quality;
        if(!requested.isEmpty()&&qualities(track).stream().noneMatch(q->q.id().equals(requested)))throw failure("qualityUnavailable");
        Map<String, String> requestCookies = new LinkedHashMap<>(cookies);
        requestCookies.putIfAbsent(ANTI_COOKIE, randomText(32)); requestCookies.putIfAbsent("kw_token", randomText(11));
        URI uri = URI.create(HOME + "api/v1/www/music/playUrl?" + form("mid", track.id(), "type", "music", "httpsStatus", "1",
                "plat", "web_www", "from", "", "br", requested.isEmpty()?"128kmp3":requested, "reqId", UUID.randomUUID().toString()));
        JsonObject root = MusicNetwork.json(provider(), uri, "GET", headers(requestCookies, true), null, MAX_JSON);
        if (number(root, "code", -1) != 200 || root.has("success") && text(root, "success").equals("false")) throw failure("rightsRestricted");
        JsonObject data = child(root, "data"); String value = text(data, "url");
        String returnedId = first(data, "rid", "mid");
        if (!returnedId.isBlank() && !track.id().equals(returnedId)) throw failure("protocolChanged");
        String url = asset(value);
        if (url.isBlank()) throw failure("rightsRestricted");
        String format = text(data, "format").toLowerCase(Locale.ROOT);
        if (format.isBlank()) format = URI.create(url).getPath().toLowerCase(Locale.ROOT).endsWith(".flac") ? "flac" : "mp3";
        return new Media(url, format,confirmedQuality(data,format));
    }
    /** A requested lossless stream may be downgraded; only returned format/bitrate proves its tier. */
    private static String confirmedQuality(JsonObject data,String format) {
        if(Set.of("flac","ape","wav").contains(format))return "flac";
        String rate=first(data,"br","bitrate","bit_rate").toLowerCase(Locale.ROOT);
        return switch(rate){case "128kmp3","128k","128","128000"->"128kmp3";
            case "320kmp3","320k","320","320000"->"320kmp3";default->"";};
    }
    private Track track(JsonObject raw) {
        String id = first(raw, "rid", "id"); if (id.startsWith("MUSIC_")) id = id.substring(6);
        String name = first(raw, "name", "FSONGNAME");
        if (!validId(id) || name.isBlank()) return null;
        long duration = Math.max(0, Math.min(604800000, number(raw, "duration", 0)));
        return new Track(provider(), id, name, first(raw, "artist", "FARTIST"), first(raw, "album", "FALBUM"),
                artwork(first(raw, "albumpic", "pic")), duration);
    }
    @Override public void logout() {
        cookies.clear(); playlistTypes.clear(); currentAccount = null; MusicNetwork.clearCookies(provider());
        credentials.delete(SLOT); notice = "ui.musicPlayer.cookieRequired";
    }
    private void requireAccount() throws Exception { if (account() == null) throw failure("loginRequired"); }
    private void requireTrack(Track track) throws IOException {
        if (track == null || track.provider() != provider() || !validId(track.id())) throw failure("invalidTrack");
    }
    private static Map<String, String> parseCookies(String input) throws IOException {
        if (input == null || input.isBlank() || input.length() > 32768 || input.indexOf('\r') >= 0 || input.indexOf('\n') >= 0) throw failure("loginRequired");
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : input.split(";")) {
            int eq = part.indexOf('='); if (eq <= 0) continue;
            String name = part.substring(0, eq).strip(), value = part.substring(eq + 1).strip();
            if (!COOKIE_KEYS.contains(name)) continue;
            if (!safeCookie(value) || values.containsKey(name)) throw failure("loginRequired");
            values.put(name, value);
        }
        if (cookieHeader(values).length() > 4096) throw failure("credentialTooLarge");
        return values;
    }
    private static Map<String, String> headers(Map<String, String> values, boolean secret) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36");
        headers.put("Accept", "application/json"); headers.put("Referer", HOME); headers.put("Origin", "https://www.kuwo.cn");
        if (!values.isEmpty()) headers.put("Cookie", cookieHeader(values));
        if (secret) { headers.put("Secret", secret(values.getOrDefault(ANTI_COOKIE, ""))); headers.put("csrf", values.getOrDefault("kw_token", "")); }
        return headers;
    }
    /** Published web request transform, also present in current h5s.kuwo.cn www scripts. */
    private static String secret(String value) {
        StringBuilder digits = new StringBuilder(); for (char c : ANTI_COOKIE.toCharArray()) digits.append((int) c);
        int step = digits.length() / 5; StringBuilder chosen = new StringBuilder();
        for (int i = 1; i <= 5; i++) if (i * step < digits.length()) chosen.append(digits.charAt(i * step));
        long random = RANDOM.nextInt(100000000); String folded = digits.toString() + random;
        while (folded.length() > 10) folded = jsNumber(jsInteger(folded.substring(0, 10)) + jsInteger(folded.substring(10)));
        double seed = Double.parseDouble(chosen.toString()), add = Math.ceil(ANTI_COOKIE.length() / 2.0), modulus = Integer.MAX_VALUE;
        double state = (seed * Double.parseDouble(folded) + add) % modulus;
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            output.append(String.format(Locale.ROOT, "%02x", value.charAt(i) ^ (int) Math.floor(state / modulus * 255)));
            state = (seed * state + add) % modulus;
        }
        output.append(String.format(Locale.ROOT, "%08x", random)); return output.toString();
    }
    private static double jsInteger(String input) {
        int count = 0; while (count < input.length() && input.charAt(count) >= '0' && input.charAt(count) <= '9') count++;
        return count == 0 ? 0 : Double.parseDouble(input.substring(0, count));
    }
    private static String jsNumber(double number) {
        if (number == 0) return "0";
        if (Math.abs(number) >= 1e21 || Math.abs(number) < 1e-6) return Double.toString(number).replace("E", "e+");
        return BigDecimal.valueOf(number).stripTrailingZeros().toPlainString();
    }
    private static String randomText(int length) {
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"; StringBuilder out = new StringBuilder();
        for (int i = 0; i < length; i++) out.append(alphabet.charAt(RANDOM.nextInt(alphabet.length()))); return out.toString();
    }
    private static String cookieHeader(Map<String, String> values) {
        StringBuilder out = new StringBuilder(); for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!out.isEmpty()) out.append("; "); out.append(entry.getKey()).append('=').append(entry.getValue());
        } return out.toString();
    }
    private static String asset(String input) {
        try {
            if (input.regionMatches(true, 0, "http:", 0, 5)) input = "https:" + input.substring(5);
            URI uri = URI.create(input); String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null && uri.getFragment() == null && uri.getPort() == -1
                    && (host.equals("kuwo.cn") || host.endsWith(".kuwo.cn") || host.equals("kuwo.com") || host.endsWith(".kuwo.com")) ? uri.toString() : "";
        } catch (IllegalArgumentException invalid) { return ""; }
    }
    private static String artwork(String input) { return asset(input); }
    private static String form(String... pairs) {
        StringBuilder out = new StringBuilder(); for (int i = 0; i < pairs.length; i += 2) {
            if (!out.isEmpty()) out.append('&'); out.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        } return out.toString();
    }
    private static boolean safeCookie(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) return false;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) <= 0x20 || value.charAt(i) >= 0x7f || value.charAt(i) == ';' || value.charAt(i) == ',') return false;
        return true;
    }
    private static boolean validPlaylistId(String value) { return value != null && (value.equals("0") || validId(value)); }
    private static boolean validId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(value) > 0; } catch (NumberFormatException invalid) { return false; }
    }
    private static String first(Map<String, String> values, String... names) {
        for (String name : names) { String value = values.getOrDefault(name, ""); if (!value.isBlank()) return value; } return "";
    }
    private static String first(JsonObject value, String... names) { for (String name : names) { String text = text(value, name); if (!text.isBlank()) return text; } return ""; }
    private static String text(JsonObject value, String name) { JsonElement item = value == null ? null : value.get(name); return item != null && item.isJsonPrimitive() ? item.getAsString() : ""; }
    private static long number(JsonObject value, String name, long fallback) { try { return Long.parseLong(text(value, name)); } catch (NumberFormatException invalid) { return fallback; } }
    private static JsonObject child(JsonObject value, String name) { JsonElement item = value == null ? null : value.get(name); return item != null && item.isJsonObject() ? item.getAsJsonObject() : new JsonObject(); }
    private static JsonArray array(JsonObject value, String name) { JsonElement item = value == null ? null : value.get(name); return item != null && item.isJsonArray() ? item.getAsJsonArray() : new JsonArray(); }
    private static void requireLegacy(JsonObject value) throws IOException { if (!text(value, "result").equals("ok")) throw failure("loginRequired"); }
    private void partialNotice() { if (!notice.endsWith("libraryLimit")) notice = "ui.musicPlayer.partialLibrary"; }
    private void limitNotice() { notice = "ui.musicPlayer.libraryLimit"; }
    private static void interrupted() throws InterruptedException { if (Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    private static IOException failure(String key) { return new IOException("ui.musicPlayer." + key); }
}
