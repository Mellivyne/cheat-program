package com.cheatprogram.media.player;

import com.cheatprogram.core.Config;
import com.cheatprogram.media.player.MusicModels.Login;
import com.cheatprogram.media.player.MusicModels.Provider;
import com.cheatprogram.media.player.MusicModels.Track;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Shared public album artwork; QR images stay local to their owning screen. */
public final class MusicArtworkCache implements AutoCloseable {
    private static final int LIMIT=128,MAX_BYTES=3*1024*1024,DISK_FILES=2048;
    private static final long DISK_BYTES=128L*1024*1024,MAX_AGE=TimeUnit.DAYS.toMillis(30);
    private static final Object PUBLIC_LOCK=new Object();
    private static final Map<String,Entry> PUBLIC=new LinkedHashMap<>(160,.75f,true);
    private static volatile long epoch;
    private static long sequence;
    private final Minecraft mc=Minecraft.getInstance();
    private final Map<String,Entry> qrEntries=new LinkedHashMap<>(4,.75f,true);
    private static final LinkedBlockingDeque<Runnable> REQUESTS=new LinkedBlockingDeque<>(48);
    private static final ThreadPoolExecutor WORKER=new ThreadPoolExecutor(3,3,10,TimeUnit.SECONDS,REQUESTS,task->{
        Thread thread=new Thread(task,"Music artwork");thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.AbortPolicy());
    private static final ThreadPoolExecutor QR_WORKER=new ThreadPoolExecutor(1,1,10,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),task->{
        Thread thread=new Thread(task,"Music login image");thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.AbortPolicy());
    private boolean closed;
    private List<Track> idleTracks=List.of();private int idleCenter=-1,idleStep=9;private long nextIdle;
    private static final class Entry {Identifier texture;Runnable task;boolean pending;int failures;long retryAt,epoch;}

    /** Called only by the render thread; the network policy independently validates provider hosts. */
    public Identifier artwork(Provider provider,String url){
        if(closed||url==null||url.isBlank())return null;
        URI uri=publicUri(provider,url);if(uri==null)return null;String key=provider.id+":"+uri;
        synchronized(PUBLIC_LOCK){Entry entry=PUBLIC.get(key);if(entry==null){entry=new Entry();entry.epoch=epoch;PUBLIC.put(key,entry);trimPublic();}
            if(entry.texture==null&&!entry.pending&&System.currentTimeMillis()>=entry.retryAt){long requestEpoch=entry.epoch;request(PUBLIC,key,entry,()->publicBytes(provider,uri,key,requestEpoch),true,true);}
            return entry.texture;}
    }
    /** A far-zoom mosaic may display thousands of covers; drawing must not enqueue them all. */
    public Identifier cachedArtwork(Provider provider,String url){
        if(closed||url==null||url.isBlank())return null;URI uri=publicUri(provider,url);if(uri==null)return null;
        synchronized(PUBLIC_LOCK){Entry entry=PUBLIC.get(provider.id+":"+uri);return entry==null?null:entry.texture;}
    }
    /** Center first, then nearest neighbors; never walk a whole account library. */
    public void preload(List<Track> tracks,int center,int radius){if(closed||tracks==null||tracks.isEmpty())return;int selected=Math.max(0,Math.min(tracks.size()-1,center));Track focus=tracks.get(selected);preload(focus);prioritize(focus);for(int step=1;step<=Math.max(0,Math.min(8,radius));step++){if(selected+step<tracks.size())preload(tracks.get(selected+step));if(selected-step>=0)preload(tracks.get(selected-step));}}
    /** Stationary views warm their next covers in small batches after visible requests drain. */
    public void preloadIdle(List<Track> tracks,int center){
        if(closed||tracks==null||tracks.isEmpty())return;int selected=Math.max(0,Math.min(tracks.size()-1,center));
        if(idleTracks!=tracks||idleCenter!=selected){idleTracks=tracks;idleCenter=selected;idleStep=9;nextIdle=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(350);}
        long now=System.nanoTime();if(now<nextIdle||!REQUESTS.isEmpty()||WORKER.getActiveCount()!=0)return;nextIdle=now+TimeUnit.MILLISECONDS.toNanos(250);
        int requested=0;while(requested<2&&idleStep<=88){int step=idleStep++,offset=step<=64?step:-(step-56);int index=selected+offset;if(index<0||index>=tracks.size())continue;preload(tracks.get(index));requested++;}
    }
    private void preload(Track track){if(track!=null)artwork(track.provider(),track.cover());}
    private static void prioritize(Track track){if(track==null)return;URI uri=publicUri(track.provider(),track.cover());if(uri==null)return;synchronized(PUBLIC_LOCK){Entry entry=PUBLIC.get(track.provider().id+":"+uri);if(entry!=null&&entry.task!=null&&REQUESTS.remove(entry.task)&&!REQUESTS.offerFirst(entry.task)){entry.pending=false;entry.retryAt=0;}}}
    public boolean unavailable(Provider provider,String url){URI uri=publicUri(provider,url);if(uri==null)return true;synchronized(PUBLIC_LOCK){Entry entry=PUBLIC.get(provider.id+":"+uri);return entry!=null&&entry.texture==null&&entry.failures>0;}}
    public Identifier login(Login login){
        if(closed||login==null)return null;byte[] bytes=login.qrPng();if(bytes.length==0||bytes.length>MAX_BYTES)return null;
        String key="qr:"+Arrays.hashCode(bytes);Entry prior=qrEntries.get(key);if(prior!=null)return prior.texture;
        Entry entry=new Entry();qrEntries.put(key,entry);while(qrEntries.size()>2){String eldest=qrEntries.keySet().iterator().next();release(qrEntries.remove(eldest));}
        request(qrEntries,key,entry,()->bytes,false,false);return null;
    }
    private interface Bytes {byte[] get() throws Exception;}
    private void request(Map<String,Entry> entries,String key,Entry entry,Bytes source,boolean crop,boolean shared){
        entry.pending=true;
        Runnable task=()->{
            NativeImage pixels=null;
            try{pixels=decode(source.get(),crop);NativeImage decoded=pixels;mc.execute(()->{synchronized(PUBLIC_LOCK){upload(entries,key,entry,decoded,shared);}});}
            catch(Exception|OutOfMemoryError failed){if(pixels!=null)pixels.close();mc.execute(()->{synchronized(PUBLIC_LOCK){if(entries.get(key)==entry){entry.pending=false;entry.task=null;entry.failures=Math.min(6,entry.failures+1);entry.retryAt=System.currentTimeMillis()+Math.min(60000,1000L<<entry.failures);}}});}
        };entry.task=task;
        try{(shared?WORKER:QR_WORKER).execute(task);}catch(java.util.concurrent.RejectedExecutionException full){entry.pending=false;entry.retryAt=System.currentTimeMillis()+250;}
    }
    private static URI publicUri(Provider provider,String url){if(provider==null||url==null||url.isBlank()||url.length()>16384)return null;try{URI uri=URI.create(url.startsWith("//")?"https:"+url:url);if("http".equalsIgnoreCase(uri.getScheme()))uri=URI.create("https"+uri.toString().substring(4));if(!MusicNetwork.allowed(provider,uri))return null;String host=uri.getHost().toLowerCase(Locale.ROOT),query=uri.getRawQuery();
        // NetEase's artwork thumbnail parameter is content sizing, not identity.
        if(provider==Provider.NETEASE&&(host.equals("music.126.net")||host.endsWith(".music.126.net"))){StringBuilder other=new StringBuilder();if(query!=null)for(String part:query.split("&"))if(!part.regionMatches(true,0,"param=",0,6)){if(other.length()>0)other.append('&');other.append(part);}if(other.length()>0)other.append('&');query=other.append("param=512y512").toString();}
        URI result=URI.create("https://"+host+(uri.getRawPath()==null?"":uri.getRawPath())+(query==null?"":"?"+query));return MusicNetwork.allowed(provider,result)?result:null;
    }catch(IllegalArgumentException invalid){return null;}}
    private static Path disk(String key)throws Exception{return Config.directory().resolve("cache/music-artwork").resolve(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)))+".img");}
    private static byte[] publicBytes(Provider provider,URI uri,String key,long requestEpoch)throws Exception{
        Path path=disk(key);long now=System.currentTimeMillis();
        synchronized(Config.persistenceLock()){if(Config.persistenceAllowed()&&Files.isRegularFile(path)){long size=Files.size(path);if(size>0&&size<=MAX_BYTES&&now-Files.getLastModifiedTime(path).toMillis()<MAX_AGE){byte[] cached=Files.readAllBytes(path);try{NativeImage checked=decode(cached,true);checked.close();Files.setLastModifiedTime(path,FileTime.fromMillis(now));return cached;}catch(Exception invalid){Files.deleteIfExists(path);}}else Files.deleteIfExists(path);}}
        byte[] bytes=MusicNetwork.bytes(provider,uri,Map.of(),MAX_BYTES);NativeImage checked=decode(bytes,true);checked.close();
        synchronized(Config.persistenceLock()){if(Config.persistenceAllowed()&&requestEpoch==epoch){try{Files.createDirectories(path.getParent());Path part=path.resolveSibling(path.getFileName()+"."+Thread.currentThread().threadId()+".tmp");try{Files.write(part,bytes);try{Files.move(part,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException unsupported){Files.move(part,path,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(part);}pruneDisk(path.getParent(),now);}catch(java.io.IOException ignored){ }}}
        return bytes;
    }
    private static void pruneDisk(Path directory,long now)throws java.io.IOException{List<Path> files=new ArrayList<>();try(var stream=Files.list(directory)){stream.filter(path->path.getFileName().toString().matches("[0-9a-f]{64}\\.img")).forEach(files::add);}files.sort(Comparator.comparingLong(MusicArtworkCache::modified).reversed());long total=0;int count=0;for(Path file:files){long size=Files.size(file);if(size<=0||size>MAX_BYTES||now-modified(file)>MAX_AGE||++count>DISK_FILES||total+size>DISK_BYTES)Files.deleteIfExists(file);else total+=size;}}
    private static long modified(Path path){try{return Files.getLastModifiedTime(path).toMillis();}catch(java.io.IOException missing){return 0;}}
    private void trimPublic(){while(PUBLIC.size()>LIMIT){String eldest=PUBLIC.keySet().iterator().next();release(PUBLIC.remove(eldest));}}
    /** Explicit cache controls may call this; leaving a screen retains public covers. */
    public static void clearPublicCache(){Minecraft mc=Minecraft.getInstance();synchronized(PUBLIC_LOCK){epoch++;PUBLIC.values().forEach(entry->{if(entry.texture!=null)mc.getTextureManager().release(entry.texture);});PUBLIC.clear();}try{WORKER.execute(()->{synchronized(Config.persistenceLock()){if(!Config.persistenceAllowed())return;Path directory=Config.directory().resolve("cache/music-artwork");if(!Files.isDirectory(directory))return;try(var files=Files.list(directory)){files.filter(path->path.getFileName().toString().matches("[0-9a-f]{64}\\.img")).forEach(path->{try{Files.deleteIfExists(path);}catch(java.io.IOException ignored){ }});}catch(java.io.IOException ignored){ }}});}catch(java.util.concurrent.RejectedExecutionException ignored){ }}
    private static NativeImage decode(byte[] bytes,boolean crop) throws Exception {
        if(bytes.length==0||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Artwork size");
        BufferedImage decoded=null;NativeImage pixels=null;
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IllegalArgumentException("Artwork format");
            var reader=readers.next();
            try{reader.setInput(input,true,true);int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<1||h<1||w>16384||h>16384||(long)w*h>33554432)throw new IllegalArgumentException("Artwork dimensions");
                var parameters=reader.getDefaultReadParam();int sample=Math.max(1,(Math.max(w,h)+511)/512);
                parameters.setSourceSubsampling(sample,sample,0,0);decoded=reader.read(0,parameters);
            }finally{reader.dispose();}
            if(decoded==null)throw new IllegalArgumentException("Artwork pixels");
            int side=Math.min(decoded.getWidth(),decoded.getHeight()),w=crop?side:decoded.getWidth(),h=crop?side:decoded.getHeight();
            int ox=crop?(decoded.getWidth()-side)/2:0,oy=crop?(decoded.getHeight()-side)/2:0;
            pixels=new NativeImage(w,h,false);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)pixels.setPixel(x,y,decoded.getRGB(x+ox,y+oy));
            return pixels;
        }catch(Exception|OutOfMemoryError failed){if(pixels!=null)pixels.close();throw failed;}
        finally{if(decoded!=null)decoded.flush();}
    }
    private void upload(Map<String,Entry> entries,String key,Entry entry,NativeImage pixels,boolean shared){
        if(!shared&&closed||entries.get(key)!=entry||shared&&entry.epoch!=epoch){pixels.close();return;}
        entry.pending=false;entry.task=null;
        DynamicTexture texture=null;
        try{texture=new DynamicTexture(() -> "Cheat Program texture", pixels);com.cheatprogram.compat.gl.RenderSystem.setTextureFilter(texture, true, false);
            Identifier id=Identifier.fromNamespaceAndPath("cheatprogram","dynamic/music/"+(++sequence));
            mc.getTextureManager().register(id,texture);entry.texture=id;
        }catch(RuntimeException failed){if(texture!=null)texture.close();else pixels.close();entry.failures++;entry.retryAt=System.currentTimeMillis()+5000;}
    }
    private void release(Entry entry){if(entry!=null&&entry.texture!=null)mc.getTextureManager().release(entry.texture);}
    @Override public void close(){closed=true;qrEntries.values().forEach(this::release);qrEntries.clear();}
}

