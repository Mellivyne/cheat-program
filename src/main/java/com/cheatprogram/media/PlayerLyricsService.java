package com.cheatprogram.media;

import com.cheatprogram.core.Config;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.*;

/** Actual accessible text from one recognized music player's standalone lyrics window. */
public final class PlayerLyricsService implements AutoCloseable {
    public record State(String text,String statusKey,String detail,boolean relocated,String provider,
                        long sequence,long changedNanos,long sampledNanos) {
        public boolean ready(){return !text.isBlank()&&System.nanoTime()-sampledNanos<2_000_000_000L;}
    }
    private static final String PREFIX="ui.playerLyrics.";
    private static final ExecutorService IO=Executors.newCachedThreadPool(job->{Thread t=new Thread(job,"CheatProgram-player-lyrics");t.setDaemon(true);return t;});
    private static final AtomicLong NEXT_DISCOVERY=new AtomicLong();
    private static volatile List<MusicService.Track> discovered=List.of();
    private volatile State state=empty("waitingPlayer");
    private volatile Session session;
    private String signature="";

    public State state(){State value=state;return value.ready()||value.text().isBlank()?value:empty("unreadable");}
    /** Recognized processes remain selectable even when their Windows media metadata is disabled. */
    public static List<MusicService.Track> players(){
        List<MusicService.Track> players=new ArrayList<>(MusicService.sessions().stream().filter(PlayerLyricsService::recognized).toList());
        for(MusicService.Track player:discovered)if(players.stream().noneMatch(t->family(t.provider()).equals(family(player.provider()))))players.add(player);
        long now=System.nanoTime(),next=NEXT_DISCOVERY.get();
        if(now>=next&&NEXT_DISCOVERY.compareAndSet(next,now+3_000_000_000L))IO.execute(()->{
            List<MusicService.Track> found=new ArrayList<>();
            try(var processes=ProcessHandle.allProcesses()){
                processes.limit(4096).forEach(process->{try{
                    String name=process.info().command().map(p->Path.of(p).getFileName().toString().toLowerCase(Locale.ROOT).replaceFirst("\\.exe$","")).orElse("");
                    if(!List.of("cloudmusic","qqmusic","spotify","applemusic","foobar2000","musicbee","aimp","kugou","kwmusic").contains(name))return;
                    String provider=name+".exe";
                    if(found.stream().noneMatch(t->t.provider().equals(provider)))found.add(new MusicService.Track("player:"+name,playerName(name),"","",false,provider,0,0,"",System.nanoTime(),false));
                }catch(RuntimeException ignored){}});
                discovered=List.copyOf(found);
            }catch(RuntimeException ignored){discovered=List.of();}
        });
        return List.copyOf(players);
    }
    private static String playerName(String family){return switch(family){case "cloudmusic"->"网易云音乐";case "qqmusic"->"QQ音乐";case "spotify"->"Spotify";case "applemusic"->"Apple Music";case "kugou"->"酷狗音乐";case "kwmusic"->"酷我音乐";default->family;};}
    public static boolean samePlayer(MusicService.Track track,String provider){return track!=null&&(track.provider().equals(provider)||track.id().equals(provider)||!family(provider).isBlank()&&family(track.provider()).equals(family(provider)));}
    public static boolean recognized(MusicService.Track track){return track!=null&&!family(track.provider()).isEmpty();}
    static String family(String provider){
        String name=Objects.requireNonNullElse(provider,"").toLowerCase(Locale.ROOT);
        if(name.contains("cloudmusic")||name.contains("netease"))return "cloudmusic";
        if(name.contains("qqmusic"))return "qqmusic";
        if(name.contains("spotify"))return "spotify";
        if(name.contains("applemusic")||name.contains("apple-music"))return "applemusic";
        if(name.contains("foobar2000"))return "foobar2000";
        if(name.contains("musicbee"))return "musicbee";
        if(name.contains("aimp"))return "aimp";
        if(name.contains("kugou"))return "kugou";
        if(name.contains("kuwo")||name.contains("kwmusic"))return "kwmusic";
        return "";
    }
    /** Nonblocking: metadata is used only to select a recognized player, never to fabricate lyrics. */
    public synchronized void update(MusicService.Track track,boolean relocate){
        if(track==null||!recognized(track)||!Config.persistenceAllowed()){stop();return;}
        String next=family(track.provider())+"\n"+relocate;
        if(next.equals(signature)&&session!=null){
            Session owner=session;owner.provider=track.provider();
            if(!owner.title.equals(track.title())||!owner.artist.equals(track.artist())){owner.title=track.title();owner.artist=track.artist();owner.metadataRevision++;owner.displayedAt=0;owner.metadataPending=true;state=empty("starting");}
            return;
        }
        stop();signature=next;
        if(!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows")){state=empty("unsupported");return;}
        Session owner=new Session(track.provider(),family(track.provider()),track.title(),track.artist(),relocate);session=owner;state=empty("starting");
        IO.execute(()->run(owner));
    }
    /** Called only after a real lyric line was drawn in the world; preview/status text never grants a lease. */
    public void displayed(long sequence){Session owner=session;State value=state;if(owner!=null&&value.ready()&&value.sequence()==sequence){owner.displayedSequence=sequence;owner.displayedAt=System.nanoTime();}}
    public synchronized void stop(){Session old=session;session=null;signature="";state=empty("waitingPlayer");if(old!=null){old.cancelled=true;IO.execute(old::stop);}}
    public synchronized void reload(){stop();}
    @Override public void close(){stop();}
    private boolean alive(Session owner){return session==owner&&!owner.cancelled&&Config.persistenceAllowed();}
    private static State empty(String key){return new State("",PREFIX+key,"",false,"",0,0,0);}
    private static String base64(String value){return java.util.Base64.getEncoder().encodeToString((value.isBlank()?" ":value).getBytes(StandardCharsets.UTF_8));}
    private void run(Session owner){
        Path directory=null;
        try{
            directory=Files.createTempDirectory("cheatprogram-player-lyrics-");
            for(String name:List.of("player-lyrics.ps1","player-lyrics.cs")){
                try(InputStream input=PlayerLyricsService.class.getResourceAsStream("/assets/cheatprogram/music/"+name)){
                    if(input==null)throw new IOException("Missing player lyrics adapter");Files.copy(input,directory.resolve(name));
                }
            }
            if(!alive(owner))return;
            String windows=System.getenv().getOrDefault("SystemRoot","C:\\Windows");
            Process process=new ProcessBuilder(Path.of(windows,"System32","WindowsPowerShell","v1.0","powershell.exe").toString(),
                    "-NoProfile","-NonInteractive","-WindowStyle","Hidden","-ExecutionPolicy","Bypass","-File",directory.resolve("player-lyrics.ps1").toString(),
                    "-Provider",owner.family,"-OwnerPid",Long.toString(ProcessHandle.current().pid()),"-TitleBase64",base64(owner.title),"-ArtistBase64",base64(owner.artist)).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            owner.process=process;owner.writer=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.US_ASCII));
            owner.lastOutput=System.nanoTime();IO.execute(()->read(owner,process));
            while(alive(owner)&&!process.waitFor(100,TimeUnit.MILLISECONDS)){
                long now=System.nanoTime();
                if(now-owner.lastOutput>8_000_000_000L)throw new IOException("Player adapter timed out");
                if(owner.metadataPending){owner.metadataPending=false;owner.send("M|"+owner.metadataRevision+"|"+base64(owner.title)+"|"+base64(owner.artist));}
                if(owner.relocate&&now-owner.displayedAt<650_000_000L)owner.send("D|"+owner.displayedSequence);
                else owner.send("R");
            }
            if(alive(owner))state=empty("failed");
        }catch(Exception failure){if(alive(owner))state=empty("failed");}
        finally{
            owner.stop();
            // Guardian may briefly retain the adapter executable while restoring a hidden window.
            if(directory!=null){Path files=directory;IO.execute(()->{try{Thread.sleep(2500);try(var list=Files.list(files)){for(Path file:list.toList())Files.deleteIfExists(file);}Files.deleteIfExists(files);}catch(Exception ignored){}});}
        }
    }
    private void read(Session owner,Process process){
        try(Reader reader=new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)){
            StringBuilder line=new StringBuilder();boolean excessive=false;int c;
            while((c=reader.read())!=-1&&alive(owner)){
                if(c=='\n'){
                    if(!excessive){try{
                        JsonObject json=JsonParser.parseString(line.toString()).getAsJsonObject();
                        if((json.has("metadata")?json.get("metadata").getAsLong():0)<owner.metadataRevision){line.setLength(0);continue;}
                        State previous=state;State next=decode(json,owner.provider,previous);
                        if(alive(owner)){state=next;owner.lastOutput=System.nanoTime();}
                    }catch(RuntimeException ignored){}}
                    line.setLength(0);excessive=false;
                }else if(c!='\r'&&!excessive){if(line.length()<16384)line.append((char)c);else{line.setLength(0);excessive=true;}}
            }
        }catch(IOException ignored){}
    }
    static State decode(JsonObject json,String provider,State previous){
        String status=json.has("status")?json.get("status").getAsString():"failed";
        if(!List.of("enableLyrics","unreadable","live","relocated","restoreFailed","failed").contains(status))status="failed";
        String text=json.has("text")?json.get("text").getAsString():"";
        text=text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","").strip();
        if(text.length()>512)text=text.substring(0,512);
        // A player may expose its original line plus a translation. Read its first supplied line;
        // there is no language detection, network translation, ASR, or generated lyric content.
        text=text.lines().filter(value->!value.isBlank()).findFirst().orElse("");
        if(!status.equals("live")&&!status.equals("relocated"))text="";
        long sequence=json.has("sequence")?Math.max(0,json.get("sequence").getAsLong()):0;
        long now=System.nanoTime(),changed=previous!=null&&previous.text().equals(text)&&previous.sequence()==sequence?previous.changedNanos():now;
        return new State(text,PREFIX+status,"",status.equals("relocated")&&!text.isBlank(),provider,sequence,changed,now);
    }
    private static final class Session{
        final String family;final boolean relocate;
        volatile String provider,title,artist;
        volatile boolean cancelled,metadataPending;volatile Process process;volatile BufferedWriter writer;
        volatile long displayedAt,displayedSequence,lastOutput,metadataRevision;
        Session(String provider,String family,String title,String artist,boolean relocate){this.provider=provider;this.family=family;this.title=title;this.artist=artist;this.relocate=relocate;}
        synchronized void send(String command)throws IOException{if(writer!=null){writer.write(command);writer.newLine();writer.flush();}}
        synchronized void stop(){
            try{send("Q");if(writer!=null){writer.close();writer=null;}}catch(IOException ignored){}
            Process current=process;if(current==null)return;
            try{if(!current.waitFor(1500,TimeUnit.MILLISECONDS)){current.destroy();if(!current.waitFor(500,TimeUnit.MILLISECONDS))current.destroyForcibly();}}
            catch(InterruptedException interrupted){current.destroy();Thread.currentThread().interrupt();}
        }
    }
}
