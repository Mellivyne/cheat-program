package com.cheatprogram.media.player;

import com.cheatprogram.ai.CredentialStore;
import com.cheatprogram.core.Config;
import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.player.MusicModels.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Direct QQ Music compatibility client. Protocol references: L-1124/QQMusicApi and ylw1997/qqmusic-api.
 * The published QQ Music web client IDs identify the platform login flow; no developer secret is used.
 * Every request goes through the provider's first-party transport, without an API proxy or a song matcher.
 */
public final class QQMusicProvider implements MusicProvider {
    private static final String HOME = "https://y.qq.com/", API = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String SLOT = "music.qq.auth";
    private static final int MAX_JSON = 8 * 1024 * 1024, PAGE_SIZE = 200, MAX_TRACKS = 20000, MAX_PLAYLISTS = 5000;
    private static final Pattern CALLBACK = Pattern.compile("^\\s*ptuiCB\\s*\\((.*)\\)\\s*;?\\s*$", Pattern.DOTALL);
    private static final Pattern ARGUMENT = Pattern.compile("\\s*'((?:\\\\.|[^'\\\\])*)'\\s*(?:,|$)");
    private record AudioTier(String id,String label,String extension) { }
    // Unencrypted native GetVkey file families. Encrypted/NAC variants are deliberately unsupported.
    private static final List<AudioTier> AUDIO_TIERS=List.of(new AudioTier("M500","标准音质",".mp3"),
            new AudioTier("M800","HQ 高品质",".mp3"),new AudioTier("F000","SQ 无损音质",".flac"),
            new AudioTier("RS01","Hi-Res 高解析无损",".flac"),new AudioTier("AI00","臻品母带",".flac"),
            new AudioTier("Q000","臻品音质",".flac"),new AudioTier("Q001","臻品全景声 5.1",".flac"),
            new AudioTier("Q003","臻品全景声 7.1",".ogg"),new AudioTier("D004","杜比全景声",".mp4"),
            new AudioTier("DT03","DTS:X",".mp4"),new AudioTier("C400","流畅音质",".m4a"));
    private static final List<Quality> QUALITIES=AUDIO_TIERS.stream().map(t->new Quality(t.id,t.label)).toList();
    private final CredentialStore credentials = CredentialStore.open(Config.directory().resolve("music/credentials.dpapi"));
    private final Map<String, SongInfo> songInfo = new java.util.concurrent.ConcurrentHashMap<>();
    private final String guid = UUID.randomUUID().toString().replace("-", "");
    private String musicId = "", musicKey = "", encryptedUin = "", qrsig = "", loginKey = "";
    private Account currentAccount;
    private String notice = "ui.musicPlayer.compatibility";

    private record SongInfo(long numericId, String mediaMid, int type) { }

    public QQMusicProvider() {
        MusicNetwork.clearCookies(provider());
        String saved = credentials.read(SLOT, API);
        if (saved.isBlank() || saved.length() > 4096) return;
        try {
            JsonObject auth = JsonParser.parseString(saved).getAsJsonObject();
            String id = text(auth, "musicid"), key = text(auth, "musickey");
            if (validNumber(id) && safeCookie(key)) {
                musicId = id; musicKey = key; encryptedUin = cleanEncryptedUin(text(auth, "encryptUin"));
            }
        } catch (RuntimeException invalid) { /* No credential values are included in diagnostics. */ }
    }

    @Override public Provider provider() { return Provider.QQ; }
    @Override public String requirement() { return notice; }
    @Override public boolean supportsTranslatedLyrics() { return true; }

    @Override public Login beginLogin() throws Exception {
        clearMemory();
        MusicNetwork.clearCookies(provider());
        URI uri = uri("https://ssl.ptlogin2.qq.com/ptqrshow", "appid", "716027609", "e", "2", "l", "M",
                "s", "3", "d", "72", "v", "4", "t", Long.toString(System.currentTimeMillis()),
                "daid", "383", "pt_3rd_aid", "100497308");
        byte[] image = MusicNetwork.bytes(provider(), uri, authHeaders(""), 512 * 1024);
        String signature = MusicNetwork.cookies(provider()).getOrDefault("qrsig", "");
        if (!safeCookie(signature) || image.length < 8 || image[0] != (byte) 0x89 || image[1] != 'P'
                || image[2] != 'N' || image[3] != 'G') throw failure("protocolChanged");
        qrsig = signature;
        loginKey = UUID.randomUUID().toString(); // Local request identity; the platform qrsig remains private in memory.
        return new Login(loginKey, HOME, image, "ui.musicPlayer.qrWaiting", false);
    }

