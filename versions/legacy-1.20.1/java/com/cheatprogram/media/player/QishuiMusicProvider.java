package com.cheatprogram.media.player;

import com.cheatprogram.ai.CredentialStore;
import com.cheatprogram.core.Config;
import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.player.MusicModels.*;
import com.google.gson.*;
import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.*;

/**
 * Independently written client for Qishui's own Passport and Luna PC wire formats.
 * Protocol references: Winnie0408/LocalMusicHelper, ConvertPage.kt (Luna login/library);
 * XxHuberrr/Mineradio-paused, qishui-api.js (/me, track_v2 and collection schemas).
 * No third-party service, browser-cookie extraction, security-runtime bypass, or song matching.
 * Current platforms may require CAPTCHA/native security signing; that remains an explicit challenge.
 */
public final class QishuiMusicProvider implements MusicProvider {
    private static final String API="https://api.qishui.com",UA="LunaPC/3.3.0(359450208)";
    private static final int MAX_JSON=8*1024*1024,MAX_PAGES=100;
    private static final String[] TOKENS={"sessionid","sessionid_ss","sid_tt","sid_guard","uid_tt","uid_tt_ss","passport_csrf_token","passport_csrf_token_default"};
    private final CredentialStore store;
    private final Transport transport;
    private final Map<String,String> cookies=new java.util.concurrent.ConcurrentHashMap<>();
    private final LinkedHashMap<String,JsonObject> metadata=new LinkedHashMap<>(64,.75f,true);
    private final Map<String,JsonObject> cards=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,PlaybackVariants> clearVariants=new java.util.concurrent.ConcurrentHashMap<>();
    private final Object sessionLock=new Object();
    private volatile long sessionRevision;
    private final String deviceId,installId;
    private volatile Account currentAccount;
    private volatile Login pending;
    private volatile long nextPoll;
    private volatile boolean challenged;
    private volatile String notice="ui.musicPlayer.qishuiCompatibility";
    private record ClearVariant(String tier,String label,Media media) { }
    private record PlaybackVariants(List<ClearVariant> variants,List<Quality> qualities) { }
    @FunctionalInterface interface Transport {MusicNetwork.Response send(URI uri,String method,Map<String,String> headers,byte[] body)throws Exception;}
    public QishuiMusicProvider(){this(CredentialStore.open(Config.directory().resolve("music/credentials.dpapi")),
            (uri,method,headers,body)->MusicNetwork.response(Provider.QISHUI,uri,method,headers,body,MAX_JSON));}
    /** Fixture tests provide a transport and no credential store; no account or device is accessed. */
    QishuiMusicProvider(CredentialStore store,Transport transport){
        this.store=store;this.transport=Objects.requireNonNull(transport);MusicNetwork.clearCookies(provider());
        for(String name:TOKENS){String value=read(name);if(safeCookie(value))cookies.put(name,value);}
        String savedDevice=read("device"),savedInstall=read("install");
        deviceId=savedDevice.matches("[0-9]{13,20}")?savedDevice:numericId();installId=savedInstall.matches("[0-9]{13,20}")?savedInstall:numericId();
    }
    @Override public Provider provider(){return Provider.QISHUI;}
    @Override public String requirement(){return notice;}
    @Override public Login beginLogin()throws Exception{
        clearSession();challenged=false;notice="ui.musicPlayer.qishuiCompatibility";
        JsonObject response=raw("/passport/web/get_qrcode/",passportParams(),null);
        if(challenge(response))return challengeLogin(response,"",new byte[0]);
        success(response);JsonObject data=data(response);String token=text(data,"token"),url=first(text(data,"qrcode_index_url"),text(data,"web_url"),text(data,"qrcode"));
        if(!safeCookie(token)||safeUrl(url).isBlank())throw failure("protocolChanged");
        pending=new Login(token,url,MusicQr.png(url),"ui.musicPlayer.qrWaiting",false);nextPoll=System.nanoTime()+7_500_000_000L;return pending;
    }
    @Override public Login pollLogin(Login login)throws Exception{
        if(pending==null||login==null||!Objects.equals(pending.key(),login.key()))throw failure("qrExpired");
        if(challenged||System.nanoTime()<nextPoll)return pending;
        nextPoll=System.nanoTime()+7_500_000_000L;Map<String,String> query=passportParams();query.put("token",pending.key());
        JsonObject response=raw("/passport/web/check_qrconnect/",query,null);
        if(challenge(response))return challengeLogin(response,pending.key(),pending.qrPng());
        success(response);JsonObject data=data(response);String status=text(data,"status").toLowerCase(Locale.ROOT);
        if(status.equals("expired")||status.equals("timeout")||status.equals("5"))throw failure("qrExpired");
        if(status.equals("confirmed")||status.equals("3")){
            // A confirmation flag without a first-party session and /me identity is never a login.
            if(!hasSession())throw failure("protocolChanged");Account proof=account();if(proof==null)throw failure("loginRequired");persist();
            return pending=new Login(pending.key(),pending.url(),pending.qrPng(),"ui.musicPlayer.connected",true);
        }
        if(!Set.of("new","waiting","scanned","1","2","").contains(status))throw failure("protocolChanged");
        return pending=new Login(pending.key(),pending.url(),pending.qrPng(),Set.of("scanned","2").contains(status)?"ui.musicPlayer.qrConfirm":"ui.musicPlayer.qrWaiting",false);
    }
    private Login challengeLogin(JsonObject response,String token,byte[] qr){
        challenged=true;notice="ui.musicPlayer.platformVerification";JsonObject data=data(response);
        String url=safeUrl(first(text(data,"desc_url"),text(data,"verify_url"),text(data,"redirect_url")));
        // No manufactured continuation URL, no unvalidated redirect, no poll storm after CAPTCHA.
        return pending=new Login(token,url,qr,"ui.musicPlayer.captchaRequired",false);
    }
    @Override public Account account()throws Exception{
        long revision=sessionRevision;
        if(!hasSession())return null;JsonObject response=api("/luna/pc/me",pcParams(),null);JsonObject data=data(response);
        JsonObject user=object(data,"my_info","user","user_info","account","me");if((user.size()==0))user=data;
        String id=first(text(user,"user_id"),text(user,"uid"),text(user,"id"));
        if(!validId(id)){currentAccount=null;throw failure("loginRequired");}
        String name=first(text(user,"nickname"),text(user,"nick_name"),text(user,"public_name"),text(user,"name"),id);
        Account account=new Account(id,name,image(firstElement(user,"larger_avatar_url","medium_avatar_url","avatar_url","avatar")));
        synchronized(sessionLock){if(revision!=sessionRevision)throw failure("loginRequired");return currentAccount=account;}
    }
    @Override public List<Playlist> playlists()throws Exception{
        requireAccount();LinkedHashMap<String,Playlist> result=new LinkedHashMap<>();cards.clear();
        for(JsonObject page:pages("/luna/pc/me/playlist",pcParams(),"playlists")){
            for(JsonElement entry:array(data(page),"playlists"))if(entry.isJsonObject()){
                JsonObject card=entry.getAsJsonObject();JsonObject nested=object(card,"playlist","playlist_info");if(!(nested.size()==0))card=nested;
                String id=first(text(card,"id"),text(card,"playlist_id")),name=first(text(card,"title"),text(card,"name"),text(card,"playlist_name"));
                if(!validId(id)||name.isBlank())throw failure("protocolChanged");
                Playlist playlist=new Playlist(id,name,image(firstElement(card,"cover_url","cover","url_cover")),(int)Math.max(0,number(card,"count_tracks","track_count","total")));
                result.put(id,playlist);cards.put(id,card);
            }
        }
        return List.copyOf(result.values());
    }
    @Override public List<Track> liked()throws Exception{
        requireAccount();LinkedHashMap<String,Track> result=new LinkedHashMap<>();boolean anyCollection=false;
        for(JsonObject page:pages("/luna/pc/me/collection/mixed",pcParams(),"media_resources","media_list","medias","tracks","items","collections")){
            JsonArray values=array(data(page),"media_resources","media_list","medias","tracks","items","collections");anyCollection|=!values.isEmpty();
            for(JsonElement value:values){Track track=parseTrack(value);if(track!=null)result.put(track.id(),track);}
        }
        if(!result.isEmpty())return List.copyOf(result.values());
        List<Playlist> library=playlists();
        for(Playlist playlist:library){JsonObject card=cards.get(playlist.id());String type=first(text(card,"playlist_type"),text(card,"type"));
            boolean favorite=bool(card,"is_favorite")||bool(card,"is_liked")||type.equals("favorite_track_playlist")||type.equals("liked_track_playlist");
            if(favorite)return tracks(playlist);
        }
        if(anyCollection)throw failure("likedUnavailable");return List.of();
    }
    @Override public List<Track> tracks(Playlist playlist)throws Exception{
        requireAccount();if(playlist==null||!validId(playlist.id()))throw failure("invalidTrack");
        Map<String,String> query=pcParams();query.put("playlist_id",playlist.id());LinkedHashMap<String,Track> result=new LinkedHashMap<>();
        for(JsonObject page:pages("/luna/pc/playlist/detail",query,"media_resources","media_list","medias","tracks","items")){
            for(JsonElement value:array(data(page),"media_resources","media_list","medias","tracks","items")){Track track=parseTrack(value);if(track!=null)result.put(track.id(),track);}
        }
        return List.copyOf(result.values());
    }
    @Override public LrcLyrics lyrics(Track track)throws Exception{
        long revision=sessionRevision;
        JsonObject value=trackData(track),body=trackObject(value),display=object(body,"display_info"),base=object(body,"base_info");
        JsonObject info=object(display,"lyric_info");if((info.size()==0))info=object(body,"lyric_info");if((info.size()==0))info=object(base,"lyric_info");
        JsonObject original=object(info,"lyric_entity","original_lyric","lyric");
        String content=first(text(original,"content"),text(info,"content"),text(info,"lyric_text"));
        if(content.isBlank()||content.stripLeading().startsWith("<"))throw failure("originalLyricsUnavailable");
        LrcLyrics lyrics=LrcLyrics.parse(content); // No translated field, title search, or substituted track is consulted.
        synchronized(sessionLock){if(revision!=sessionRevision)throw failure("loginRequired");return lyrics;}
    }
    /** Render callers only read immutable already-parsed variants; no JSON walk, lock or request here. */
    @Override public List<Quality> qualities(Track track){
        if(track==null||track.provider()!=provider()||!validId(track.id()))return List.of();
        PlaybackVariants cached=clearVariants.get(track.id());return cached==null?List.of():cached.qualities();
    }
    @Override public Media media(Track track)throws Exception{return media(track,"");}
    @Override public Media media(Track track,String quality)throws Exception{
        long revision=sessionRevision;
        JsonObject value=trackData(track,true),body=trackObject(value),data=data(value),player=player(value);
        if(denied(body)||denied(data)||denied(player))throw failure("rightsRestricted");
        if(encrypted(player))throw failure("decoderRequired");
        List<ClearVariant> variants=variants(value);String requested=quality==null?"":quality;
        for(ClearVariant variant:variants)if(requested.isBlank()||requested.equals(variant.tier())){
            synchronized(sessionLock){if(revision!=sessionRevision)throw failure("loginRequired");return variant.media();}
        }
        if(!requested.isBlank())throw failure("qualityUnavailable");
        throw failure("decoderRequired");
    }
    private static JsonObject player(JsonObject value){
        JsonObject player=object(data(value),"track_player","trackPlayer");
        return (player.size()==0)?object(trackObject(value),"track_player","trackPlayer"):player;
    }
    private static List<ClearVariant> variants(JsonObject value){
        if(denied(data(value))||denied(trackObject(value)))return List.of();
        List<ClearVariant> result=new ArrayList<>();collectClearMedia(player(value),0,"",result,new int[]{0});return List.copyOf(result);
    }
    private static PlaybackVariants playbackVariants(List<ClearVariant> variants){
        List<Quality> qualities=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(ClearVariant variant:variants)if(qualities.size()<16&&!variant.tier().isBlank()&&seen.add(variant.tier()))qualities.add(new Quality(variant.tier(),variant.label()));
        return new PlaybackVariants(variants,List.copyOf(qualities));
    }
    private static void collectClearMedia(JsonElement element,int depth,String inheritedTier,List<ClearVariant> result,int[] visited){
        if(element==null||depth>6||visited[0]++>=1024||result.size()>=64)return;
        if(element.isJsonArray()){for(JsonElement item:element.getAsJsonArray())collectClearMedia(item,depth+1,inheritedTier,result,visited);return;}
        if(element.isJsonPrimitive()){
            String json=element.getAsString();if(json.length()<256000&&(json.stripLeading().startsWith("{")||json.stripLeading().startsWith("[")))
                try{collectClearMedia(JsonParser.parseString(json),depth+1,inheritedTier,result,visited);}catch(RuntimeException ignored){}return;
        }
        if(!element.isJsonObject())return;JsonObject object=element.getAsJsonObject();if(denied(object)||encrypted(object))return;
        String tier=qualityField(object);if(tier.isBlank())tier=inheritedTier;
        String url=safeUrl(first(text(object,"main_play_url"),text(object,"main_url"),text(object,"play_url"),text(object,"url"),text(object,"MainPlayUrl"),text(object,"PlayURL")));
        if(!url.isBlank()){
            String id=qualityId(tier);
            result.add(new ClearVariant(id,tier,new Media(url,first(text(object,"format"),text(object,"Format"),text(object,"file_format"),text(object,"FileFormat")),id)));
        }
        // Walk only the platform-returned track_player subtree. Object keys/bitrates/filenames never invent a tier.
        for(var item:object.entrySet())if(item.getValue().isJsonObject()||item.getValue().isJsonArray()
                ||Set.of("video_model","videoModel","VideoModel","play_info","playInfo").contains(item.getKey()))
            collectClearMedia(item.getValue(),depth+1,tier,result,visited);
    }
    private static String qualityField(JsonObject value){
        String tier=first(text(value,"quality"),text(value,"Quality"),text(value,"definition"),text(value,"Definition"),text(value,"quality_type"),text(value,"QualityType"));
        return tier.length()<=100&&tier.chars().noneMatch(Character::isISOControl)?tier:"";
    }
    /** An opaque local lookup for a returned label, never an invented platform quality request. */
    private static String qualityId(String value){
        if(value.isBlank()||value.matches("[A-Za-z0-9_-]{1,64}"))return value;
        try{return "native_"+Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private JsonObject trackData(Track track)throws Exception{
        return trackData(track,false);
    }
    private JsonObject trackData(Track track,boolean refresh)throws Exception{
        long revision=sessionRevision;
        requireAccount();if(track==null||track.provider()!=provider()||!validId(track.id()))throw failure("invalidTrack");
        if(!refresh)synchronized(sessionLock){JsonObject cached=metadata.get(track.id());if(cached!=null&&revision==sessionRevision)return cached;}
        Map<String,String> query=pcParams();query.put("track_id",track.id());query.put("media_type","track");JsonObject response=api("/luna/pc/track_v2",query,null);
        JsonObject body=trackObject(response),base=object(body,"base_info");String id=first(text(base,"id"),text(body,"id"),text(body,"track_id"));
        if(!track.id().equals(id))throw failure("protocolChanged");List<ClearVariant> variants=variants(response);
        synchronized(sessionLock){
            if(revision!=sessionRevision)throw failure("loginRequired");metadata.put(track.id(),response);clearVariants.put(track.id(),playbackVariants(variants));
            while(metadata.size()>64){String oldest=metadata.keySet().iterator().next();metadata.remove(oldest);clearVariants.remove(oldest);}
        }
        return response;
    }
    private static Track parseTrack(JsonElement value){
        if(value==null||!value.isJsonObject())return null;JsonObject raw=value.getAsJsonObject(),entity=object(raw,"entity","data");if((entity.size()==0))entity=raw;
        JsonObject media=object(entity,"media");if((media.size()==0))media=entity;String type=first(text(raw,"media_type"),text(media,"media_type"),text(raw,"resource_type"));
        if(!type.isBlank()&&!type.equals("track")&&!type.equals("song")&&!type.equals("1"))return null;
        if(media.has("playlist_id")||media.has("count_tracks")||media.has("playlist"))return null;
        JsonObject wrapper=object(media,"track_wrapper"),track=object(wrapper,"track");if((track.size()==0))track=object(media,"track_entity","track");if((track.size()==0))track=media;
        JsonObject base=object(track,"base_info");if((base.size()==0))base=track;JsonObject display=object(track,"display_info"),related=object(track,"related_info"),album=object(related,"album_link","album");
        String id=first(text(base,"id"),text(track,"id")),name=first(text(base,"name"),text(base,"title"));if(!validId(id)||name.isBlank())return null;
        List<String> artists=new ArrayList<>();for(JsonElement item:array(related,"artist_links","artists"))if(item.isJsonObject()){String artist=first(text(item.getAsJsonObject(),"name"),text(item.getAsJsonObject(),"display_name"));if(!artist.isBlank())artists.add(artist);}
        String artist=artists.isEmpty()?first(text(base,"artist_name"),text(track,"artist_name")):String.join(" / ",artists);
        return new Track(Provider.QISHUI,id,name,artist,first(text(album,"name"),text(base,"album_name")),image(firstElement(display,"cover_url","url_cover")),Math.max(0,number(base,"duration_ms","duration")));
    }
    private static JsonObject trackObject(JsonObject response){JsonObject data=data(response),track=object(data,"track","track_info","trackInfo");return (track.size()==0)?data:track;}
    private List<JsonObject> pages(String path,Map<String,String> query,String... fields)throws Exception{
        List<JsonObject> result=new ArrayList<>();String cursor="";
        for(int i=0;i<MAX_PAGES;i++){
            Map<String,String> parameters=new LinkedHashMap<>(query);parameters.put("count","100");parameters.put("cursor",cursor);
            JsonObject page=api(path,parameters,null),data=data(page);boolean found=false;for(String field:fields)found|=data.has(field)&&data.get(field).isJsonArray();if(!found)throw failure("protocolChanged");result.add(page);
            if(!bool(data,"has_more")&&!bool(data,"hasMore"))return result;
            String next=first(text(data,"next_cursor"),text(data,"nextCursor"));if(next.isBlank()||next.equals(cursor))throw failure("partialLibrary");cursor=next;
        }
        throw failure("partialLibrary");
    }
    private JsonObject api(String path,Map<String,String> params,JsonObject body)throws Exception{JsonObject result=raw(path,params,body);success(result);return result;}
    private JsonObject raw(String path,Map<String,String> params,JsonObject body)throws Exception{
        long revision;Map<String,String> sessionCookies;
        synchronized(sessionLock){revision=sessionRevision;sessionCookies=Map.copyOf(cookies);}
        StringJoiner query=new StringJoiner("&");params.forEach((key,value)->query.add(encode(key)+"="+encode(value)));
        URI uri=URI.create(API+path+"?"+query);if(!MusicNetwork.credentialHost(provider(),uri))throw failure("hostBlocked");
        Map<String,String> headers=new LinkedHashMap<>();headers.put("User-Agent",UA);headers.put("Accept","application/json");headers.put("x-luna-is-local-user","1");
        if(!sessionCookies.isEmpty()){StringJoiner values=new StringJoiner("; ");sessionCookies.forEach((key,value)->values.add(key+"="+value));headers.put("Cookie",values.toString());}
        if(body!=null)headers.put("Content-Type","application/json; charset=utf-8");
        MusicNetwork.Response response=transport.send(uri,body==null?"GET":"POST",Map.copyOf(headers),body==null?null:body.toString().getBytes(StandardCharsets.UTF_8));
        synchronized(sessionLock){if(revision!=sessionRevision)throw failure("loginRequired");capture(response.headers());}
        if(response.status()==401||response.status()==403)throw failure("loginRequired");if(response.status()<200||response.status()>=300)throw failure("networkFailed");
        if(response.body().length>MAX_JSON)throw failure("responseTooLarge");try{return JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8)).getAsJsonObject();}catch(RuntimeException invalid){throw failure("protocolChanged");}
    }
    private void capture(Map<String,List<String>> headers){
        headers.forEach((name,values)->{if(name.equalsIgnoreCase("set-cookie"))for(String header:values)try{for(HttpCookie cookie:HttpCookie.parse(header)){
            String domain=Objects.requireNonNullElse(cookie.getDomain(),"api.qishui.com").replaceFirst("^\\.","");
            if(!domain.equalsIgnoreCase("qishui.com")&&!domain.equalsIgnoreCase("api.qishui.com"))continue;
            if(Arrays.asList(TOKENS).contains(cookie.getName())){if(cookie.hasExpired())cookies.remove(cookie.getName());else if(safeCookie(cookie.getValue()))cookies.put(cookie.getName(),cookie.getValue());}
        }}catch(IllegalArgumentException ignored){}});
    }
    private void success(JsonObject response)throws IOException{
        long code=number(response,"status_code","error_code");if(code==0)code=number(data(response),"error_code","status_code");if(code==0)return;
        if(code==4031||code==2046){notice="ui.musicPlayer.platformVerification";throw failure("captchaRequired");}
        if(code==7||code==2156)throw failure("rateLimited");if(code==1000016||code==401){currentAccount=null;throw failure("loginRequired");}throw failure("protocolChanged");
    }
    private static boolean challenge(JsonObject response){long code=number(data(response),"error_code");if(code==0)code=number(response,"error_code");return code==4031||code==2046||data(response).has("verify_ticket")||data(response).has("captcha");}
    private Map<String,String> pcParams(){Map<String,String> values=new LinkedHashMap<>();values.put("aid","386088");values.put("app_name","luna_pc");values.put("device_id",deviceId);values.put("iid",installId);values.put("fp",deviceId);values.put("device_platform","windows");values.put("device_type","Windows");values.put("version_name","3.3.0");values.put("version_code","30030000");values.put("channel","official");values.put("region","cn");return values;}
    private Map<String,String> passportParams(){Map<String,String> values=new LinkedHashMap<>();values.put("aid","386088");values.put("device_id",deviceId);values.put("install_id",installId);values.put("did",deviceId);values.put("iid",installId);values.put("language","zh");values.put("passport_jssdk_version","3.0.3");values.put("device_platform","PC");values.put("version_code","1.6.3");values.put("next","https://luna-pc.bytedance.net/login");return values;}
    private void requireAccount()throws Exception{if(!hasSession()||(currentAccount==null&&account()==null))throw failure("loginRequired");}
    private boolean hasSession(){return safeCookie(cookies.get("sessionid"))||safeCookie(cookies.get("sessionid_ss"));}
    private String read(String name){return store==null?"":store.read("music.qishui."+name,API);}
    private void persist(){if(store!=null){cookies.forEach((key,value)->store.put("music.qishui."+key,API,value));store.put("music.qishui.device",API,deviceId);store.put("music.qishui.install",API,installId);}}
    private void clearSession(){synchronized(sessionLock){sessionRevision++;cookies.clear();metadata.clear();clearVariants.clear();cards.clear();currentAccount=null;pending=null;MusicNetwork.clearCookies(provider());if(store!=null)for(String name:TOKENS)store.delete("music.qishui."+name);}}
    @Override public void logout(){clearSession();challenged=false;notice="ui.musicPlayer.qishuiCompatibility";}
    private static boolean validId(String value){return value!=null&&value.matches("[A-Za-z0-9_-]{1,128}");}
    private static boolean safeCookie(String value){return value!=null&&!value.isBlank()&&value.length()<=4096&&value.chars().allMatch(c->c>32&&c<127&&c!=';'&&c!=','&&c!='"');}
    private static String safeUrl(String value){try{URI uri=URI.create(value);return MusicNetwork.allowed(Provider.QISHUI,uri)?uri.toString():"";}catch(RuntimeException invalid){return "";}}
    private static String image(JsonElement value){if(value==null||value.isJsonNull())return "";if(value.isJsonPrimitive())return safeUrl(value.getAsString());if(value.isJsonArray())for(JsonElement item:value.getAsJsonArray()){String url=image(item);if(!url.isBlank())return url;}if(value.isJsonObject())return image(firstElement(value.getAsJsonObject(),"url_list","urls","url"));return "";}
    private static boolean denied(JsonObject value){return bool(value,"is_preview")||bool(value,"is_trial")||(value.has("playable")&&!bool(value,"playable"))||(value.has("can_play")&&!bool(value,"can_play"));}
    private static boolean encrypted(JsonObject value){return !first(text(value,"play_auth"),text(value,"spade_a"),text(value,"PlayAuth"),text(value,"SpadeA")).isBlank()
            ||object(value,"encrypt_info","EncryptInfo","encryptInfo").size()!=0||bool(value,"encrypted")||bool(value,"is_encrypted")||number(value,"encrypt_type","EncryptType")!=0;}
    private static JsonObject data(JsonObject root){JsonObject value=object(root,"data");return (value.size()==0)?root:value;}
    private static JsonObject object(JsonObject root,String... names){if(root!=null)for(String name:names){JsonElement value=root.get(name);if(value!=null&&value.isJsonObject())return value.getAsJsonObject();}return new JsonObject();}
    private static JsonArray array(JsonObject root,String... names){if(root!=null)for(String name:names){JsonElement value=root.get(name);if(value!=null&&value.isJsonArray())return value.getAsJsonArray();}return new JsonArray();}
    private static JsonElement firstElement(JsonObject root,String... names){if(root!=null)for(String name:names){JsonElement value=root.get(name);if(value!=null&&!value.isJsonNull())return value;}return JsonNull.INSTANCE;}
    private static String text(JsonObject root,String name){JsonElement value=root==null?null:root.get(name);return value!=null&&value.isJsonPrimitive()?value.getAsString():"";}
    private static String first(String... values){for(String value:values)if(value!=null&&!value.isBlank())return value;return "";}
    private static long number(JsonObject root,String... names){for(String name:names)try{String value=text(root,name);if(!value.isBlank())return Long.parseLong(value);}catch(NumberFormatException ignored){}return 0;}
    private static boolean bool(JsonObject root,String name){String value=text(root,name);return value.equalsIgnoreCase("true")||value.equals("1");}
    private static String numericId(){SecureRandom random=new SecureRandom();StringBuilder value=new StringBuilder("73");for(int i=0;i<14;i++)value.append(random.nextInt(10));return value.toString();}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static IOException failure(String name){return new IOException("ui.musicPlayer."+name);}
}


