package com.cheatprogram.media.player;

import com.cheatprogram.ai.CredentialStore;
import com.cheatprogram.core.Config;
import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.player.MusicModels.*;
import com.google.gson.*;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.regex.*;
import java.util.zip.InflaterInputStream;
import javax.crypto.Cipher;

/** Local compatibility client; protocol reference: github.com/MakcRe/KuGouMusicApi module/ and util/.
 * Requests go directly to Kugou. No proxy API, social-login intermediary, or substitute recording is used. */
public final class KugouMusicProvider implements MusicProvider {
    private static final String HOME = "https://www.kugou.com", GATEWAY = "https://gateway.kugou.com";
    private static final String WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt", APP_SALT = "OIlwieks28dk2k092lksi2UIkp";
    private static final String PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDIAG7QOELSYoIJvTFJhMpe1s/gbjDJX51HBNnEl5HXqTW6lQ7LC8jr9fWZTwusknp+sVGzwd40MwP6U5yDE27M/X1+UR4tvOGOqp94TJtQ1EPnWGWXngpeIW5GxoQGao1rmYWAu6oi1z9XkChrsUdC6DJE5E221wf/4WLFxwAtRQIDAQAB";
    private static final int PAGE_SIZE = 200, MAX_TRACKS = 20_000, MAX_LISTS = 5_000, MAX_JSON = 8 * 1024 * 1024;
    private static final Pattern KRC_LINE = Pattern.compile("(?m)^\\[(\\d+),(\\d+)](.*)$"), KRC_WORD = Pattern.compile("<(\\d+),(\\d+),\\d+>([^<]*)");
    private static final int[] KRC_KEY = {64,71,97,119,94,50,116,71,81,54,49,45,206,210,110,105};
    // v5 tracker quality values; proprietary tape/DSD/transcoding variants are not advertised.
    private static final List<Quality> QUALITIES=List.of(new Quality("128","标准音质"),new Quality("320","高品音质"),
            new Quality("flac","无损音质"),new Quality("high","Hi-Res音质"),
            new Quality("viper_clear","蝰蛇超清音质"),new Quality("viper_atmos","蝰蛇全景声"));
    @FunctionalInterface interface Transport { JsonObject request(URI uri, String method, Map<String,String> headers, byte[] body) throws Exception; }
    interface Secrets { String read(String name); void put(String name,String value); void delete(String name); }
    private static final class LocalSecrets implements Secrets {
        private final CredentialStore store = CredentialStore.open(Config.directory().resolve("music/credentials.dpapi"));
        public String read(String name) { return store.read("music.kugou."+name, HOME); }
        public void put(String name,String value) { store.put("music.kugou."+name, HOME, value); }
        public void delete(String name) { store.delete("music.kugou."+name); }
    }
    private final Transport transport;
    private final Secrets secrets;
    private final String device, mid, dev;
    private final Map<String,PlaylistRef> playlistRefs = new java.util.concurrent.ConcurrentHashMap<>();
    private String token = "", userid = "0", qrKey = "";
    private Account confirmed;
    private record PlaylistRef(String listid,int type,boolean liked) { }
    private record SongId(String hash,String album,String audio) { }