    @Override public Login pollLogin(Login login) throws Exception {
        if (login == null || loginKey.isBlank() || !loginKey.equals(login.key()) || qrsig.isBlank()) throw failure("qrExpired");
        URI uri = uri("https://ssl.ptlogin2.qq.com/ptqrlogin", "u1", "https://graph.qq.com/oauth2.0/login_jump",
                "ptqrtoken", Integer.toString(hash33(qrsig, 0)), "ptredirect", "0", "h", "1", "t", "1", "g", "1",
                "from_ui", "1", "ptlang", "2052", "action", "0-0-" + System.currentTimeMillis(),
                "js_ver", "20102616", "js_type", "1", "pt_uistyle", "40", "aid", "716027609", "daid", "383",
                "pt_3rd_aid", "100497308", "has_onekey", "1");
        MusicNetwork.Response response = MusicNetwork.response(provider(), uri, "GET", authHeaders("qrsig=" + qrsig), null, 64 * 1024);
        requireHttp(response, false);
        List<String> args = callback(new String(response.body(), StandardCharsets.UTF_8));
        int code;
        try { code = Integer.parseInt(args.get(0)); } catch (NumberFormatException invalid) { throw failure("protocolChanged"); }
        String status = switch (code) {
            case 66 -> "ui.musicPlayer.qrWaiting";
            case 67 -> "ui.musicPlayer.qrConfirm";
            case 65 -> "ui.musicPlayer.qrExpired";
            case 68 -> "ui.musicPlayer.loginDeclined";
            case 0 -> "ui.musicPlayer.connected";
            default -> throw failure("protocolChanged");
        };
        if (code == 0) {
            if (args.size() < 3) throw failure("protocolChanged");
            try { finishQQAuthorization(args.get(2)); }
            finally { qrsig = ""; loginKey = ""; MusicNetwork.clearCookies(provider()); }
        } else if (code == 65 || code == 68) {
            qrsig = ""; loginKey = ""; MusicNetwork.clearCookies(provider());
        }
        return new Login(login.key(), login.url(), login.qrPng(), status, code == 0);
    }

