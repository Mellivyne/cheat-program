package com.cheatprogram.audio;

import com.cheatprogram.ai.voice.VoiceInputConfig;
import com.cheatprogram.core.Config;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Local, bounded dialogue recognition from an explicitly selected application tree. */
public final class SpeechSubtitleService implements AutoCloseable {
    public record State(String text,String statusKey,float level,long changedNanos,long sequence) { }
    private record Segment(byte[] mono,long sequence,long capturedAt) { }
    private static final String PREFIX="ui.floatingSubtitles.speech.";
    private static final ExecutorService IO=Executors.newCachedThreadPool(job->{Thread thread=new Thread(job,"CheatProgram-speech-subtitles");thread.setDaemon(true);return thread;});
    private static final long OWNER=ProcessHandle.current().pid();
    private final AppAudioCapture capture=new AppAudioCapture();
    private volatile Session session;
    private volatile State current=new State("",PREFIX+"idle",0,0,0);
    private String signature="";
    private VoiceInputConfig cachedConfig;

    /** Call only while dialogue subtitles are wanted. A missing/invalid PID stops the service. */
    public synchronized void update(VoiceInputConfig config,int pid,double seconds,String language){
        if(!Config.persistenceAllowed()||pid<=4||pid==OWNER){stop();return;}
        VoiceInputConfig cfg=config;
        if(cfg==null){if(cachedConfig==null)cachedConfig=VoiceInputConfig.load();cfg=cachedConfig;}
        cfg=cfg.snapshot();cfg.language=language!=null&&VoiceInputConfig.LANGUAGES.contains(language)?language:"auto";
        double chunk=Double.isFinite(seconds)?Math.max(2,Math.min(8,seconds)):4;
        String next=pid+"|"+chunk+"|"+cfg.engine+"|"+cfg.python+"|"+cfg.model+"|"+cfg.device+"|"+cfg.language+"|"+cfg.actualLanguage()+"|"+cfg.recognitionTimeout;
        if(next.equals(signature)&&session!=null)return;
        stop();signature=next;
        if(!AppAudioCapture.supported()){current=new State("",PREFIX+"unsupported",0,0,0);return;}
        Session owner=new Session(cfg,pid,chunk);session=owner;current=new State("",PREFIX+"loading",0,0,0);
        IO.execute(()->run(owner));
    }
    public State state(){
        State value=current;Session owner=session;
        if(owner==null)return value;
        String status=value.statusKey;
        if(owner.ready){String source=capture.statusKey();if(source.endsWith("Failed")||source.endsWith("Rejected")||source.endsWith("Unsupported")||source.endsWith("Stopped"))status=source;}
        return new State(value.text,status,capture.level(),value.changedNanos,value.sequence);
    }
    public synchronized void stop(){
        Session previous=session;session=null;signature="";capture.stop();
        if(previous!=null)previous.cancel();current=new State("",PREFIX+"idle",0,0,0);
    }
    @Override public void close(){stop();}
    private boolean alive(Session owner){return session==owner&&!owner.cancelled&&Config.persistenceAllowed();}
    private synchronized void status(Session owner,String key){if(alive(owner)){State value=current;current=new State(value.text,key,0,value.changedNanos,value.sequence);}}
    private synchronized void subtitle(Session owner,String text){
        if(!alive(owner))return;
        text=text==null?"":text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","").strip();
        if(text.isEmpty())return;if(text.length()>2048)text=text.substring(0,2048);
        current=new State(text,PREFIX+"listening",0,System.nanoTime(),owner.outputSequence.incrementAndGet());
    }
    private void run(Session owner){
        try{
            for(int attempt=0;attempt<3&&alive(owner);attempt++){
                try{recognize(owner);return;}
                catch(Permanent failure){status(owner,failure.key.startsWith("ui.")?failure.key:PREFIX+failure.key);return;}
                catch(Exception failure){
                    if(!alive(owner))return;
                    status(owner,PREFIX+(failure instanceof TimeoutException?"timeout":"failed"));
                    if(attempt==2)return;
                    long until=System.nanoTime()+(attempt==0?2_000_000_000L:5_000_000_000L);
                    while(alive(owner)&&System.nanoTime()<until)Thread.sleep(100);
                }finally{owner.ready=false;stopCapture(owner);owner.stopWorker();owner.pending.set(null);owner.segmenter.reset();}
            }
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
    }
    private void recognize(Session owner)throws Exception{
        status(owner,PREFIX+"loading");
        Process process;
        synchronized(Config.persistenceLock()){
            if(!alive(owner))return;
            Path helper=resource(owner.cfg.engine.equals("whisper")?"subtitle-whisper-worker.py":"subtitle-windows-worker.ps1");
            List<String> command;
            if(owner.cfg.engine.equals("whisper")){
                if(owner.cfg.model.isBlank()||!Files.isDirectory(Path.of(owner.cfg.model)))throw new Permanent("model");
                command=List.of(owner.cfg.python,helper.toString(),"--model",owner.cfg.model,"--language",owner.cfg.language.equals("auto")?"auto":owner.cfg.whisperLanguage(),"--device",owner.cfg.device,"--owner",Long.toString(OWNER));
            }else{
                String windows=System.getenv().getOrDefault("SystemRoot","C:\\Windows");
                command=List.of(Path.of(windows,"System32","WindowsPowerShell","v1.0","powershell.exe").toString(),"-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",helper.toString(),"-Culture",owner.cfg.culture(),"-OwnerPid",Long.toString(OWNER));
            }
            if(!alive(owner))return;
            process=new ProcessBuilder(command).start();owner.worker=process;
        }
        if(!alive(owner)){owner.stopWorker();return;}
        ArrayBlockingQueue<String> replies=new ArrayBlockingQueue<>(8);
        IO.execute(()->readReplies(process,replies));IO.execute(()->discard(process.getErrorStream()));
        int timeout=Math.max(15,Math.min(120,owner.cfg.recognitionTimeout));
        JsonObject ready=reply(owner,process,replies,timeout,0);
        if(!ready.has("ready")||!ready.get("ready").getAsBoolean())throw new Permanent(error(ready));
        owner.ready=true;status(owner,PREFIX+"listening");
        // Recognition only observes a selected process. It never changes routing, mutes or plays it.
        if(!startCapture(owner))return;
        try(BufferedWriter writer=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.US_ASCII))){
            while(alive(owner)&&process.isAlive()){
                Segment segment=owner.pending.getAndSet(null);
                if(segment==null){
                    synchronized(owner.signal){if(owner.pending.get()==null&&alive(owner))owner.signal.wait(100);}
                    if(!capture.running()&&owner.ready){String key=capture.statusKey();if(key.endsWith("Rejected")||key.endsWith("Unsupported"))throw new Permanent(key);if(!key.endsWith("Starting")){status(owner,key);throw new IOException("Application audio capture stopped");}}
                    continue;
                }
                // A slow local model receives only the newest segment, never an old backlog.
                if(System.nanoTime()-segment.capturedAt>10_000_000_000L)continue;
                status(owner,PREFIX+"recognizing");JsonObject request=new JsonObject();request.addProperty("sequence",segment.sequence);request.addProperty("pcm",Base64.getEncoder().encodeToString(segment.mono));
                writer.write(request.toString());writer.newLine();writer.flush();
                JsonObject result=reply(owner,process,replies,timeout,segment.sequence);
                if(result.has("ok")&&result.get("ok").getAsBoolean()&&result.has("text"))subtitle(owner,result.get("text").getAsString());
                else{String reason=error(result);if(reason.equals("dependency")||reason.equals("model")||reason.equals("language"))throw new Permanent(reason);status(owner,PREFIX+(reason.equals("empty")?"empty":"failed"));}
            }
            if(alive(owner))throw new IOException("Subtitle recognizer ended");
        }
    }
    private void accept(Session owner,AppAudioCapture.Pcm pcm){
        if(!alive(owner)||pcm.sampleRate()!=48000||pcm.channels()!=2)return;
        Segment segment=owner.segmenter.accept(pcm.samples());
        if(segment!=null){owner.pending.set(segment);synchronized(owner.signal){owner.signal.notifyAll();}}
        else if(owner.segmenter.justSilent)status(owner,PREFIX+"silent");
    }
    private synchronized boolean startCapture(Session owner){if(!alive(owner))return false;capture.start(owner.pid,false,pcm->accept(owner,pcm));return true;}
    private synchronized void stopCapture(Session owner){if(session==owner)capture.stop();}
    private JsonObject reply(Session owner,Process process,BlockingQueue<String> replies,int seconds,long sequence)throws Exception{
        long deadline=System.nanoTime()+seconds*1_000_000_000L;
        while(alive(owner)){
            String line=replies.poll(100,TimeUnit.MILLISECONDS);
            if(line!=null){try{JsonObject value=JsonParser.parseString(line).getAsJsonObject();if(sequence==0&&value.has("ready")||sequence>0&&value.has("sequence")&&value.get("sequence").getAsLong()==sequence)return value;}catch(RuntimeException ignored){}}
            if(!process.isAlive()&&replies.isEmpty())throw new IOException("Subtitle worker ended");
            if(System.nanoTime()>deadline)throw new TimeoutException("Local subtitle recognition timeout");
        }
        throw new InterruptedException("Subtitle recognition cancelled");
    }
    private static String error(JsonObject value){String key=value.has("error")?value.get("error").getAsString():"recognizer";return switch(key){case "language","dependency","model","empty"->key;default->"failed";};}
    private static Path resource(String name)throws IOException{
        synchronized(Config.persistenceLock()){
            if(!Config.persistenceAllowed())throw new IOException("Initialization in progress");
            Path directory=FabricLoader.getInstance().getConfigDir().resolve("cheatprogram/runtime/speech-subtitles");Files.createDirectories(directory);Path target=directory.resolve(name);
            try(InputStream input=SpeechSubtitleService.class.getResourceAsStream("/assets/cheatprogram/speech/"+name)){
                if(input==null)throw new IOException("Missing local subtitle helper");Files.write(target,input.readAllBytes());
            }
            return target;
        }
    }
    private static void readReplies(Process process,BlockingQueue<String> replies){
        try(Reader input=new InputStreamReader(process.getInputStream(),StandardCharsets.UTF_8)){
            StringBuilder line=new StringBuilder();boolean excessive=false;int character;
            while((character=input.read())!=-1){
                if(character=='\n'){if(!excessive){if(!replies.offer(line.toString())){replies.poll();replies.offer(line.toString());}}line.setLength(0);excessive=false;}
                else if(character!='\r'&&!excessive){if(line.length()<16384)line.append((char)character);else{excessive=true;line.setLength(0);}}
            }
        }catch(IOException ignored){}
    }
    private static void discard(InputStream input){try(input){input.transferTo(OutputStream.nullOutputStream());}catch(IOException ignored){}}
    private static final class Permanent extends Exception {final String key;Permanent(String key){this.key=key;}}
    private static final class Session {
        final VoiceInputConfig cfg;final int pid;final Object signal=new Object();final Segmenter segmenter;final AtomicReference<Segment> pending=new AtomicReference<>();final AtomicLong outputSequence=new AtomicLong();
        volatile Process worker;volatile boolean cancelled,ready;
        Session(VoiceInputConfig cfg,int pid,double seconds){this.cfg=cfg;this.pid=pid;segmenter=new Segmenter(seconds);}
        void cancel(){cancelled=true;pending.set(null);synchronized(signal){signal.notifyAll();}stopWorker();}
        void stopWorker(){Process active=worker;worker=null;if(active!=null){try{active.getOutputStream().close();}catch(IOException ignored){}active.destroy();if(active.isAlive())active.destroyForcibly();}}
    }
    /** PCM48k stereo -> PCM16k mono with a bounded overlap buffer; no recording files. */
    static final class Segmenter {
        private final byte[] data;private final int target;private int size,phase,sum,voiced;private long energy,sequence;volatile boolean justSilent;
        Segmenter(double seconds){target=(int)(Math.max(2,Math.min(8,seconds))*16000)*2;data=new byte[target];}
        synchronized void reset(){size=phase=sum=voiced=0;energy=0;justSilent=false;Arrays.fill(data,(byte)0);}
        synchronized Segment accept(byte[] stereo){
            justSilent=false;Segment ready=null;
            for(int offset=0;offset+3<stereo.length;offset+=4){int left=(short)((stereo[offset]&255)|(stereo[offset+1]<<8)),right=(short)((stereo[offset+2]&255)|(stereo[offset+3]<<8));sum+=(left+right)/2;
                if(++phase<3)continue;int sample=sum/3;sum=phase=0;data[size++]=(byte)sample;data[size++]=(byte)(sample>>8);energy+=(long)sample*sample;if(Math.abs(sample)>160)voiced++;
                if(size==target){
                    double rms=Math.sqrt(energy/(double)(size/2))/32768;
                    if(rms>=.0012&&voiced>=size/100)ready=new Segment(Arrays.copyOf(data,size),++sequence,System.nanoTime());else justSilent=true;
                    int overlap=Math.min(8000,target/4);System.arraycopy(data,size-overlap,data,0,overlap);size=overlap;energy=0;voiced=0;
                    for(int at=0;at<size;at+=2){int value=(short)((data[at]&255)|(data[at+1]<<8));energy+=(long)value*value;if(Math.abs(value)>160)voiced++;}
                }
            }
            return ready;
        }
    }
}
