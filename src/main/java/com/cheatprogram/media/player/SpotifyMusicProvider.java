package com.cheatprogram.media.player;

import com.cheatprogram.ai.CredentialStore;
import com.cheatprogram.core.Config;
import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.player.MusicModels.*;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Official Spotify PKCE and Premium Connect client. Audio stays in the user's Spotify device.
 * See developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow and concepts/redirect_uri. */
public final class SpotifyMusicProvider implements MusicProvider {
    private static final String API="https://api.spotify.com/v1", ACCOUNTS="https://accounts.spotify.com";
    private static final String SCOPES="user-read-private user-library-read playlist-read-private playlist-read-collaborative user-read-playback-state user-modify-playback-state";
    private static final int MAX_TRACKS=20_000,MAX_LISTS=5_000,PAGE=50;
    // Current official Search API maximum is 10 per request, offset at most 1000.
    private static final int SEARCH_SIZE=10,MAX_SEARCH_PAGES=100;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final ScheduledThreadPoolExecutor EXPIRY=new ScheduledThreadPoolExecutor(1,daemon("spotify-oauth-expiry"));
    static {EXPIRY.setRemoveOnCancelPolicy(true);}
    record Response(int status,Map<String,List<String>> headers,JsonObject body){
        @Override public String toString(){return "SpotifyResponse[status="+status+"]";}
    }
    @FunctionalInterface interface Transport {Response request(URI uri,String method,Map<String,String> headers,byte[] body)throws Exception;}
    interface Secrets {String read(String name);void put(String name,String value);void delete(String name);}
    private static final class LocalSecrets implements Secrets {
        final CredentialStore store=CredentialStore.open(Config.directory().resolve("music/credentials.dpapi"));
        public String read(String name){return store.read("music.spotify."+name,ACCOUNTS);}
        public void put(String name,String value){store.put("music.spotify."+name,ACCOUNTS,value);}
        public void delete(String name){store.delete("music.spotify."+name);}
    }
    public record RemotePlayback(Track track,boolean playing,long positionMs,long durationMs,long sampledNanos,String deviceId,String status){ }
    private record Auth(String token,String client,long generation){@Override public String toString(){return "SpotifyAuthorization[redacted]";}}
    private final Object lock=new Object();private final Transport transport;private final Secrets secrets;
    private String client="",access="",refresh="";private long expiresAt,generation,retryAtNanos;private Account confirmed;private Pending pending;