    private void finishQQAuthorization(String callbackUrl) throws Exception {
        URI callbackUri;
        try { callbackUri = URI.create(callbackUrl); } catch (IllegalArgumentException invalid) { throw failure("protocolChanged"); }
        if (!"https".equalsIgnoreCase(callbackUri.getScheme()) || callbackUri.getUserInfo() != null
                || callbackUri.getHost() == null || !under(callbackUri.getHost().toLowerCase(Locale.ROOT), "graph.qq.com"))
            throw failure("hostBlocked");
        Map<String, String> query = query(callbackUri);
        String uin = query.getOrDefault("uin", ""), sigx = query.getOrDefault("ptsigx", "");
        if (!validNumber(uin) || !safeCookie(sigx)) throw failure("protocolChanged");
        MusicNetwork.clearCookies(provider());
        URI check = uri("https://ssl.ptlogin2.graph.qq.com/check_sig", "uin", uin, "pttype", "1", "service", "ptqrlogin",
                "nodirect", "0", "ptsigx", sigx, "s_url", "https://graph.qq.com/oauth2.0/login_jump", "ptlang", "2052",
                "ptredirect", "100", "aid", "716027609", "daid", "383", "j_later", "0", "low_login_hour", "0",
                "regmaster", "0", "pt_login_type", "3", "pt_aid", "0", "pt_aaid", "16", "pt_light", "0", "pt_3rd_aid", "100497308");
        MusicNetwork.Response checked = MusicNetwork.response(provider(), check, "GET", authHeaders(""), null, 256 * 1024);
        requireHttp(checked, true);
        Map<String, String> auth = MusicNetwork.cookies(provider());
        String skey = auth.getOrDefault("p_skey", "");
        if (!safeCookie(skey)) throw failure("loginAuthorizationRequired");
        String state = UUID.randomUUID().toString();
        String form = form("response_type", "code", "client_id", "100497308",
                "redirect_uri", "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
                "scope", "get_user_info,get_app_friends", "state", state, "switch", "", "from_ptlogin", "1", "src", "1",
                "update_auth", "1", "openapi", "1010_1030", "g_tk", Integer.toString(hash33(skey, 5381)),
                "auth_time", Long.toString(System.currentTimeMillis()), "ui", UUID.randomUUID().toString());
        MusicNetwork.Response authorized = MusicNetwork.response(provider(), URI.create("https://graph.qq.com/oauth2.0/authorize"),
                "POST", authHeaders(cookieHeader(auth)), form.getBytes(StandardCharsets.UTF_8), 256 * 1024);
        requireHttp(authorized, true);
        String location = header(authorized, "location");
        URI redirect;
        try { redirect = URI.create(location); } catch (IllegalArgumentException invalid) { throw failure("loginAuthorizationRequired"); }
        if (!"https".equalsIgnoreCase(redirect.getScheme()) || !"y.qq.com".equalsIgnoreCase(redirect.getHost())
                || !"/portal/wx_redirect.html".equals(redirect.getPath()) || redirect.getUserInfo() != null)
            throw failure("loginAuthorizationRequired");
        Map<String, String> redirectQuery = query(redirect);
        String code = redirectQuery.getOrDefault("code", "");
        if (!code.matches("[A-Za-z0-9_-]{1,2048}") || !state.equals(redirectQuery.get("state"))) throw failure("loginAuthorizationRequired");
        JsonObject data = call("QQConnectLogin.LoginServer", "QQLogin", object("code", code), false, object("tmeLoginType", 2));
        String id = first(data, "str_musicid", "musicid"), key = text(data, "musickey");
        if (!validNumber(id) || !safeCookie(key)) throw failure("protocolChanged");
        String encrypt = cleanEncryptedUin(text(data, "encryptUin"));
        JsonObject stored = object("musicid", id, "musickey", key, "encryptUin", encrypt);
        if (stored.toString().length() > 4096) throw failure("credentialTooLarge");
        // One DPAPI entry prevents a partially replaced ID/key pair after changing accounts.
        credentials.put(SLOT, API, stored.toString());
        musicId = id; musicKey = key; encryptedUin = encrypt; currentAccount = null;
    }

    @Override public Account account() throws Exception {
        if (!hasSession()) return null;
        if (currentAccount != null) return currentAccount;
        JsonObject data = call("music.UserInfo.userInfoServer", "GetLoginUserInfo", new JsonObject(), true);
        JsonObject info = child(data, "info");
        if (info.size() == 0) throw failure("loginRequired");
        String returnedId = first(info, "str_musicid", "musicid");
        if (!returnedId.isBlank() && !musicId.equals(returnedId)) throw failure("protocolChanged");
        currentAccount = new Account(musicId, text(info, "nick"), artwork(text(info, "logo")));
        if (encryptedUin.isBlank()) encryptedUin = cleanEncryptedUin(first(info, "encryptUin", "encrypt_uin"));
        return currentAccount;
    }

    @Override public List<Track> liked() throws Exception {
        resetNotice(); requireAccount();
        return loadSongs(0, 201, encryptedAccount());
    }

    @Override public List<Playlist> playlists() throws Exception {
        resetNotice(); requireAccount();
        Map<String, Playlist> all = new LinkedHashMap<>();
        JsonObject created = call("music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", object("uin", musicId), true);
        if (!created.has("v_playlist") || !created.get("v_playlist").isJsonArray()) throw failure("protocolChanged");
        addPlaylists(all, created.getAsJsonArray("v_playlist"));
        if (created.has("bFinish") && !truth(created, "bFinish") || number(created, "total", all.size()) > all.size()) partialNotice();
        if (all.size() >= MAX_PLAYLISTS) { limitNotice(); return List.copyOf(all.values()); }
        String euin = encryptedAccount();
        int offset = 0;
        for (int page = 0; page <= MAX_PLAYLISTS / PAGE_SIZE; page++) {
            interrupted();
            JsonObject subscribed = call("music.musicasset.PlaylistFavRead", "CgiGetPlaylistFavInfo",
                    object("uin", euin, "offset", offset, "size", PAGE_SIZE), true);
            if (!subscribed.has("v_list") || !subscribed.get("v_list").isJsonArray()) throw failure("protocolChanged");
            JsonArray list = subscribed.getAsJsonArray("v_list");
            int before = all.size(); addPlaylists(all, list);
            offset += list.size();
            boolean more = truth(subscribed, "hasmore") || number(subscribed, "total", offset) > offset;
            if (!more) return List.copyOf(all.values());
            if (list.isEmpty() || all.size() == before) throw failure("partialLibrary");
            if (all.size() >= MAX_PLAYLISTS) { limitNotice(); return List.copyOf(all.values()); }
        }
        limitNotice(); return List.copyOf(all.values());
    }

