package com.cheatprogram.media.player;

import com.cheatprogram.ai.CredentialStore;
import com.cheatprogram.core.Config;
import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.player.MusicModels.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Local compatibility client for NetEase's own web/desktop endpoints, not an approved developer SDK.
 * Protocol reference: github.com/NeteaseCloudMusicApiEnhanced/api-enhanced (module/ and util/crypto.js).
 * No API service, public proxy, song matching service or external QR renderer is involved.
 */
public final class NeteaseMusicProvider implements MusicProvider {
    private static final String HOME = "https://music.163.com";
    private static final String EAPI = "https://interfacepc.music.163.com";
    private static final int MAX_JSON = 8 * 1024 * 1024;
    private static final int PAGE_SIZE = 200, MAX_PLAYLISTS = 5000, MAX_TRACKS = 20000;
    private static final int SEARCH_SIZE = 30, MAX_SEARCH_PAGES = 100;
    private static final String[] TOKEN_NAMES = { "MUSIC_U", "__csrf", "NMTID" };
    // Platform level IDs, not a cross-service approximation. Rights are checked by the URL endpoint.
    private static final List<Quality> QUALITIES=List.of(new Quality("standard","标准"),new Quality("higher","较高"),
            new Quality("exhigh","极高"),new Quality("lossless","无损"),new Quality("hires","Hi-Res"),
            new Quality("jyeffect","高清臻音"),new Quality("sky","沉浸环绕声"),new Quality("jymaster","超清母带"));
    private final CredentialStore credentials = CredentialStore.open(Config.directory().resolve("music/credentials.dpapi"));
    private final Map<String, String> cookies = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Track> metadata = java.util.Collections.synchronizedMap(new LinkedHashMap<>(256,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Track> entry){return size()>10000;}
    });
    private volatile String favoritePlaylist="";
    private final String deviceId = UUID.randomUUID().toString().replace("-", "");
    private Account currentAccount;
    private String loginKey = "";
    private String notice = "ui.musicPlayer.compatibility";

    public NeteaseMusicProvider() {
        MusicNetwork.clearCookies(provider());
        for (String name : TOKEN_NAMES) {
            String token = credentials.read(slot(name), HOME);
            if (safeCookie(token)) cookies.put(name, token);
        }
    }

    @Override public Provider provider() { return Provider.NETEASE; }
    @Override public String requirement() { return notice; }
    @Override public boolean supportsSearch() { return true; }
    @Override public boolean supportsRadio() { return true; }
    @Override public boolean supportsTranslatedLyrics() { return true; }

    @Override public SearchPage search(String query,int page) throws Exception {
        resetNotice();
        if(query==null||query.isBlank()||query.strip().length()>200||query.chars().anyMatch(Character::isISOControl)
                ||page<0||page>=MAX_SEARCH_PAGES)throw failure("invalidSearch");
        JsonObject response=request("/api/cloudsearch/pc",object("s",query.strip(),"type",1,
                "limit",SEARCH_SIZE,"offset",page*SEARCH_SIZE,"total",true),false);
        requireSuccess(response);
        JsonObject result=child(response,"result");
        if(!result.has("songCount")||number(result,"songCount",-1)<0)throw failure("protocolChanged");
        long total=number(result,"songCount",0);
        if(result.has("songs")&&!result.get("songs").isJsonArray()||total>0&&!result.has("songs"))throw failure("protocolChanged");
        JsonArray songs=array(result,"songs");
        if(songs.size()>SEARCH_SIZE)throw failure("protocolChanged");
        List<Track> tracks=discoveryTracks(songs);
        // Pagination reflects the server's catalog count; rights remain enforced by media().
        boolean hasMore=!songs.isEmpty()&&(long)(page+1)*SEARCH_SIZE<total&&page+1<MAX_SEARCH_PAGES;
        return new SearchPage(tracks,page,hasMore);
    }

    @Override public List<Track> radio() throws Exception {
        resetNotice();requireAccount();
        JsonObject response=request("/api/v1/radio/get",new JsonObject(),true);
        requireSuccess(response);
        if(!response.has("data")||!response.get("data").isJsonArray())throw failure("protocolChanged");
        JsonArray songs=response.getAsJsonArray("data");
        if(songs.size()>SEARCH_SIZE)throw failure("protocolChanged");
        return discoveryTracks(songs);
    }

    private List<Track> discoveryTracks(JsonArray songs)throws IOException {
        Map<String,Track> tracks=new LinkedHashMap<>();
        for(JsonElement song:songs){
            Track track=song.isJsonObject()?track(song.getAsJsonObject()):null;
            if(track==null)throw failure("protocolChanged");
            tracks.putIfAbsent(track.id(),track);metadata.put(track.id(),track);
        }
        return List.copyOf(tracks.values());
    }

    @Override public Login beginLogin() throws Exception {
        // A newly requested QR must never carry a previous account into another account's login.
        cookies.clear(); currentAccount = null; loginKey = "";metadata.clear();favoritePlaylist="";
        MusicNetwork.clearCookies(provider());
        JsonObject result = request("/api/login/qrcode/unikey", object("type", 3), false);
        requireSuccess(result);
        String key = text(result, "unikey");
        if (key.isBlank() || key.length() > 512 || !key.matches("[A-Za-z0-9_-]+")) throw failure("protocolChanged");
        loginKey = key;
        String url = HOME + "/login?codekey=" + encode(key);
        return new Login(key, url, MusicQr.png(url), "ui.musicPlayer.qrWaiting", false);
    }

    @Override public Login pollLogin(Login login) throws Exception {
        if (login == null || loginKey.isBlank() || !loginKey.equals(login.key())) throw failure("qrExpired");
        JsonObject result = request("/api/login/qrcode/client/login", object("key", loginKey, "type", 3), false);
        int code = integer(result, "code", -1);
        String status = switch (code) {
            case 800 -> "ui.musicPlayer.qrExpired";
            case 801 -> "ui.musicPlayer.qrWaiting";
            case 802 -> "ui.musicPlayer.qrConfirm";
            case 803 -> "ui.musicPlayer.connected";
            default -> throw failure("protocolChanged");
        };
        if (code == 800) loginKey = "";
        if (code == 803) {
            if (!hasSession()) throw failure("protocolChanged");
            for (String name : TOKEN_NAMES) credentials.put(slot(name), HOME, cookies.getOrDefault(name, ""));
            currentAccount = null;
            loginKey = "";
        }
        return new Login(login.key(), login.url(), login.qrPng(), status, code == 803);
    }

    @Override public Account account() throws Exception {
        if (!hasSession()) return null;
        if (currentAccount != null) return currentAccount;
        JsonObject result = request("/api/w/nuser/account/get", new JsonObject(), true);
        requireSuccess(result);
        JsonObject profile = child(result, "profile");
        String id = text(profile, "userId");
        if (!validId(id)) {
            // A successful HTTP response can still mean the saved session has expired.
            if (child(result, "account").size() == 0) throw failure("loginRequired");
            throw failure("protocolChanged");
        }
        currentAccount = new Account(id, text(profile, "nickname"), text(profile, "avatarUrl"));
        return currentAccount;
    }

    @Override public List<Track> liked() throws Exception {
        resetNotice();
        Account account = requireAccount();
        if(favoritePlaylist.isBlank())try{discoverFavorite(account);}catch(IOException rejected){
            if(!List.of("ui.musicPlayer.unavailable","ui.musicPlayer.protocolChanged").contains(rejected.getMessage()))throw rejected;
        }
        if(!favoritePlaylist.isBlank())return loadPlaylist(new Playlist(favoritePlaylist,"","",0),true);
        JsonObject result = request("/api/song/like/get", object("uid", account.id()), false);
        requireSuccess(result);
        if (!result.has("ids") || !result.get("ids").isJsonArray()) throw failure("protocolChanged");
        return details(ids(result.getAsJsonArray("ids"), false));
    }
    private void discoverFavorite(Account account)throws Exception{
        for(int offset=0;offset<MAX_PLAYLISTS;offset+=PAGE_SIZE){
            JsonObject result=request("/api/user/playlist",object("uid",account.id(),"limit",PAGE_SIZE,"offset",offset,"includeVideo",false),true);requireSuccess(result);
            JsonArray rows=array(result,"playlist");
            for(JsonElement item:rows)if(item.isJsonObject()){
                JsonObject row=item.getAsJsonObject();String owner=text(child(row,"creator"),"userId");
                if(integer(row,"specialType",0)==5&&owner.equals(account.id())&&validId(text(row,"id"))){favoritePlaylist=text(row,"id");return;}
            }
            if(rows.size()<PAGE_SIZE||result.has("more")&&!truth(result,"more"))return;
        }
    }

    @Override public List<Playlist> playlists() throws Exception {
        resetNotice();
        Account account = requireAccount();
        Map<String, Playlist> all = new LinkedHashMap<>();
        int offset = 0;
        for (int page = 0; page <= MAX_PLAYLISTS / PAGE_SIZE; page++) {
            interrupted();
            JsonObject result = request("/api/user/playlist", object("uid", account.id(), "limit", PAGE_SIZE,
                    "offset", offset, "includeVideo", false), true);
            requireSuccess(result);
            if (!result.has("playlist") || !result.get("playlist").isJsonArray()) throw failure("protocolChanged");
            JsonArray list = result.getAsJsonArray("playlist");
            int before = all.size();
            for (JsonElement item : list) {
                if (!item.isJsonObject()) continue;
                JsonObject value = item.getAsJsonObject();
                String id = text(value, "id");
                if (!validId(id)) continue;
                if (all.size() == MAX_PLAYLISTS && !all.containsKey(id)) {
                    limitNotice(); return List.copyOf(all.values());
                }
                all.put(id, new Playlist(id, text(value, "name"), text(value, "coverImgUrl"),
                        Math.max(0, integer(value, "trackCount", 0))));
            }
            offset += list.size();
            boolean more = result.has("more") ? truth(result, "more") : list.size() == PAGE_SIZE;
            if (!more) return List.copyOf(all.values());
            if (list.isEmpty() || all.size() == before) throw failure("partialLibrary");
            if (all.size() >= MAX_PLAYLISTS) { limitNotice(); return List.copyOf(all.values()); }
        }
        limitNotice(); return List.copyOf(all.values());
    }

    @Override public List<Track> tracks(Playlist playlist) throws Exception {
        return loadPlaylist(playlist,false);
    }
    private List<Track> loadPlaylist(Playlist playlist,boolean favorites) throws Exception {
        resetNotice();
        if (playlist == null || !validId(playlist.id())) throw failure("invalidTrack");
        JsonObject result = request("/api/v6/playlist/detail", object("id", playlist.id(), "n", MAX_TRACKS + 1, "s", 0), false);
        requireSuccess(result);
        JsonObject data = child(result, "playlist");
        if (!playlist.id().equals(text(data, "id"))) throw failure("protocolChanged");
        for(JsonElement song:array(data,"tracks"))if(song.isJsonObject()){Track track=track(song.getAsJsonObject());if(track!=null)metadata.put(track.id(),track);}
        if (data.has("trackIds") && data.get("trackIds").isJsonArray()) {
            List<String> ids = favorites?favoriteIds(data.getAsJsonArray("trackIds")):ids(data.getAsJsonArray("trackIds"), true);
            if (integer(data, "trackCount", ids.size()) > ids.size() && !notice.endsWith("libraryLimit")) partialNotice();
            return details(ids);
        }
        // Some restricted playlists expose only their available tracks; expose that omission explicitly.
        JsonArray songs = array(data, "tracks");
        List<Track> resultTracks = new ArrayList<>();
        for (JsonElement song : songs) {
            if (resultTracks.size() >= MAX_TRACKS) { limitNotice(); break; }
            Track track = song.isJsonObject() ? track(song.getAsJsonObject()) : null;
            if (track != null) resultTracks.add(track);
        }
        if (integer(data, "trackCount", playlist.count()) > resultTracks.size()) partialNotice();
        return List.copyOf(resultTracks);
    }
    /** Stable newest-save-first order. Missing timestamps retain the platform's canonical list order. */
    private List<String> favoriteIds(JsonArray list)throws Exception{
        List<JsonObject> rows=new ArrayList<>();boolean complete=true;
        for(JsonElement element:list){if(!element.isJsonObject()){complete=false;continue;}JsonObject row=element.getAsJsonObject();rows.add(row);if(number(row,"at",0)<=0)complete=false;}
        if(!complete)return ids(list,true);
        rows.sort(java.util.Comparator.comparingLong((JsonObject row)->number(row,"at",0)).reversed());
        JsonArray sorted=new JsonArray();rows.forEach(sorted::add);return ids(sorted,true);
    }

    @Override public LrcLyrics lyrics(Track track) throws Exception {
        requireTrack(track);
        JsonObject result;
        try {
            result=request("/api/song/lyric/v1",object("id",track.id(),"cp",false,"tv",0,"lv",0,
                    "rv",0,"kv",0,"yv",0,"ytv",0,"yrv",0),false);
            requireSuccess(result);
        }catch(IOException problem){
            if(!List.of("ui.musicPlayer.unavailable","ui.musicPlayer.protocolChanged").contains(problem.getMessage()))throw problem;
            return originalLyrics(lyricResponse(track));
        }
        validateLyricId(track,result);
        String words=text(child(result,"yrc"),"lyric");
        if(!words.isBlank())try{
            LrcLyrics precise=LrcLyrics.parse(words);
            if(precise.synced()&&precise.lines().stream().anyMatch(line->!line.words().isEmpty()))return precise;
        }catch(IllegalArgumentException malformed){/* Use only the same exact recording's native line lyric. */}
        if(text(child(result,"lrc"),"lyric").isBlank())result=lyricResponse(track);
        return originalLyrics(result);
    }

    private static LrcLyrics originalLyrics(JsonObject result)throws IOException {
        String original=text(child(result,"lrc"),"lyric");
        if(original.isBlank())throw failure("originalLyricsUnavailable");
        // Original and translated lyrics remain separate until the player explicitly combines them.
        return LrcLyrics.parse(original);
    }

    @Override public LrcLyrics translatedLyrics(Track track) throws Exception {
        requireTrack(track);
        try{
            JsonObject modern=request("/api/song/lyric/v1",object("id",track.id(),"cp",false,"tv",0,"lv",0,"rv",0,"kv",0,"yv",0,"ytv",0,"yrv",0),false);
            requireSuccess(modern);validateLyricId(track,modern);
            // ytlrc uses the same newer timeline as YRC; older recordings may only have tlyric.
            for(String field:List.of("ytlrc","tlyric")){LrcLyrics translated=platformTranslation(modern,field);if(translated!=null)return translated;}
        }catch(IOException unavailable){if(!List.of("ui.musicPlayer.unavailable","ui.musicPlayer.protocolChanged").contains(unavailable.getMessage()))throw unavailable;}
        LrcLyrics translated=platformTranslation(lyricResponse(track),"tlyric");
        if(translated==null)throw failure("translatedLyricsUnavailable");return translated;
    }
    private static LrcLyrics platformTranslation(JsonObject result,String field){String text=text(child(result,field),"lyric");if(text.isBlank())return null;try{LrcLyrics lyrics=LrcLyrics.parse(text);return !lyrics.isEmpty()&&lyrics.synced()&&lyrics.lines().stream().anyMatch(line->!line.text().isBlank())?lyrics:null;}catch(IllegalArgumentException malformed){return null;}}

    private JsonObject lyricResponse(Track track)throws Exception {
        requireTrack(track);
        JsonObject result=request("/api/song/lyric",object("id",track.id(),"tv",-1,"lv",-1,
                "rv",-1,"kv",-1,"_nmclfl",1),false);
        requireSuccess(result);
        validateLyricId(track,result);
        return result;
    }

    private static void validateLyricId(Track track,JsonObject result)throws IOException {
        // Some responses include the requested ID. A mismatching ID must never be merged.
        if(result.has("songId")&&!track.id().equals(text(result,"songId"))
                ||result.has("id")&&!track.id().equals(text(result,"id")))throw failure("protocolChanged");
    }

    @Override public Media media(Track track) throws Exception {
        requireTrack(track);
        JsonObject result = request("/api/song/enhance/player/url", object("ids", "[" + track.id() + "]", "br", 320000), false);
        return mediaResult(track,result);
    }
    @Override public List<Quality> qualities(Track track){return track==null||track.provider()==provider()?QUALITIES:List.of();}
    @Override public Media media(Track track,String quality)throws Exception{
        if(quality==null||quality.isEmpty())return media(track);requireTrack(track);
        if(qualityLabel(quality).isEmpty())throw failure("qualityUnavailable");
        JsonObject params=object("ids","["+track.id()+"]","level",quality,"encodeType","flac");
        if(quality.equals("sky"))params.addProperty("immerseType","c51");
        return mediaResult(track,request("/api/song/enhance/player/url/v1",params,false));
    }
    private Media mediaResult(Track track,JsonObject result)throws Exception{
        requireSuccess(result);
        for (JsonElement item : array(result, "data")) {
            if (!item.isJsonObject()) continue;
            JsonObject value = item.getAsJsonObject();
            if (!track.id().equals(text(value, "id"))) continue;
            if (value.has("freeTrialInfo") && !value.get("freeTrialInfo").isJsonNull()) throw failure("rightsRestricted");
            String url = text(value, "url");
            if (url.isBlank() || integer(value, "code", 200) != 200) throw failure("rightsRestricted");
            // A successful request may be downgraded. Only the returned level earns a quality label.
            return new Media(mediaUrl(url), text(value, "type").toLowerCase(Locale.ROOT),qualityLabel(text(value,"level")));
        }
        throw failure("unavailable");
    }
    private static String qualityLabel(String id){return QUALITIES.stream().filter(q->q.id().equals(id)).map(Quality::label).findFirst().orElse("");}

    @Override public void logout() {
        cookies.clear(); currentAccount = null; loginKey = ""; resetNotice();metadata.clear();favoritePlaylist="";
        MusicNetwork.clearCookies(provider());
        for (String name : TOKEN_NAMES) credentials.delete(slot(name));
    }

    private List<String> ids(JsonArray list, boolean objects) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonElement element : list) {
            String id = objects && element.isJsonObject() ? text(element.getAsJsonObject(), "id")
                    : element.isJsonPrimitive() ? element.getAsString() : "";
            if (!validId(id)) { partialNotice(); continue; }
            if (ids.size() >= MAX_TRACKS) { limitNotice(); break; }
            ids.add(id);
        }
        return ids;
    }

    private List<Track> details(List<String> ids) throws Exception {
        Map<String,Track> resolved=new HashMap<>();for(String id:ids){Track cached=metadata.get(id);if(cached!=null)resolved.put(id,cached);}
        List<String> missing=ids.stream().filter(id->!resolved.containsKey(id)).toList();
        List<Track> all = new ArrayList<>();
        for (int offset = 0; offset < missing.size(); offset += PAGE_SIZE) {
            interrupted();
            List<String> page = missing.subList(offset, Math.min(missing.size(), offset + PAGE_SIZE));
            JsonArray c = new JsonArray();
            for (String id : page) c.add(object("id", Long.parseLong(id)));
            JsonObject result = request("/api/v3/song/detail", object("c", c.toString()), true);
            requireSuccess(result);
            if (!result.has("songs") || !result.get("songs").isJsonArray()) throw failure("protocolChanged");
            Map<String, Track> byId = new HashMap<>();
            for (JsonElement value : result.getAsJsonArray("songs")) {
                if (!value.isJsonObject()) continue;
                Track track = track(value.getAsJsonObject());
                if (track != null) {byId.put(track.id(), track);resolved.put(track.id(),track);metadata.put(track.id(),track);}
            }
            for (String id : page) {
                Track track = byId.get(id);
                if (track == null) partialNotice();
            }
        }
        for(String id:ids){Track track=resolved.get(id);if(track!=null)all.add(track);else partialNotice();}
        return List.copyOf(all);
    }

    private Track track(JsonObject song) {
        String id = text(song, "id"), name = text(song, "name");
        if (!validId(id) || name.isBlank()) return null;
        JsonObject album = child(song, song.has("al") ? "al" : "album");
        List<String> artists = new ArrayList<>();
        for (JsonElement artist : array(song, song.has("ar") ? "ar" : "artists")) {
            if (artist.isJsonObject()) {
                String artistName = text(artist.getAsJsonObject(), "name");
                if (!artistName.isBlank()) artists.add(artistName);
            }
        }
        return new Track(provider(), id, name, String.join(" / ", artists), text(album, "name"),
                text(album, "picUrl"), Math.max(0, number(song, song.has("dt") ? "dt" : "duration", 0)));
    }

    private JsonObject request(String path, JsonObject data, boolean web) throws Exception {
        interrupted();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        headers.put("Accept", "application/json");
        headers.put("Referer", HOME + "/");
        headers.put("Origin", HOME);
        headers.put("User-Agent", web
                ? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36"
                : "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.17.204416");
        String cookie = cookieHeader();
        if (!cookie.isBlank()) headers.put("Cookie", cookie);
        data.addProperty("e_r", false);
        String body;
        if (web) {
            data.addProperty("csrf_token", cookies.getOrDefault("__csrf", ""));
            body = Crypto.weapi(data.toString());
        } else {
            JsonObject header = object("os", "pc", "appver", "3.1.17.204416", "osver", "Microsoft-Windows-10",
                    "deviceId", deviceId, "channel", "netease", "versioncode", "140", "resolution", "1920x1080",
                    "buildver", Long.toString(System.currentTimeMillis() / 1000), "requestId",
                    System.currentTimeMillis() + "_" + Crypto.RANDOM.nextInt(10000), "__csrf", cookies.getOrDefault("__csrf", ""));
            if (hasSession()) header.addProperty("MUSIC_U", cookies.get("MUSIC_U"));
            if (cookies.containsKey("NMTID")) header.addProperty("NMTID", cookies.get("NMTID"));
            data.add("header", header);
            body = Crypto.eapi(path, data.toString());
        }
        URI uri = URI.create((web ? HOME + "/weapi/" : EAPI + "/eapi/") + path.substring(5));
        JsonObject result = MusicNetwork.json(provider(), uri, "POST", headers, body.getBytes(StandardCharsets.UTF_8), MAX_JSON);
        for (Map.Entry<String, String> entry : MusicNetwork.cookies(provider()).entrySet()) {
            String name = entry.getKey(), value = entry.getValue();
            if (!(name.equals("MUSIC_U") || name.equals("__csrf") || name.equals("NMTID"))) continue;
            if (value == null || value.isBlank()) cookies.remove(name);
            else if (safeCookie(value)) cookies.put(name, value);
        }
        return result;
    }

    private String cookieHeader() {
        StringBuilder result = new StringBuilder("os=pc; appver=3.1.17.204416; __remember_me=true");
        for (Map.Entry<String, String> item : cookies.entrySet())
            if (safeCookie(item.getValue())) result.append("; ").append(item.getKey()).append('=').append(item.getValue());
        return result.toString();
    }

    private Account requireAccount() throws Exception {
        Account account = account();
        if (account == null) throw failure("loginRequired");
        return account;
    }
    private void requireTrack(Track track) throws IOException {
        if (track == null || track.provider() != provider() || !validId(track.id())) throw failure("invalidTrack");
    }
    private boolean hasSession() { return safeCookie(cookies.get("MUSIC_U")); }
    private static String slot(String name) { return "music.netease." + name; }
    private void resetNotice() { notice = "ui.musicPlayer.compatibility"; }
    private void partialNotice() { if (!notice.endsWith("libraryLimit")) notice = "ui.musicPlayer.partialLibrary"; }
    private void limitNotice() { notice = "ui.musicPlayer.libraryLimit"; }
    private static void interrupted() throws InterruptedException { if (Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    private static IOException failure(String key) { return new IOException("ui.musicPlayer." + key); }
    private static void requireSuccess(JsonObject result) throws IOException {
        int code = integer(result, "code", -1);
        if (code == 301 || code == 401 || code == 302) throw failure("loginRequired");
        if (code != 200) throw failure(code == 403 || code == 404 ? "unavailable" : "protocolChanged");
    }
    private static boolean safeCookie(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x20 || c >= 0x7f || c == ';' || c == ',') return false;
        }
        return true;
    }
    private static boolean validId(String id) {
        if (id == null || !id.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(id) > 0; } catch (NumberFormatException invalid) { return false; }
    }
    private static String mediaUrl(String value) throws IOException {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if ((!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getPort() != -1
                    || !(host.endsWith(".music.126.net") || host.endsWith(".music.163.com"))) throw failure("unavailable");
            return value.regionMatches(true, 0, "http:", 0, 5) ? "https:" + value.substring(5) : value;
        } catch (IllegalArgumentException invalid) { throw failure("unavailable"); }
    }
    private static JsonObject child(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
    private static JsonArray array(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }
    private static String text(JsonObject parent, String key) {
        JsonElement value = parent == null ? null : parent.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
    private static long number(JsonObject parent, String key, long fallback) {
        try { return Long.parseLong(text(parent, key)); } catch (NumberFormatException invalid) { return fallback; }
    }
    private static int integer(JsonObject parent, String key, int fallback) {
        long value = number(parent, key, fallback);
        return value > Integer.MAX_VALUE || value < Integer.MIN_VALUE ? fallback : (int) value;
    }
    private static boolean truth(JsonObject parent, String key) { return text(parent, key).equals("true"); }
    private static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = pairs[i].toString(); Object value = pairs[i + 1];
            if (value instanceof Number number) result.addProperty(key, number);
            else if (value instanceof Boolean bool) result.addProperty(key, bool);
            else result.addProperty(key, value.toString());
        }
        return result;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    /** Encrypts requests locally using the published compatibility protocol; these are protocol constants, not user keys. */
    private static final class Crypto {
        private static final SecureRandom RANDOM = new SecureRandom();
        private static final byte[] IV = "0102030405060708".getBytes(StandardCharsets.US_ASCII);
        private static final String PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";
        private static String weapi(String json) throws Exception {
            String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
            StringBuilder secret = new StringBuilder();
            for (int i = 0; i < 16; i++) secret.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
            String first = Base64.getEncoder().encodeToString(aes(json, "0CoJUm6Qyw8W8jud", true));
            String params = Base64.getEncoder().encodeToString(aes(first, secret.toString(), true));
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
            BigInteger encoded = new BigInteger(1, secret.reverse().toString().getBytes(StandardCharsets.US_ASCII))
                    .modPow(key.getPublicExponent(), key.getModulus());
            String rsa = encoded.toString(16);
            rsa = "0".repeat(256 - rsa.length()) + rsa;
            return "params=" + encode(params) + "&encSecKey=" + rsa;
        }
        private static String eapi(String path, String json) throws Exception {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(("nobody" + path + "use" + json + "md5forencrypt").getBytes(StandardCharsets.UTF_8)));
            String plain = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest;
            return "params=" + HexFormat.of().withUpperCase().formatHex(aes(plain, "e82ckenh8dichen8", false));
        }
        private static byte[] aes(String value, String key, boolean cbc) throws Exception {
            Cipher cipher = Cipher.getInstance(cbc ? "AES/CBC/PKCS5Padding" : "AES/ECB/PKCS5Padding");
            SecretKeySpec secret = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            if (cbc) cipher.init(Cipher.ENCRYPT_MODE, secret, new IvParameterSpec(IV));
            else cipher.init(Cipher.ENCRYPT_MODE, secret);
            return cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