    public SpotifyMusicProvider(){this(SpotifyMusicProvider::direct,new LocalSecrets());}
    SpotifyMusicProvider(Transport transport,Secrets secrets){
        this.transport=Objects.requireNonNull(transport);this.secrets=Objects.requireNonNull(secrets);
        String saved=secrets.read("clientId");if(validClient(saved))client=saved;
        if(!client.isEmpty()){
            String a=secrets.read("accessToken"),r=secrets.read("refreshToken");if(validToken(a))access=a;if(validToken(r))refresh=r;
            try{expiresAt=Long.parseLong(secrets.read("expiresAt"));}catch(RuntimeException ignored){expiresAt=0;}
        }
    }
    @Override public Provider provider(){return Provider.SPOTIFY;}
    @Override public boolean supported(){return true;}
    @Override public boolean supportsSearch(){return true;}
    @Override public String requirement(){return configured()?"ui.musicPlayer.spotifyConnect":"ui.musicPlayer.spotifyClientRequired";}
    public boolean configured(){synchronized(lock){return validClient(client);}}
    public String clientId(){synchronized(lock){return client;}}
    /** Client IDs are public application configuration, stored locally outside profile/share exports. */
    public void clientId(String value){
        String next=Objects.requireNonNullElse(value,"").strip();if(!next.isEmpty()&&!validClient(next))throw new IllegalArgumentException("ui.musicPlayer.spotifyClientRequired");
        Pending old;
        synchronized(lock){if(next.equals(client))return;old=pending;pending=null;generation++;clearTokensLocked();client=next;
            if(next.isEmpty())secrets.delete("clientId");else secrets.put("clientId",next);}
        if(old!=null)old.close();
    }
    /** Register this portless URI in the Spotify app. Official loopback rules permit dynamic ports. */
    public static String registeredRedirectUri(){return "http://127.0.0.1/callback";}
    public void cancelLogin(){Pending old;synchronized(lock){old=pending;pending=null;generation++;}if(old!=null)old.close();}
    @Override public Login beginLogin()throws Exception{
        Pending old;String selected;long epoch;
        synchronized(lock){if(!validClient(client))throw failure("spotifyClientRequired");selected=client;old=pending;pending=null;generation++;epoch=generation;clearTokensLocked();}
        if(old!=null)old.close();Pending started=new Pending(epoch,selected);
        synchronized(lock){if(generation!=epoch){started.close();throw failure("loginRequired");}pending=started;}
        String url=ACCOUNTS+"/authorize?"+form(map("client_id",selected,"response_type","code","redirect_uri",started.redirect,
                "scope",SCOPES,"state",started.state,"code_challenge_method","S256","code_challenge",challenge(started.verifier),"show_dialog","true"));
        return new Login(started.state,url,new byte[0],"ui.musicPlayer.loginPending",false);
    }
    @Override public Login pollLogin(Login login)throws Exception{
        Pending flow;synchronized(lock){flow=pending;}
        if(flow==null||login==null||!constantEquals(login.key(),flow.state)||flow.expired())throw failure("qrExpired");
        Callback callback=flow.callback.get();if(callback==null)return new Login(login.key(),login.url(),login.qrPng(),"ui.musicPlayer.loginPending",false);
        if(!callback.error.isEmpty()){cancelLogin();throw failure("spotifyAccessRequired");}
        try{
            Response response=send(URI.create(ACCOUNTS+"/api/token"),"POST",Map.of("Content-Type","application/x-www-form-urlencoded"),
                    form(map("client_id",flow.client,"grant_type","authorization_code","code",callback.code,"redirect_uri",flow.redirect,"code_verifier",flow.verifier)).getBytes(StandardCharsets.UTF_8));
            checkStatus(response,false);synchronized(lock){if(pending!=flow||generation!=flow.generation)throw failure("loginRequired");adoptTokensLocked(response.body,true);}
            Account upstream=account();if(upstream==null)throw failure("loginRequired");
            synchronized(lock){if(pending!=flow||generation!=flow.generation)throw failure("loginRequired");persistTokensLocked();pending=null;}
            return new Login(login.key(),login.url(),login.qrPng(),"ui.musicPlayer.connected",true);
        }catch(Exception problem){
            synchronized(lock){if(pending==flow){pending=null;generation++;clearTokensLocked();}}
            throw sanitized(problem);
        }finally{flow.close();}
    }
    @Override public Account account()throws Exception{
        long epoch;synchronized(lock){if(client.isEmpty()||access.isEmpty()&&refresh.isEmpty())return null;epoch=generation;}
        JsonObject profile=api("/me","GET",null);String id=first(profile,"id","account_id"),name=first(profile,"display_name");
        if(id.isBlank()||id.length()>256||id.chars().anyMatch(Character::isISOControl))throw failure("protocolChanged");if(name.isBlank())name=id;
        Account result=new Account(id,name,image(profile));synchronized(lock){if(generation!=epoch)throw failure("loginRequired");confirmed=result;return result;}
    }
    @Override public List<Track> liked()throws Exception{return loadTracks("/me/tracks",false);}
    @Override public SearchPage search(String query,int page)throws Exception{
        if(query==null||query.isBlank()||query.strip().length()>200||query.chars().anyMatch(Character::isISOControl)
                ||page<0||page>=MAX_SEARCH_PAGES)throw failure("invalidSearch");
        requireAccount();
        JsonObject response=api("/search?"+form(map("q",query.strip(),"type","track","limit",Integer.toString(SEARCH_SIZE),
                "offset",Integer.toString(page*SEARCH_SIZE))),"GET",null);
        JsonObject result=object(response,"tracks");JsonArray items=requiredItems(result);
        if(items.size()>SEARCH_SIZE||number(result,"offset",-1)!=(long)page*SEARCH_SIZE
                ||number(result,"total",-1)<0||!result.has("next"))throw failure("protocolChanged");
        Map<String,Track> tracks=new LinkedHashMap<>();
        for(JsonElement element:items){
            // Null/removed/local items have no playable catalog ID and are never manufactured.
            if(!element.isJsonObject())continue;
            Track track=track(element.getAsJsonObject());if(track!=null)tracks.putIfAbsent(track.id(),track);
        }
        JsonElement next=result.get("next");
        if(!next.isJsonNull()&&(!next.isJsonPrimitive()||!next.getAsJsonPrimitive().isString()))throw failure("protocolChanged");
        boolean more=!items.isEmpty()&&!next.isJsonNull()&&page+1<MAX_SEARCH_PAGES
                &&(long)page*SEARCH_SIZE+items.size()<number(result,"total",0);
        // Never follow upstream next URLs; every request is rebuilt against the fixed first-party API.
        return new SearchPage(List.copyOf(tracks.values()),page,more);
    }
    @Override public List<Playlist> playlists()throws Exception{
        requireAccount();List<Playlist> all=new ArrayList<>();int offset=0;
        while(offset<MAX_LISTS){JsonObject page=api("/me/playlists?limit="+PAGE+"&offset="+offset,"GET",null);JsonArray items=requiredItems(page);
            for(JsonElement item:items){if(!item.isJsonObject())continue;JsonObject row=item.getAsJsonObject();String id=first(row,"id"),name=first(row,"name");if(!validId(id)||name.isBlank())continue;
                JsonObject count=object(row,"items");if(count.size()==0)count=object(row,"tracks");all.add(new Playlist(id,name,image(row),(int)Math.max(0,Math.min(Integer.MAX_VALUE,number(count,"total",0)))));}
            offset=nextOffset(page,items,offset,MAX_LISTS);if(offset<0)break;}
        return List.copyOf(all);
    }
    @Override public List<Track> tracks(Playlist playlist)throws Exception{
        if(playlist==null||!validId(playlist.id()))throw failure("protocolChanged");return loadTracks("/playlists/"+playlist.id()+"/items",true);
    }
    private List<Track> loadTracks(String path,boolean playlist)throws Exception{
        requireAccount();List<Track> all=new ArrayList<>();int offset=0;
        while(offset<MAX_TRACKS){JsonObject page=api(path+"?limit="+PAGE+"&offset="+offset,"GET",null);JsonArray items=requiredItems(page);
            for(JsonElement element:items){if(!element.isJsonObject())continue;JsonObject wrapper=element.getAsJsonObject();if(bool(wrapper,"is_local",false))continue;
                JsonObject row=object(wrapper,playlist?"item":"track");if(row.size()==0&&playlist)row=object(wrapper,"track");Track track=track(row);if(track!=null)all.add(track);}
            offset=nextOffset(page,items,offset,MAX_TRACKS);if(offset<0)break;}
        return List.copyOf(all);
    }
    @Override public LrcLyrics lyrics(Track track)throws IOException{validate(track);return LrcLyrics.parse("");}
    @Override public Media media(Track track)throws IOException{validate(track);throw failure("spotifyConnect");}
    /** Starts only the supplied Spotify recording on an existing active Spotify Connect device. */
    public void playRemote(Track track,long positionMs)throws Exception{
        validate(track);requireAccount();JsonObject actual=api("/tracks/"+track.id(),"GET",null);
        if(!track.id().equals(first(actual,"id"))||!bool(actual,"is_playable",true)||object(actual,"restrictions").size()>0)throw failure("rightsRequired");
        JsonObject body=new JsonObject();JsonArray uris=new JsonArray();uris.add("spotify:track:"+track.id());body.add("uris",uris);
        body.addProperty("position_ms",clampPosition(positionMs,track.durationMs()));api("/me/player/play","PUT",body);
    }
    public void pauseRemote()throws Exception{requireAccount();api("/me/player/pause","PUT",null);}
    public void resumeRemote()throws Exception{requireAccount();api("/me/player/play","PUT",null);}
    public void seekRemote(long positionMs)throws Exception{requireAccount();api("/me/player/seek?position_ms="+Math.max(0,Math.min(Integer.MAX_VALUE,positionMs)),"PUT",null);}
    public void volumeRemote(double volume)throws Exception{
        if(!Double.isFinite(volume))return;requireAccount();api("/me/player/volume?volume_percent="+(int)Math.round(Math.max(0,Math.min(1,volume))*100),"PUT",null);
    }
    public void nextRemote(int delta)throws Exception{if(delta==0)return;requireAccount();api(delta>0?"/me/player/next":"/me/player/previous","POST",null);}
    public RemotePlayback remotePlayback()throws Exception{
        requireAccount();JsonObject data=api("/me/player","GET",null);long nanos=System.nanoTime();
        if(data.size()==0)return new RemotePlayback(null,false,0,0,nanos,"","ui.musicPlayer.idle");
        Track track=track(object(data,"item"));JsonObject device=object(data,"device");String deviceId=first(device,"id");
        long position=Math.max(0,number(data,"progress_ms",0));boolean playing=track!=null&&bool(data,"is_playing",false)&&data.has("progress_ms")&&!data.get("progress_ms").isJsonNull();
        long duration=track==null?0:track.durationMs();if(duration>0)position=Math.min(duration,position);
        return new RemotePlayback(track,playing,position,duration,nanos,deviceId,"ui.musicPlayer."+(track==null?"idle":playing?"playing":"paused"));
    }
    @Override public void logout(){Pending old;synchronized(lock){old=pending;pending=null;generation++;clearTokensLocked();}if(old!=null)old.close();MusicNetwork.clearCookies(provider());}
    private void requireAccount()throws Exception{Account current;synchronized(lock){current=confirmed;}if(current==null&&account()==null)throw failure("loginRequired");}
    private Auth authorization()throws Exception{
        String refreshToken,selected;long epoch;
        synchronized(lock){if(!validClient(client))throw failure("spotifyClientRequired");if(System.nanoTime()<retryAtNanos)throw failure("rateLimited");
            if(validToken(access)&&expiresAt>System.currentTimeMillis()+30_000)return new Auth(access,client,generation);
            if(!validToken(refresh))throw failure("loginRequired");refreshToken=refresh;selected=client;epoch=generation;}
        Response response=send(URI.create(ACCOUNTS+"/api/token"),"POST",Map.of("Content-Type","application/x-www-form-urlencoded"),
                form(map("grant_type","refresh_token","refresh_token",refreshToken,"client_id",selected)).getBytes(StandardCharsets.UTF_8));
        if(response.status==400||response.status==401){synchronized(lock){if(epoch==generation)clearTokensLocked();}throw failure("loginRequired");}
        checkStatus(response,false);
        synchronized(lock){if(epoch!=generation)throw failure("loginRequired");adoptTokensLocked(response.body,false);persistTokensLocked();return new Auth(access,client,generation);}
    }
    private JsonObject api(String path,String method,JsonObject body)throws Exception{
        if(!path.startsWith("/")||path.startsWith("//")||path.indexOf('#')>=0)throw failure("hostBlocked");
        for(int attempt=0;attempt<2;attempt++){
            Auth auth=authorization();Map<String,String> headers=new LinkedHashMap<>();headers.put("Authorization","Bearer "+auth.token);if(body!=null)headers.put("Content-Type","application/json");
            Response response=send(URI.create(API+path),method,headers,body==null?null:body.toString().getBytes(StandardCharsets.UTF_8));
            synchronized(lock){if(auth.generation!=generation)throw failure("loginRequired");if(response.status==401&&attempt==0){access="";expiresAt=0;continue;}}
            checkStatus(response,path.startsWith("/me/player"));return response.body;
        }
        throw failure("loginRequired");
    }
    private Response send(URI uri,String method,Map<String,String> headers,byte[] body)throws Exception{
        if(!MusicNetwork.credentialHost(provider(),uri)||!(uri.getHost().equals("api.spotify.com")||uri.getHost().equals("accounts.spotify.com")))throw failure("hostBlocked");
        try{Response response=transport.request(uri,method,Map.copyOf(headers),body);if(response==null||response.body==null)throw failure("protocolChanged");
            if(response.status==429){long delay=30;for(var entry:response.headers.entrySet())if(entry.getKey().equalsIgnoreCase("Retry-After")&&!entry.getValue().isEmpty())try{delay=Long.parseLong(entry.getValue().get(0));}catch(NumberFormatException ignored){}
                synchronized(lock){retryAtNanos=System.nanoTime()+TimeUnit.SECONDS.toNanos(Math.max(1,Math.min(3600,delay)));}}return response;
        }catch(Exception problem){throw sanitized(problem);}
    }
    private static Response direct(URI uri,String method,Map<String,String> headers,byte[] body)throws Exception{
        MusicNetwork.Response response=MusicNetwork.response(Provider.SPOTIFY,uri,method,headers,body,8*1024*1024);byte[] bytes=response.body();JsonObject parsed=new JsonObject();
        if(bytes.length>0)try{parsed=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}catch(RuntimeException bad){throw failure("protocolChanged");}
        return new Response(response.status(),response.headers(),parsed);
    }
    private static void checkStatus(Response response,boolean playback)throws IOException{
        int status=response.status;if(status>=200&&status<300)return;if(status==401)throw failure("loginRequired");if(status==429)throw failure("rateLimited");
        if(status==404&&playback)throw failure("spotifyDeviceRequired");
        if(status==403){String reason=first(object(response.body,"error"),"reason");throw failure(reason.equals("PREMIUM_REQUIRED")?"spotifyPremiumRequired":"spotifyAccessRequired");}
        throw failure("failed");
    }
    private void adoptTokensLocked(JsonObject json,boolean initial)throws IOException{
        String a=first(json,"access_token"),r=first(json,"refresh_token"),type=first(json,"token_type");long seconds=number(json,"expires_in",0);
        if(!validToken(a)||!type.equalsIgnoreCase("Bearer")||seconds<=0||initial&&!validToken(r))throw failure("protocolChanged");
        access=a;if(validToken(r))refresh=r;expiresAt=System.currentTimeMillis()+Math.min(seconds,86400)*1000;
    }
    private void persistTokensLocked(){secrets.put("accessToken",access);secrets.put("refreshToken",refresh);secrets.put("expiresAt",Long.toString(expiresAt));}
    private void clearTokensLocked(){access="";refresh="";expiresAt=0;confirmed=null;retryAtNanos=0;for(String slot:List.of("accessToken","refreshToken","expiresAt"))secrets.delete(slot);}
    private static Track track(JsonObject row){
        String id=first(row,"id"),name=first(row,"name");if(!validId(id)||!first(row,"type").equals("track")||name.isBlank()||bool(row,"is_local",false))return null;
        JsonObject album=object(row,"album");List<String> artists=new ArrayList<>();JsonArray values=array(row,"artists");for(JsonElement value:values)if(value.isJsonObject()){String artist=first(value.getAsJsonObject(),"name");if(!artist.isBlank())artists.add(artist);}
        return new Track(Provider.SPOTIFY,id,name,String.join(", ",artists),first(album,"name"),image(album),Math.max(0,number(row,"duration_ms",0)));
    }
    private static void validate(Track track)throws IOException{if(track==null||track.provider()!=Provider.SPOTIFY||!validId(track.id()))throw failure("protocolChanged");}
    private static int nextOffset(JsonObject page,JsonArray items,int offset,int maximum)throws IOException{
        JsonElement next=page.get("next");if(next==null)throw failure("protocolChanged");if(next.isJsonNull())return -1;
        if(!next.isJsonPrimitive()||items.isEmpty())throw failure("protocolChanged");int value=offset+items.size();if(value>=maximum)throw failure("responseTooLarge");return value;
    }
    private static JsonArray requiredItems(JsonObject page)throws IOException{JsonElement items=page.get("items");if(items==null||!items.isJsonArray())throw failure("protocolChanged");return items.getAsJsonArray();}
    private static String image(JsonObject row){for(JsonElement element:array(row,"images"))if(element.isJsonObject()){String value=first(element.getAsJsonObject(),"url");try{URI uri=URI.create(value);if(MusicNetwork.allowed(Provider.SPOTIFY,uri))return uri.toString();}catch(RuntimeException ignored){}}return "";}
    private static JsonObject object(JsonObject row,String key){JsonElement value=row.get(key);return value!=null&&value.isJsonObject()?value.getAsJsonObject():new JsonObject();}
    private static JsonArray array(JsonObject row,String key){JsonElement value=row.get(key);return value!=null&&value.isJsonArray()?value.getAsJsonArray():new JsonArray();}
    private static String first(JsonObject row,String...keys){for(String key:keys){JsonElement value=row.get(key);if(value!=null&&value.isJsonPrimitive()&&!value.getAsString().isBlank())return value.getAsString();}return "";}
    private static long number(JsonObject row,String key,long fallback){try{return row.has(key)?row.get(key).getAsLong():fallback;}catch(RuntimeException ignored){return fallback;}}
    private static boolean bool(JsonObject row,String key,boolean fallback){try{return row.has(key)?row.get(key).getAsBoolean():fallback;}catch(RuntimeException ignored){return fallback;}}
    private static long clampPosition(long position,long duration){return Math.max(0,Math.min(Integer.MAX_VALUE,duration>0?Math.min(duration,position):position));}
    private static boolean validClient(String value){return value!=null&&value.matches("[a-fA-F0-9]{32}");}
    private static boolean validId(String value){return value!=null&&value.matches("[A-Za-z0-9]{22}");}
    private static boolean validToken(String value){return value!=null&&value.matches("[A-Za-z0-9._~+/=-]{1,4096}");}
    private static String random(){byte[] bytes=new byte[32];RANDOM.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static String challenge(String verifier)throws Exception{return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));}
    private static boolean constantEquals(String a,String b){return a!=null&&b!=null&&MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static String form(Map<String,String> values){StringJoiner result=new StringJoiner("&");values.forEach((k,v)->result.add(URLEncoder.encode(k,StandardCharsets.UTF_8)+"="+URLEncoder.encode(v,StandardCharsets.UTF_8)));return result.toString();}
    private static Map<String,String> map(String...pairs){Map<String,String> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i],pairs[i+1]);return result;}
    private static IOException failure(String name){return new IOException("ui.musicPlayer."+name);}
    private static IOException sanitized(Exception exception){String message=exception.getMessage();if(message!=null&&message.matches("ui\\.musicPlayer\\.[A-Za-z]+"))return new IOException(message);return failure("failed");}
    private static ThreadFactory daemon(String name){return task->{Thread thread=new Thread(task,name);thread.setDaemon(true);return thread;};}
    private record Callback(String code,String error){@Override public String toString(){return "SpotifyCallback[redacted]";}}
    private static final class Pending implements AutoCloseable {
        final long generation,deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(5);final String client,state=random(),verifier=random(),redirect;
        final AtomicReference<Callback> callback=new AtomicReference<>();final HttpServer server;final ThreadPoolExecutor handlers;final ScheduledFuture<?> expiry;
        Pending(long generation,String client)throws IOException{
            this.generation=generation;this.client=client;server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),4);
            redirect="http://127.0.0.1:"+server.getAddress().getPort()+"/callback";
            handlers=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(4),daemon("spotify-oauth-callback"));server.setExecutor(handlers);
            server.createContext("/callback",this::receive);server.start();expiry=EXPIRY.schedule(this::close,5,TimeUnit.MINUTES);
        }
        boolean expired(){return System.nanoTime()>deadline;}
        void receive(HttpExchange exchange)throws IOException{
            int status=400;String response="Spotify authorization was not accepted. Return to the game and try again.";
            try{
                String host=exchange.getRequestHeaders().getFirst("Host");URI uri=exchange.getRequestURI();String query=uri.getRawQuery();
                if(!expired()&&exchange.getRemoteAddress().getAddress().isLoopbackAddress()&&exchange.getRequestMethod().equals("GET")&&uri.getPath().equals("/callback")
                        &&("127.0.0.1:"+server.getAddress().getPort()).equals(host)&&query!=null&&query.length()<=8192){
                    Map<String,String> values=new HashMap<>();boolean duplicate=false;
                    for(String pair:query.split("&")){String[] parts=pair.split("=",2);if(parts.length!=2){duplicate=true;break;}String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8),value=URLDecoder.decode(parts[1],StandardCharsets.UTF_8);if(values.putIfAbsent(key,value)!=null){duplicate=true;break;}}
                    String code=values.getOrDefault("code",""),error=values.getOrDefault("error","");
                    if(!duplicate&&constantEquals(values.get("state"),state)&&((!code.isBlank()&&code.length()<=4096&&error.isEmpty())||code.isEmpty()&&!error.isBlank())
                            &&!code.chars().anyMatch(Character::isISOControl)){
                        if(callback.compareAndSet(null,new Callback(code,error))){status=200;response="Spotify authorization received. You may close this page and return to the game.";}else status=409;
                    }
                }
            }catch(RuntimeException malformed){status=400;}
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            try{exchange.sendResponseHeaders(status,bytes.length);try(OutputStream output=exchange.getResponseBody()){output.write(bytes);}}finally{exchange.close();}
        }
        @Override public void close(){server.stop(0);handlers.shutdownNow();ScheduledFuture<?> timeout=expiry;if(timeout!=null)timeout.cancel(false);}
    }
}