    @Override public List<Track> tracks(Playlist playlist) throws Exception {
        resetNotice();
        if (playlist == null || !validNumber(playlist.id())) throw failure("invalidTrack");
        return loadSongs(Long.parseLong(playlist.id()), 0, "");
    }

    private List<Track> loadSongs(long playlistId, int dirId, String euin) throws Exception {
        List<Track> all = new ArrayList<>();
        String previousPage = "";
        int offset = 0;
        for (int page = 0; page <= MAX_TRACKS / PAGE_SIZE; page++) {
            interrupted();
            JsonObject param = object("disstid", playlistId, "dirid", dirId, "tag", true, "song_begin", offset,
                    "song_num", PAGE_SIZE, "userinfo", true, "orderlist", true, "onlysonglist", false);
            if (!euin.isBlank()) param.addProperty("enc_host_uin", euin);
            JsonObject data = call("music.srfDissInfo.DissInfo", "CgiGetDiss", param, hasSession());
            if (!data.has("songlist") || !data.get("songlist").isJsonArray()) throw failure("protocolChanged");
            JsonArray list = data.getAsJsonArray("songlist");
            StringBuilder pageIds = new StringBuilder();
            for (JsonElement value : list) {
                if (!value.isJsonObject()) { partialNotice(); continue; }
                Track track = track(value.getAsJsonObject());
                if (track == null) { partialNotice(); continue; }
                pageIds.append(track.id()).append(',');
                if (all.size() >= MAX_TRACKS) { limitNotice(); return List.copyOf(all); }
                all.add(track);
            }
            offset += list.size();
            boolean more = truth(data, "hasmore") || number(data, "total_song_num", offset) > offset;
            if (!more) return List.copyOf(all);
            if (list.isEmpty() || pageIds.isEmpty() || pageIds.toString().equals(previousPage)) throw failure("partialLibrary");
            previousPage = pageIds.toString();
            if (all.size() >= MAX_TRACKS) { limitNotice(); return List.copyOf(all); }
        }
        limitNotice(); return List.copyOf(all);
    }

    private void addPlaylists(Map<String, Playlist> all, JsonArray list) {
        for (JsonElement value : list) {
            if (!value.isJsonObject()) { partialNotice(); continue; }
            JsonObject item = value.getAsJsonObject();
            String id = first(item, "tid", "id", "dissid");
            if (!validNumber(id)) { partialNotice(); continue; }
            if (all.size() >= MAX_PLAYLISTS && !all.containsKey(id)) { limitNotice(); break; }
            all.put(id, new Playlist(id, first(item, "dirName", "title", "dissname", "name"),
                    artwork(first(item, "picUrl", "picurl", "cover", "logo")),
                    Math.max(0, integer(item, item.has("songNum") ? "songNum" : "songnum", 0))));
        }
    }

    private String encryptedAccount() throws Exception {
        if (!encryptedUin.isBlank()) return encryptedUin;
        requireAccount();
        URI uri = uri("https://c6.y.qq.com/rsc/fcgi-bin/fcg_get_profile_homepage.fcg", "ct", "20", "cv", "4747474",
                "cid", "205360838", "userid", musicId, "format", "json", "g_tk", Integer.toString(hash33(musicKey, 5381)),
                "loginUin", musicId, "hostUin", "0", "inCharset", "utf-8", "outCharset", "utf-8", "notice", "0", "needNewCode", "0");
        JsonObject response = MusicNetwork.json(provider(), uri, "GET", apiHeaders(true), null, MAX_JSON);
        requireCode(integer(response, "code", -1));
        encryptedUin = cleanEncryptedUin(text(child(child(response, "data"), "creator"), "encrypt_uin"));
        if (encryptedUin.isBlank()) throw failure("protocolChanged");
        return encryptedUin;
    }

