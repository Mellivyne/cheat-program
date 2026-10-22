package com.cheatprogram.media.player;

import com.cheatprogram.media.player.MusicModels.Provider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** First-party-only transport. There is no public proxy, ambient cookie jar, or telemetry endpoint. */
public final class MusicNetwork {
    private static final ProxySelector DIRECT=new ProxySelector(){
        @Override public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
        @Override public void connectFailed(URI uri,SocketAddress address,IOException failure){ }
    };
    private static final HttpClient CLIENT=HttpClient.newBuilder().proxy(DIRECT).followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(8)).build();
    private static final Map<Provider,Map<String,String>> COOKIES=new ConcurrentHashMap<>();
    private static final java.util.concurrent.ScheduledExecutorService BODY_DEADLINES=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"music-bounded-http");thread.setDaemon(true);return thread;});
    private MusicNetwork(){ }
    public static boolean allowed(Provider provider,URI uri){
        if(provider==null||uri==null||!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null
                ||uri.getRawUserInfo()!=null||uri.getFragment()!=null||uri.getPort()!=-1&&uri.getPort()!=443)return false;
        String host=uri.getHost().toLowerCase(Locale.ROOT);
        if(host.endsWith(".")||host.indexOf(':')>=0||host.matches("[0-9.]+"))return false;
        return apiHost(provider,host)||switch(provider){
            case NETEASE->under(host,"music.126.net");
            case QQ->under(host,"qpic.cn")||under(host,"gtimg.cn")||under(host,"qqmusic.qq.com");
            case KUGOU->under(host,"kgimg.com")||under(host,"kugoucdn.com");
            case QISHUI->under(host,"douyincdn.com")||under(host,"byteimg.com")||under(host,"bytecdn.cn");
            case KUWO->under(host,"kuwo.cn")||under(host,"kuwo.com");
            case SPOTIFY->under(host,"scdn.co");
        };
    }
    private static boolean under(String host,String suffix){return host.equals(suffix)||host.endsWith("."+suffix);}
    private static boolean apiHost(Provider provider,String host){return switch(provider){
        case NETEASE->host.equals("music.163.com")||host.equals("interface.music.163.com")||host.equals("interfacepc.music.163.com");
        case QQ->host.equals("y.qq.com")||host.equals("c.y.qq.com")||host.equals("c6.y.qq.com")||host.equals("u.y.qq.com")||host.equals("s.y.qq.com")
                ||host.equals("ssl.ptlogin2.qq.com")||host.equals("xui.ptlogin2.qq.com")||host.equals("ptlogin2.qq.com")||host.equals("graph.qq.com")||host.equals("ssl.ptlogin2.graph.qq.com");
        case KUGOU->under(host,"kugou.com");
        case QISHUI->host.equals("qishui.douyin.com")||host.equals("music.douyin.com")||host.equals("api.qishui.com")||host.equals("api.qishui.douyin.com");
        case KUWO->host.equals("www.kuwo.cn")||host.equals("kuwo.cn")||host.equals("m.kuwo.cn")||host.equals("wapi.kuwo.cn")||host.equals("nplserver.kuwo.cn");
        case SPOTIFY->host.equals("accounts.spotify.com")||host.equals("api.spotify.com")||host.equals("open.spotify.com");
    };}
    public static boolean credentialHost(Provider provider,URI uri){return allowed(provider,uri)&&apiHost(provider,uri.getHost().toLowerCase(Locale.ROOT));}
    public static Map<String,String> cookies(Provider provider){Map<String,String> values=COOKIES.get(provider);return values==null?Map.of():Map.copyOf(values);}
    public static void clearCookies(Provider provider){COOKIES.remove(provider);}
    private static void captureCookies(Provider provider,URI uri,HttpHeaders headers){
        if(!credentialHost(provider,uri))return;
        for(String header:headers.allValues("Set-Cookie"))try{
            for(HttpCookie cookie:HttpCookie.parse(header)){
                String domain=cookie.getDomain();if(domain!=null){while(domain.startsWith("."))domain=domain.substring(1);if(!under(uri.getHost().toLowerCase(Locale.ROOT),domain.toLowerCase(Locale.ROOT)))continue;}
                if(!cookie.getName().matches("[A-Za-z0-9_.$-]{1,128}")||cookie.getValue().length()>8192)continue;
                Map<String,String> values=COOKIES.computeIfAbsent(provider,p->new ConcurrentHashMap<>());
                if(cookie.hasExpired())values.remove(cookie.getName());else if(values.size()<128||values.containsKey(cookie.getName()))values.put(cookie.getName(),cookie.getValue());
            }
        }catch(IllegalArgumentException ignored){ }
    }
    private static HttpRequest request(Provider provider,URI uri,String method,Map<String,String> headers,byte[] body)throws IOException{
        return request(provider,uri,method,headers,body,false);
    }
    private static HttpRequest request(Provider provider,URI uri,String method,Map<String,String> headers,byte[] body,boolean streaming)throws IOException{
        if(!allowed(provider,uri))throw new IOException("ui.musicPlayer.hostBlocked");
        HttpRequest.Builder builder=HttpRequest.newBuilder(uri).header("User-Agent","CheatProgramMusic/1.0");
        if(!streaming)builder.timeout(Duration.ofSeconds(20));
        for(var entry:headers.entrySet()){
            String name=entry.getKey().toLowerCase(Locale.ROOT),value=entry.getValue();
            if(name.equals("proxy-authorization")||name.equals("proxy-connection"))throw new IOException("ui.musicPlayer.hostBlocked");
            if((name.equals("authorization")||name.equals("cookie"))&&!credentialHost(provider,uri))throw new IOException("ui.musicPlayer.hostBlocked");
            if(name.equals("referer")||name.equals("origin")){try{if(!allowed(provider,URI.create(value)))throw new IOException("ui.musicPlayer.hostBlocked");}catch(IllegalArgumentException bad){throw new IOException("ui.musicPlayer.hostBlocked");}}
            builder.setHeader(entry.getKey(),value);
        }
        return builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }
    public static JsonObject json(Provider provider,URI uri,String method,Map<String,String> headers,byte[] body,int limit)throws IOException,InterruptedException{
        Response response=response(provider,uri,method,headers,body,limit);
        if(response.status()<200||response.status()>=300)throw status(response.status());
        try{return JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8)).getAsJsonObject();}
        catch(RuntimeException invalid){throw new IOException("ui.musicPlayer.protocolChanged");}
    }
    public record Response(int status,Map<String,List<String>> headers,byte[] body){
        public Response {headers=Map.copyOf(headers);body=body.clone();}
        @Override public byte[] body(){return body.clone();}
        @Override public String toString(){return "MusicNetwork.Response[status="+status+"]";}
    }
    /** Manual redirects are inspectable without ever forwarding credentials automatically. */
    public static Response response(Provider provider,URI uri,String method,Map<String,String> headers,byte[] body,int limit)throws IOException,InterruptedException{
        var response=CLIENT.send(request(provider,uri,method,headers,body),HttpResponse.BodyHandlers.ofInputStream());
        captureCookies(provider,uri,response.headers());
        try(InputStream input=response.body()){return new Response(response.statusCode(),response.headers().map(),bounded(input,limit));}
    }
    public static byte[] bytes(Provider provider,URI uri,Map<String,String> headers,int limit)throws IOException,InterruptedException{
        try(InputStream input=stream(provider,uri,headers)){return bounded(input,limit);}
    }
    public static InputStream stream(Provider provider,URI uri,Map<String,String> headers)throws IOException,InterruptedException{
        URI current=uri;boolean hasCredentials=headers.keySet().stream().anyMatch(k->k.equalsIgnoreCase("Cookie")||k.equalsIgnoreCase("Authorization"));
        for(int redirects=0;redirects<=4;redirects++){
            // A song can last far longer than the bounded API request deadline. Bound header
            // acquisition independently, then let the playback progress watchdog own its lifetime.
            var pending=CLIENT.sendAsync(request(provider,current,"GET",headers,null,true),HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<InputStream> response;
            try{response=pending.get(20,java.util.concurrent.TimeUnit.SECONDS);}
            catch(java.util.concurrent.TimeoutException timeout){pending.cancel(true);throw new IOException("ui.musicPlayer.networkFailed");}
            catch(java.util.concurrent.ExecutionException failed){throw new IOException("ui.musicPlayer.networkFailed");}
            catch(InterruptedException cancelled){pending.cancel(true);throw cancelled;}
            captureCookies(provider,current,response.headers());
            int status=response.statusCode();
            if(status>=300&&status<400){response.body().close();if(hasCredentials)throw new IOException("ui.musicPlayer.hostBlocked");
                String location=response.headers().firstValue("Location").orElse("");try{current=current.resolve(location);}catch(IllegalArgumentException invalid){throw new IOException("ui.musicPlayer.hostBlocked");}
                if(location.isEmpty()||!allowed(provider,current))throw new IOException("ui.musicPlayer.hostBlocked");continue;}
            if(status<200||status>=300){response.body().close();throw status(status);}
            return response.body();
        }
        throw new IOException("ui.musicPlayer.hostBlocked");
    }
    private static byte[] bounded(InputStream input,int limit)throws IOException{
        var deadline=BODY_DEADLINES.schedule(()->{try{input.close();}catch(IOException ignored){}},20,java.util.concurrent.TimeUnit.SECONDS);
        try{int maximum=Math.max(1,Math.min(16*1024*1024,limit));byte[] bytes=input.readNBytes(maximum+1);
            if(bytes.length>maximum)throw new IOException("ui.musicPlayer.responseTooLarge");return bytes;
        }finally{deadline.cancel(false);}
    }
    private static IOException status(int status){return new IOException(status==401||status==403?"ui.musicPlayer.loginRequired":status==429?"ui.musicPlayer.rateLimited":"ui.musicPlayer.networkFailed");}
}