    public KugouMusicProvider() {
        this((uri,method,headers,body) -> MusicNetwork.json(Provider.KUGOU,uri,method,headers,body,MAX_JSON),new LocalSecrets());
    }
    KugouMusicProvider(Transport transport,Secrets secrets) {
        this.transport=Objects.requireNonNull(transport);this.secrets=Objects.requireNonNull(secrets);
        String savedDevice=secrets.read("device");device=savedDevice!=null&&savedDevice.matches("[a-f0-9]{32}")?savedDevice:UUID.randomUUID().toString().replace("-","");
        mid=new BigInteger(md5(device),16).toString();dev=device.substring(0,10).toUpperCase(Locale.ROOT);
        String savedToken=secrets.read("token"),savedUser=secrets.read("userid");
        if(safeToken(savedToken)&&validNumber(savedUser)&&!savedUser.equals("0")){token=savedToken;userid=savedUser;}
    }
    @Override public Provider provider(){return Provider.KUGOU;}
    @Override public String requirement(){return "ui.musicPlayer.compatibility";}
    @Override public Login beginLogin() throws Exception {
        logout();
        JsonObject result=request("https://login-user.kugou.com","/v2/qrcode","GET",map("appid",1001,"type",1,"plat",4,
                "qrcode_txt","https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=1005&","srcappid",2919),null,true,false,null);
        success(result);JsonObject data=object(result,"data");String key=first(data,"qrcode","key","qrcode_key");
        if(!key.matches("[A-Za-z0-9_=.-]{1,512}"))throw failure("protocolChanged");qrKey=key;
        String url="https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode="+encode(key);
        return new Login(key,url,MusicQr.png(url),"ui.musicPlayer.qrWaiting",false);
    }
    @Override public Login pollLogin(Login login) throws Exception {
        if(login==null||qrKey.isEmpty()||!qrKey.equals(login.key()))throw failure("qrExpired");
        JsonObject result=request("https://login-user.kugou.com","/v2/get_userinfo_qrcode","GET",
                map("plat",4,"appid",1005,"srcappid",2919,"qrcode",qrKey,"dev",dev),null,true,false,null);
        success(result);JsonObject data=object(result,"data");int phase=integer(data,"status",-1);
        if(phase==4){
            String nextToken=first(data,"token"),nextUser=first(data,"userid","user_id");
            if(!safeToken(nextToken)||!validNumber(nextUser)||nextUser.equals("0"))throw failure("protocolChanged");
            token=nextToken;userid=nextUser;
            try {
                Account upstream=account();if(upstream==null)throw failure("loginRequired");
                secrets.put("token",token);secrets.put("userid",userid);secrets.put("device",device);
            }catch(Exception failure){token="";userid="0";confirmed=null;throw failure;}
            return new Login(qrKey,login.url(),login.qrPng(),"ui.musicPlayer.connected",true);
        }
        String state=switch(phase){case 0->"qrExpired";case 1->"qrWaiting";case 2->"qrConfirm";default->throw failure("protocolChanged");};
        if(phase==0)qrKey="";
        return new Login(login.key(),login.url(),login.qrPng(),"ui.musicPlayer."+state,false);
    }
    @Override public Account account() throws Exception {
        confirmed=null;if(token.isEmpty()||userid.equals("0"))return null;
        long now=System.currentTimeMillis()/1000;
        JsonObject secret=json("token",token,"clienttime",now);
        JsonObject result=request(GATEWAY,"/v3/get_my_info","POST",map("plat",1),
                json("visit_time",now,"usertype",1,"p",rsa(secret.toString()),"userid",Long.parseLong(userid)),false,true,"usercenter.kugou.com");
        success(result);JsonObject data=object(result,"data");
        if(data.has("userinfo")&&data.get("userinfo").isJsonObject())data=object(data,"userinfo");
        String returned=first(data,"userid","user_id"),name=first(data,"nickname","nick_name","user_name","username");
        if((!returned.isBlank()&&!returned.equals(userid))||name.isBlank())throw failure("loginRequired");
        confirmed=new Account(userid,name,asset(first(data,"pic","avatar","headurl")));return confirmed;
    }
    @Override public List<Track> liked() throws Exception {
        List<Playlist> all=playlists();
        for(Playlist playlist:all){PlaylistRef ref=playlistRefs.get(playlist.id());if(ref!=null&&ref.liked())return tracks(playlist);}
        return List.of();
    }
    @Override public List<Playlist> playlists() throws Exception {
        requireAccount();playlistRefs.clear();LinkedHashMap<String,Playlist> all=new LinkedHashMap<>();
        for(int page=1;all.size()<MAX_LISTS;page++){
            JsonObject result=request(GATEWAY,"/v7/get_all_list","POST",map("plat",1,"userid",userid,"token",token),
                    json("userid",Long.parseLong(userid),"token",token,"total_ver",979,"type",2,"page",page,"pagesize",PAGE_SIZE),false,true,"cloudlist.service.kugou.com");
            success(result);JsonObject data=object(result,"data");JsonArray items=requiredArray(data,"info","lists","list");int added=0;
            for(JsonElement item:items){if(!item.isJsonObject())continue;JsonObject row=item.getAsJsonObject();
                String id=first(row,"listid"),name=first(row,"name","listname");if(!validNumber(id)||name.isBlank())continue;
                int type=integer(row,"type",0);if(type!=0&&type!=1)continue;String key=id+":"+type;
                boolean own=!row.has("list_create_userid")||first(row,"list_create_userid").equals(userid);
                boolean liked=own&&Set.of("我喜欢","我喜欢的音乐","我喜歡","我喜歡的音樂").contains(name);
                PlaylistRef ref=new PlaylistRef(id,type,liked);
                if(!all.containsKey(key)){all.put(key,new Playlist(key,name,asset(first(row,"pic","cover")),Math.max(0,integer(row,"count",0))));added++;}
                playlistRefs.put(key,ref);if(all.size()>=MAX_LISTS)break;
            }
            long total=number(data,"total",-1);if(items.size()<PAGE_SIZE||added==0||total>0&&all.size()>=total)break;
        }
        return List.copyOf(all.values());
    }
    @Override public List<Track> tracks(Playlist playlist) throws Exception {
        requireAccount();PlaylistRef ref=playlistRefs.get(playlist.id());
        if(ref==null)throw failure("protocolChanged");
        LinkedHashMap<String,Track> all=new LinkedHashMap<>();
        for(int page=1;all.size()<MAX_TRACKS;page++){
            JsonObject result=request(GATEWAY,"/v4/get_list_all_file_v3","POST",Map.of(),
                    json("listid",Long.parseLong(ref.listid()),"userid",Long.parseLong(userid),"type",ref.type(),"page",page,"pagesize",PAGE_SIZE,
                            "area_code",1,"allplatform",1,"show_cover",1,"token",token),false,true,"cloudlist.service.kugou.com");
            success(result);JsonObject data=object(result,"data");JsonArray items=requiredArray(data,"info","songs","files","list");int added=0;
            for(JsonElement item:items){if(!item.isJsonObject())continue;Track track=track(item.getAsJsonObject());
                if(track!=null&&!all.containsKey(track.id())){all.put(track.id(),track);added++;}if(all.size()>=MAX_TRACKS)break;}
            long total=number(data,"count",number(data,"total",-1));
            if(items.size()<PAGE_SIZE||added==0||total>0&&all.size()>=total)break;
        }
        return List.copyOf(all.values());
    }
    @Override public LrcLyrics lyrics(Track track) throws Exception {
        SongId id=songId(track);
        JsonObject found=request("https://lyrics.kugou.com","/v1/search","GET",
                map("album_audio_id",id.audio(),"appid",1005,"clientver",20489,"duration",track.durationMs(),"hash",id.hash(),"keyword","","lrctxt",1,"man","no"),null,false,false,null,true);
        success(found);JsonArray candidates=array(found,"candidates");if(candidates.isEmpty()&&found.has("data"))candidates=array(object(found,"data"),"candidates");
        for(JsonElement value:candidates){if(!value.isJsonObject())continue;JsonObject candidate=value.getAsJsonObject();
            String suppliedHash=first(candidate,"hash");if(!suppliedHash.isBlank()&&!suppliedHash.equalsIgnoreCase(id.hash()))continue;
            String lyricId=first(candidate,"id"),access=first(candidate,"accesskey");if(!validNumber(lyricId)||!safeToken(access))continue;
            JsonObject result=request("https://lyrics.kugou.com","/download","GET",map("ver",1,"client","android","id",lyricId,"accesskey",access,"fmt","krc","charset","utf8"),null,false,false,null);
            success(result);String content=first(result,"content");if(content.isBlank()&&result.has("data"))content=first(object(result,"data"),"content");
            if(content.isBlank())return LrcLyrics.parse("");return decodeLyrics(content);
        }
        return LrcLyrics.parse("");
    }
    @Override public Media media(Track track) throws Exception {
        return media(track,"128",false);
    }
    @Override public List<Quality> qualities(Track track){return track==null||track.provider()==provider()?QUALITIES:List.of();}
    @Override public Media media(Track track,String quality)throws Exception{
        if(quality==null||quality.isEmpty())return media(track);
        if(qualityLabel(quality).isEmpty())throw failure("qualityUnavailable");return media(track,quality,true);
    }
    private Media media(Track track,String quality,boolean explicit) throws Exception {
        requireAccount();SongId id=songId(track);
        Map<String,String> params=map("album_id",id.album(),"area_code",1,"hash",id.hash(),"ssa_flag","is_fromtrack","version",11430,
                "page_id",151369488,"quality",quality,"album_audio_id",id.audio(),"behavior","play","pid",2,"cmd",26,"pidversion",3001,
                "IsFreePart",0,"ppage_id","463467626,350369493,788954147","cdnBackup",1,"module","","clientver",11430);
        params.put("key",md5(id.hash()+"57ae12eb6890223e355ccfcb74edf70d1005"+mid+userid));
        JsonObject result=request(GATEWAY,"/v5/url","GET",params,null,false,true,"trackercdn.kugou.com");success(result);
        JsonObject data=result.has("data")&&result.get("data").isJsonObject()?object(result,"data"):result;
        if(integer(data,"is_free_part",0)!=0||integer(data,"IsFreePart",0)!=0||integer(data,"isfreepart",0)!=0||integer(data,"free_part",0)!=0
                ||number(data,"start_time",0)>0||number(data,"end_time",0)>0)throw failure("rightsRequired");
        JsonObject variant=new JsonObject();String returnedHash=first(data,"hash");
        if(!returnedHash.isBlank()&&!returnedHash.equalsIgnoreCase(id.hash())){
            if(!explicit)throw failure("protocolChanged");variant=verifyVariant(id,returnedHash);
        }
        String actualQuality=qualityLabel(first(data,"quality"));
        if(actualQuality.isBlank())actualQuality=qualityLabel(first(variant,"quality"));
        String returnedFormat=first(data,"extName","extname").toLowerCase(Locale.ROOT);
        if(returnedFormat.isBlank())returnedFormat=first(object(variant,"info"),"extname").toLowerCase(Locale.ROOT);
        List<String> urls=new ArrayList<>();for(String key:List.of("url","backup_url","backupUrl")){
            JsonElement value=data.get(key);if(value==null)continue;if(value.isJsonArray()){for(JsonElement item:value.getAsJsonArray())if(item.isJsonPrimitive())urls.add(item.getAsString());}
            else if(value.isJsonPrimitive())urls.add(value.getAsString());}
        for(String url:urls){String safe=asset(url);if(!safe.isEmpty()){
            String format=returnedFormat;if(format.isBlank()){String path=URI.create(safe).getPath();int dot=path.lastIndexOf('.');if(dot>=0)format=path.substring(dot+1).toLowerCase(Locale.ROOT);}
            if(!format.isBlank()&&!Set.of("mp3","flac","m4a","aac","ogg","opus","mp4","wav","webm").contains(format))throw failure("qualityUnavailable");
            return new Media(safe,format.isBlank()&&!explicit?"mp3":format,actualQuality);
        }}
        throw failure("rightsRequired");
    }
    /** A higher-quality encoding has its own hash. Verify the platform's exact recording family. */
    private JsonObject verifyVariant(SongId id,String hash)throws Exception{
        if(!hash.matches("(?i)[a-f0-9]{32}"))throw failure("qualityUnavailable");
        JsonObject body=json("appid",1005,"area_code",1,"behavior","play","clientver",20489,"need_hash_offset",1,"relate",1,"support_verify",1);
        JsonArray resource=new JsonArray();resource.add(json("type","audio","page_id",0,"hash",id.hash(),"album_id",Long.parseLong(id.album())));body.add("resource",resource);
        JsonArray qualities=new JsonArray();for(Quality tier:QUALITIES)qualities.add(tier.id());body.add("qualities",qualities);
        JsonObject result=request(GATEWAY,"/v2/get_res_privilege/lite","POST",Map.of(),body,false,true,"media.store.kugou.com");success(result);
        for(JsonElement value:array(result,"data")){if(!value.isJsonObject())continue;JsonObject row=value.getAsJsonObject();if(!id.hash().equalsIgnoreCase(first(row,"hash")))continue;
            for(JsonElement related:array(row,"relate_goods")){if(!related.isJsonObject())continue;JsonObject item=related.getAsJsonObject();
                if(hash.equalsIgnoreCase(first(item,"hash"))&&integer(item,"status",1)!=0&&!qualityLabel(first(item,"quality")).isBlank())return item;
            }
        }
        throw failure("qualityUnavailable");
    }
    private static String qualityLabel(String id){return QUALITIES.stream().filter(q->q.id().equals(id)).map(Quality::label).findFirst().orElse("");}
    @Override public void logout(){token="";userid="0";qrKey="";confirmed=null;playlistRefs.clear();
        secrets.delete("token");secrets.delete("userid");secrets.delete("device");MusicNetwork.clearCookies(provider());}
    private void requireAccount() throws Exception {if(confirmed==null&&account()==null)throw failure("loginRequired");}
    private JsonObject request(String base,String path,String method,Map<String,String> additions,JsonObject body,boolean web,boolean auth,String router)throws Exception{
        return request(base,path,method,additions,body,web,auth,router,false);
    }
    private JsonObject request(String base,String path,String method,Map<String,String> additions,JsonObject body,boolean web,boolean auth,String router,boolean onlyParams)throws Exception{
        long now=System.currentTimeMillis()/1000;TreeMap<String,String> params=new TreeMap<>();
        if(!onlyParams){params.putAll(map("dfid","-","mid",mid,"uuid","-","appid",1005,"clientver",20489,"clienttime",now));}
        if(auth){if(token.isEmpty()||userid.equals("0"))throw failure("loginRequired");params.put("token",token);params.put("userid",userid);}
        params.putAll(additions);String payload=body==null?"":body.toString();String salt=web?WEB_SALT:APP_SALT;
        StringBuilder signature=new StringBuilder(salt);params.forEach((key,value)->signature.append(key).append('=').append(value));signature.append(payload).append(salt);
        params.put("signature",md5(signature.toString()));StringJoiner query=new StringJoiner("&");params.forEach((key,value)->query.add(encode(key)+"="+encode(value)));
        URI uri=URI.create(base+path+"?"+query);if(!MusicNetwork.credentialHost(provider(),uri))throw failure("hostBlocked");
        Map<String,String> headers=new LinkedHashMap<>(Map.of("User-Agent","Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi","mid",mid,"dfid","-","clienttime",Long.toString(now),
                "kg-rc","1","kg-thash","5d816a0","kg-rec","1","kg-rf","B9EDA08A64250DEFFBCADDEE00F8F25F"));
        if(router!=null)headers.put("x-router",router);if(body!=null)headers.put("Content-Type","application/json;charset=utf-8");
        return transport.request(uri,method,Map.copyOf(headers),body==null?null:payload.getBytes(StandardCharsets.UTF_8));
    }
    private static Track track(JsonObject row){
        JsonObject audio=object(row,"audio_info"),base=object(row,"base"),album=object(row,"albuminfo");if(album.size()==0)album=object(row,"album_info");
        String hash=first(row,"hash","filehash","FileHash");if(hash.isBlank())hash=first(audio,"hash");if(!hash.matches("(?i)[a-f0-9]{32}"))return null;
        String albumId=first(row,"album_id","albumid");if(!validNumber(albumId))albumId=first(album,"album_id","id");if(!validNumber(albumId))albumId="0";
        String audioId=first(row,"album_audio_id","mixsongid");if(!validNumber(audioId))audioId="0";
        String name=first(row,"audio_name","songname","song_name","name","filename"),artist=first(row,"author_name","singername","singer_name");
        if(name.isBlank())name=first(base,"audio_name");if(artist.isBlank())artist=first(base,"author_name");
        int split=name.indexOf(" - ");if(split>0&&artist.isBlank()){artist=name.substring(0,split);name=name.substring(split+3);}if(name.isBlank())return null;
        String cover=first(row,"cover","sizable_cover","pic");if(cover.isBlank())cover=first(object(row,"trans_param"),"union_cover");
        long duration=number(row,"timelen",number(row,"timelength",number(row,"duration",number(audio,"duration",0))));
        return new Track(Provider.KUGOU,hash.toLowerCase(Locale.ROOT)+":"+albumId+":"+audioId,name,artist,first(album,"name","album_name"),asset(cover),Math.max(0,duration));
    }
    private static SongId songId(Track track)throws IOException{
        if(track==null||track.provider()!=Provider.KUGOU)throw failure("protocolChanged");String[] parts=track.id().split(":",-1);
        if(parts.length!=3||!parts[0].matches("[a-fA-F0-9]{32}")||!validNumber(parts[1])||!validNumber(parts[2]))throw failure("protocolChanged");
        return new SongId(parts[0].toLowerCase(Locale.ROOT),parts[1],parts[2]);
    }
    static LrcLyrics decodeLyrics(String content)throws IOException{
        if(content.length()>3_000_000)throw failure("responseTooLarge");byte[] bytes;
        try{bytes=Base64.getDecoder().decode(content);}catch(IllegalArgumentException bad){throw failure("protocolChanged");}
        if(bytes.length>=4&&bytes[0]=='k'&&bytes[1]=='r'&&bytes[2]=='c'&&bytes[3]=='1'){
            byte[] zipped=Arrays.copyOfRange(bytes,4,bytes.length);for(int i=0;i<zipped.length;i++)zipped[i]^=(byte)KRC_KEY[i%KRC_KEY.length];
            try(InputStream inflater=new InflaterInputStream(new ByteArrayInputStream(zipped))){bytes=inflater.readNBytes(2_000_001);if(bytes.length>2_000_000)throw failure("responseTooLarge");}
            String krc=new String(bytes,StandardCharsets.UTF_8);StringBuilder lrc=new StringBuilder();Matcher lines=KRC_LINE.matcher(krc);int count=0;
            while(lines.find()){
                if(++count>20_000)throw failure("responseTooLarge");long start=Long.parseLong(lines.group(1)),duration=Long.parseLong(lines.group(2));
                lrc.append('[').append(stamp(start)).append(']');Matcher words=KRC_WORD.matcher(lines.group(3));boolean hasWords=false;
                while(words.find()){hasWords=true;lrc.append('<').append(stamp(start+Long.parseLong(words.group(1)))).append('>').append(words.group(3));}
                if(!hasWords)lrc.append(lines.group(3));lrc.append('<').append(stamp(start+duration)).append('>').append('\n');
            }
            return LrcLyrics.parse(lrc.toString());
        }
        return LrcLyrics.parse(new String(bytes,StandardCharsets.UTF_8));
    }
    private static String stamp(long ms){long value=Math.max(0,Math.min(59_999_999,ms));return String.format(Locale.ROOT,"%02d:%02d.%03d",value/60000,value/1000%60,value%1000);}
    private static String rsa(String message)throws Exception{
        byte[] source=message.getBytes(StandardCharsets.UTF_8);if(source.length>128)throw failure("protocolChanged");byte[] padded=Arrays.copyOf(source,128);
        Cipher cipher=Cipher.getInstance("RSA/ECB/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY))));
        try{return HexFormat.of().withUpperCase().formatHex(cipher.doFinal(padded));}finally{Arrays.fill(padded,(byte)0);Arrays.fill(source,(byte)0);}
    }
    private static String asset(String source){
        try{String url=Objects.requireNonNullElse(source,"").replace("{size}","480");if(url.startsWith("//"))url="https:"+url;if(url.startsWith("http://"))url="https://"+url.substring(7);
            URI uri=URI.create(url);return MusicNetwork.allowed(Provider.KUGOU,uri)?uri.toString():"";}catch(RuntimeException bad){return "";}
    }
    private static void success(JsonObject result)throws IOException{
        if(result==null)throw failure("protocolChanged");
        int error=integer(result,"error_code",integer(result,"errcode",0)),status=integer(result,"status",-1);
        if(error!=0&&!(status==200&&error==200)||status!=1&&status!=200)throw failure(Set.of(20010,20011,20012,20014,20017).contains(error)?"loginRequired":"protocolChanged");
    }
    private static JsonObject object(JsonObject parent,String key){JsonElement value=parent.get(key);return value!=null&&value.isJsonObject()?value.getAsJsonObject():new JsonObject();}
    private static JsonArray array(JsonObject parent,String...keys){for(String key:keys){JsonElement value=parent.get(key);if(value!=null&&value.isJsonArray())return value.getAsJsonArray();}return new JsonArray();}
    private static JsonArray requiredArray(JsonObject parent,String...keys)throws IOException{for(String key:keys){JsonElement value=parent.get(key);if(value!=null&&value.isJsonArray())return value.getAsJsonArray();}throw failure("protocolChanged");}
    private static String first(JsonObject parent,String...keys){for(String key:keys){JsonElement value=parent.get(key);if(value!=null&&value.isJsonPrimitive()){String text=value.getAsString();if(!text.isBlank())return text;}}return "";}
    private static long number(JsonObject parent,String key,long fallback){try{return parent.has(key)?parent.get(key).getAsLong():fallback;}catch(RuntimeException bad){return fallback;}}
    private static int integer(JsonObject parent,String key,int fallback){long value=number(parent,key,fallback);return value<Integer.MIN_VALUE||value>Integer.MAX_VALUE?fallback:(int)value;}
    private static boolean safeToken(String value){return value!=null&&value.matches("[A-Za-z0-9._~+/=-]{1,4096}");}
    private static boolean validNumber(String value){if(value==null||!value.matches("[0-9]{1,18}"))return false;try{return Long.parseLong(value)>=0;}catch(NumberFormatException bad){return false;}}
    private static String md5(String source){try{return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(source.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20");}
    private static Map<String,String> map(Object...pairs){Map<String,String> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i].toString(),pairs[i+1].toString());return result;}
    private static JsonObject json(Object...pairs){JsonObject result=new JsonObject();for(int i=0;i<pairs.length;i+=2){Object value=pairs[i+1];if(value instanceof Number number)result.addProperty(pairs[i].toString(),number);else result.addProperty(pairs[i].toString(),value.toString());}return result;}
    private static IOException failure(String name){return new IOException("ui.musicPlayer."+name);}
}