    @Override public LrcLyrics lyrics(Track track) throws Exception {
        return lyricField(lyricResponse(track,false),"lyric","originalLyricsUnavailable",false);
    }

    @Override public LrcLyrics translatedLyrics(Track track) throws Exception {
        return lyricField(lyricResponse(track,true),"trans","translatedLyricsUnavailable",true);
    }

    private JsonObject lyricResponse(Track track,boolean translated)throws Exception {
        requireTrack(track);
        JsonObject data=call("music.musichallSong.PlayLyricInfo","GetPlayLyricInfo",object("songMid",track.id(),
                "crypt",0,"qrc",0,"qrc_t",0,"lrc_t",0,"roma",0,"roma_t",0,
                "trans",translated?1:0,"trans_t",0,"type",1),hasSession());
        SongInfo known=songInfo.get(track.id());
        if(known!=null&&known.numericId()>0&&number(data,"songID",known.numericId())!=known.numericId()
                ||data.has("songMid")&&!track.id().equals(text(data,"songMid")))throw failure("protocolChanged");
        return data;
    }

    private static LrcLyrics lyricField(JsonObject data,String field,String missing,boolean synced)throws IOException {
        String content=text(data,field);
        if(content.isBlank())throw failure(missing);
        if(!content.startsWith("[")&&!content.contains("\n[")){
            try {
                content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Base64.getDecoder().decode(content))).toString();
            }catch(IllegalArgumentException|CharacterCodingException invalid){throw failure(missing);}
        }
        // Unsupported encrypted blobs never become visible text or a fabricated translation.
        LrcLyrics lyrics=LrcLyrics.parse(content);
        if(lyrics.isEmpty()||synced&&(!lyrics.synced()||lyrics.lines().stream().noneMatch(line->!line.text().isBlank())))throw failure(missing);
        return lyrics;
    }

    @Override public Media media(Track track) throws Exception {
        return media(track,AUDIO_TIERS.getFirst(),false);
    }
    @Override public List<Quality> qualities(Track track){return track==null||track.provider()==provider()?QUALITIES:List.of();}
    @Override public Media media(Track track,String quality)throws Exception{
        if(quality==null||quality.isEmpty())return media(track);requireTrack(track);
        AudioTier tier=tier(quality);if(tier==null)throw failure("qualityUnavailable");return media(track,tier,true);
    }
    private Media media(Track track,AudioTier requested,boolean explicit) throws Exception {
        requireTrack(track);
        SongInfo info = songInfo.get(track.id());
        if (info == null) {
            JsonObject detail = call("music.pf_song_detail_svr", "get_song_detail_yqq", object("song_mid", track.id()), hasSession());
            JsonObject raw = child(detail, "track_info");
            if (!track.id().equals(text(raw, "mid")) || track(raw) == null) throw failure("protocolChanged");
            info = songInfo.get(track.id());
        }
        String mediaMid=info.mediaMid().isBlank()?track.id()+track.id():info.mediaMid();
        String filename = requested.id+mediaMid+requested.extension;
        JsonObject data = call("music.vkey.GetVkey", "UrlGetVkey", object("uin", hasSession() ? musicId : "0",
                "filename", strings(filename), "songmid", strings(track.id()), "songtype", numbers(info.type()),
                "guid", guid, "ctx", 0), hasSession());
        for (JsonElement value : array(data, "midurlinfo")) {
            if (!value.isJsonObject()) continue;
            JsonObject item = value.getAsJsonObject();
            if (!track.id().equals(text(item, "songmid"))) continue;
            String path = text(item, "purl");
            if (path.isBlank() || integer(item, "result", 0) != 0) throw failure("rightsRestricted");
            String actualFile=text(item,"filename");if(actualFile.isBlank())try{actualFile=URI.create(path).getPath();actualFile=actualFile.substring(actualFile.lastIndexOf('/')+1);}catch(RuntimeException invalid){throw failure("protocolChanged");}
            AudioTier actual=tierForFile(actualFile,mediaMid);
            if(actual==null||!explicit&&!filename.equals(actualFile))throw failure("rightsRestricted");
            JsonArray servers = array(data, "sip");
            if (servers.isEmpty()) {
                JsonObject dispatch = call("music.audioCdnDispatch.cdnDispatch", "GetCdnDispatch",
                        object("guid", guid, "uid", hasSession() ? musicId : "0", "use_new_domain", 1, "use_ipv6", 0), hasSession());
                servers = array(dispatch, "sip");
            }
            for (JsonElement server : servers) {
                if (!server.isJsonPrimitive()) continue;
                String url = mediaUrl(server.getAsString(), path, actualFile);
                if (!url.isBlank()) return new Media(url,actual.extension.substring(1),actual.label);
            }
            // Published SDK's own-domain fallback, retaining the platform-issued exact file path/vkey.
            String url = mediaUrl("https://isure.stream.qqmusic.qq.com/", path, actualFile);
            if (!url.isBlank()) return new Media(url,actual.extension.substring(1),actual.label);
            throw failure("unavailable");
        }
        throw failure("rightsRestricted");
    }
    private static AudioTier tier(String id){return AUDIO_TIERS.stream().filter(t->t.id.equals(id)).findFirst().orElse(null);}
    private static AudioTier tierForFile(String filename,String mediaMid){return AUDIO_TIERS.stream().filter(t->filename.equals(t.id+mediaMid+t.extension)).findFirst().orElse(null);}

    private Track track(JsonObject item) {
        String mid = text(item, "mid"), title = first(item, "name", "title");
        if (!validMid(mid) || title.isBlank()) return null;
        JsonObject album = child(item, "album");
        List<String> singers = new ArrayList<>();
        for (JsonElement singer : array(item, "singer")) if (singer.isJsonObject()) {
            String name = text(singer.getAsJsonObject(), "name"); if (!name.isBlank()) singers.add(name);
        }
        String albumMid = first(album, "mid", "pmid");
        String cover = validMid(albumMid) ? "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg" : "";
        String mediaMid = text(child(item, "file"), "media_mid");
        if (!mediaMid.isBlank() && !validMid(mediaMid)) mediaMid = "";
        if (!songInfo.containsKey(mid) && songInfo.size() >= MAX_TRACKS) songInfo.remove(songInfo.keySet().iterator().next());
        songInfo.put(mid, new SongInfo(number(item, "id", 0), mediaMid, integer(item, "type", 0)));
        long seconds = Math.max(0, Math.min(7 * 24 * 60 * 60, number(item, "interval", 0)));
        return new Track(provider(), mid, title, String.join(" / ", singers), text(album, "name"), cover, seconds * 1000);
    }

    private JsonObject call(String module, String method, JsonObject param, boolean authenticated) throws Exception {
        return call(module, method, param, authenticated, new JsonObject());
    }
    private JsonObject call(String module, String method, JsonObject param, boolean authenticated, JsonObject extras) throws Exception {
        interrupted();
        if (authenticated && !hasSession()) throw failure("loginRequired");
        JsonObject comm = object("ct", 24, "cv", 4747474, "platform", "yqq.json", "format", "json", "inCharset", "utf-8",
                "outCharset", "utf-8", "notice", 0, "needNewCode", 1, "uin", authenticated ? musicId : "0",
                "g_tk", authenticated ? hash33(musicKey, 5381) : 5381, "g_tk_new_20200303", authenticated ? hash33(musicKey, 5381) : 5381);
        if (authenticated) {
            comm.addProperty("qq", musicId); comm.addProperty("uid", musicId); comm.addProperty("authst", musicKey);
            comm.addProperty("tmeLoginType", 2); comm.addProperty("loginUin", musicId); comm.addProperty("tmeAppID", "qqmusic");
        }
        for (Map.Entry<String, JsonElement> entry : extras.entrySet()) comm.add(entry.getKey(), entry.getValue());
        JsonObject request = new JsonObject(); request.add("comm", comm);
        JsonObject operation = object("module", module, "method", method); operation.add("param", param); request.add("req", operation);
        JsonObject response = MusicNetwork.json(provider(), URI.create(API), "POST", apiHeaders(authenticated),
                request.toString().getBytes(StandardCharsets.UTF_8), MAX_JSON);
        requireCode(integer(response, "code", -1));
        JsonObject result = child(response, "req");
        requireCode(integer(result, "code", -1));
        JsonObject data = child(result, "data");
        if (data.has("code")) requireCode(integer(data, "code", -1));
        return data;
    }

    @Override public void logout() {
        clearMemory(); MusicNetwork.clearCookies(provider()); credentials.delete(SLOT); resetNotice();
    }
    private void clearMemory() {
        musicId = ""; musicKey = ""; encryptedUin = ""; qrsig = ""; loginKey = ""; currentAccount = null; songInfo.clear();
    }
    private boolean hasSession() { return validNumber(musicId) && safeCookie(musicKey); }
    private void requireAccount() throws Exception { if (account() == null) throw failure("loginRequired"); }
    private void requireTrack(Track track) throws IOException {
        if (track == null || track.provider() != provider() || !validMid(track.id())) throw failure("invalidTrack");
    }
    private Map<String, String> apiHeaders(boolean authenticated) {
        Map<String, String> headers = commonHeaders(); headers.put("Content-Type", "application/json");
        if (authenticated && hasSession()) headers.put("Cookie", "uin=" + musicId + "; qqmusic_uin=" + musicId
                + "; qqmusic_key=" + musicKey + "; qm_keyst=" + musicKey + "; tmeLoginType=2");
        return headers;
    }
    private static Map<String, String> commonHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36");
        headers.put("Referer", HOME); headers.put("Origin", "https://y.qq.com"); headers.put("Accept", "application/json");
        return headers;
    }
    private static Map<String, String> authHeaders(String cookie) {
        Map<String, String> headers = commonHeaders(); headers.put("Referer", "https://xui.ptlogin2.qq.com/");
        headers.put("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        if (!cookie.isBlank()) headers.put("Cookie", cookie);
        return headers;
    }
    private static String cookieHeader(Map<String, String> cookies) {
        StringBuilder header = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) if (entry.getKey().matches("[A-Za-z0-9_]{1,64}") && safeCookie(entry.getValue())) {
            if (!header.isEmpty()) header.append("; "); header.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return header.toString();
    }
    private static List<String> callback(String input) throws IOException {
        Matcher outer = CALLBACK.matcher(input);
        if (!outer.matches()) throw failure("protocolChanged");
        String arguments = outer.group(1); Matcher matcher = ARGUMENT.matcher(arguments);
        List<String> args = new ArrayList<>(); int at = 0;
        while (matcher.find()) {
            if (matcher.start() != at || args.size() >= 16) throw failure("protocolChanged");
            args.add(unescape(matcher.group(1))); at = matcher.end();
        }
        if (at != arguments.length() || args.isEmpty()) throw failure("protocolChanged");
        return args;
    }
    private static String unescape(String input) throws IOException {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c != '\\') { out.append(c); continue; }
            if (++i >= input.length()) throw failure("protocolChanged");
            c = input.charAt(i);
            if (c == 'x' || c == 'u') {
                int count = c == 'x' ? 2 : 4;
                if (i + count >= input.length()) throw failure("protocolChanged");
                try { out.append((char) Integer.parseInt(input.substring(i + 1, i + 1 + count), 16)); }
                catch (NumberFormatException invalid) { throw failure("protocolChanged"); }
                i += count;
            } else if (c == '\\' || c == '\'' || c == '"' || c == '/') out.append(c);
            else if (c == 'n') out.append('\n');
            else if (c == 'r') out.append('\r');
            else if (c == 't') out.append('\t');
            else if (c == 'b') out.append('\b');
            else if (c == 'f') out.append('\f');
            else throw failure("protocolChanged");
        }
        return out.toString();
    }
    private static String mediaUrl(String base, String path, String filename) {
        try {
            URI uri = URI.create(https(base)).resolve(https(path));
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 || uri.getFragment() != null || !under(uri.getHost().toLowerCase(Locale.ROOT), "qqmusic.qq.com")
                    || !uri.getPath().endsWith("/" + filename)) return "";
            return uri.toString();
        } catch (IllegalArgumentException invalid) { return ""; }
    }
    private static String artwork(String input) {
        try {
            URI uri = URI.create(https(input));
            if (uri.getHost() == null || uri.getUserInfo() != null || !"https".equalsIgnoreCase(uri.getScheme())) return "";
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return under(host, "qpic.cn") || under(host, "gtimg.cn") ? uri.toString() : "";
        } catch (IllegalArgumentException invalid) { return ""; }
    }
    private static String https(String input) { return input.regionMatches(true, 0, "http:", 0, 5) ? "https:" + input.substring(5) : input; }
    private static boolean under(String host, String domain) { return host.equals(domain) || host.endsWith("." + domain); }
    private static boolean validMid(String value) { return value != null && value.matches("[A-Za-z0-9]{14,64}"); }
    private static boolean validNumber(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(value) > 0; } catch (NumberFormatException invalid) { return false; }
    }
    private static boolean safeCookie(String value) {
        if (value == null || value.isBlank() || value.length() > 4096) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i); if (c <= 0x20 || c >= 0x7f || c == ';' || c == ',') return false;
        }
        return true;
    }
    private static String cleanEncryptedUin(String value) { return value.matches("[A-Za-z0-9_=/+-]{1,256}") ? value : ""; }
    private static int hash33(String value, int seed) { int hash = seed; for (int i = 0; i < value.length(); i++) hash = hash * 33 + value.charAt(i); return hash & 0x7fffffff; }
    private static URI uri(String base, String... pairs) { return URI.create(base + "?" + form(pairs)); }
    private static String form(String... pairs) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (!out.isEmpty()) out.append('&');
            out.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return out.toString();
    }
    private static Map<String, String> query(URI uri) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            for (String part : (uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&")) {
                int eq = part.indexOf('='); if (eq < 0) continue;
                String key = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
                if (result.containsKey(key)) throw failure("protocolChanged");
                result.put(key, URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException invalid) { throw failure("protocolChanged"); }
        return result;
    }
    private static String header(MusicNetwork.Response response, String name) {
        for (Map.Entry<String, List<String>> entry : response.headers().entrySet())
            if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) return entry.getValue().get(0);
        return "";
    }
    private static void requireHttp(MusicNetwork.Response response, boolean redirect) throws IOException {
        int status = response.status();
        if (status >= 200 && status < 300 || redirect && status >= 300 && status < 400) return;
        throw failure(status == 429 ? "rateLimited" : status == 401 || status == 403 ? "loginRequired" : "networkFailed");
    }
    private static void requireCode(int code) throws IOException {
        if (code == 0) return;
        if (code == 1000 || code == 104401 || code == 104400) throw failure("loginRequired");
        if (code == 104604) throw failure("rateLimited");
        if (code == 104003 || code == 104004 || code == 104013) throw failure("rightsRestricted");
        if (code == 20277 || code == 20278 || code == 20279 || code == 20450) throw failure("accountRestricted");
        throw failure("protocolChanged");
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
    private static String first(JsonObject parent, String... names) {
        for (String name : names) { String value = text(parent, name); if (!value.isBlank()) return value; } return "";
    }
    private static long number(JsonObject parent, String key, long fallback) {
        try { return Long.parseLong(text(parent, key)); } catch (NumberFormatException invalid) { return fallback; }
    }
    private static int integer(JsonObject parent, String key, int fallback) {
        long value = number(parent, key, fallback); return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE ? fallback : (int) value;
    }
    private static boolean truth(JsonObject parent, String key) { String value = text(parent, key); return value.equals("true") || value.equals("1"); }
    private static JsonArray strings(String value) { JsonArray list = new JsonArray(); list.add(value); return list; }
    private static JsonArray numbers(int value) { JsonArray list = new JsonArray(); list.add(value); return list; }
    private static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            String key = pairs[i].toString(); Object value = pairs[i + 1];
            if (value instanceof JsonElement element) result.add(key, element);
            else if (value instanceof Number number) result.addProperty(key, number);
            else if (value instanceof Boolean bool) result.addProperty(key, bool);
            else result.addProperty(key, value.toString());
        }
        return result;
    }
    private void resetNotice() { notice = "ui.musicPlayer.compatibility"; }
    private void partialNotice() { if (!notice.endsWith("libraryLimit")) notice = "ui.musicPlayer.partialLibrary"; }
    private void limitNotice() { notice = "ui.musicPlayer.libraryLimit"; }
    private static void interrupted() throws InterruptedException { if (Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    private static IOException failure(String key) { return new IOException("ui.musicPlayer." + key); }
}
