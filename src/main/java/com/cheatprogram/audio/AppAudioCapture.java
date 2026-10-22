package com.cheatprogram.audio;

import com.cheatprogram.core.Config;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** PCM from one explicitly selected application tree; never falls back to system audio. */
public final class AppAudioCapture implements AutoCloseable {
    /** Immutable ownership: the consumer receives a new PCM16LE stereo packet. */
    public record Pcm(byte[] samples, int sampleRate, int channels, long sequence) { }
    private static final int MAGIC=0x31415043;
    private static final long OWNER=ProcessHandle.current().pid();
    private static final ExecutorService IO=Executors.newCachedThreadPool(job->{Thread thread=new Thread(job,"CheatProgram-process-audio");thread.setDaemon(true);return thread;});
    private static final ScheduledExecutorService WATCH=Executors.newSingleThreadScheduledExecutor(job->{Thread thread=new Thread(job,"CheatProgram-audio-lease");thread.setDaemon(true);return thread;});
    private static Path helper;
    private volatile Session session;
    private CompletableFuture<Void> stopped=CompletableFuture.completedFuture(null);
    private volatile String status="ui.spatialAudio.captureIdle";
    private volatile float level;
    private volatile int lastHresult;
    private volatile boolean originalMuted;
    private record Denoise(String mode,double strength){}
    private volatile Denoise denoise=new Denoise("off",.35);
    private volatile String denoiseStatus="ui.spatialAudio.denoiseOff",denoiseDetail="";
    public static boolean supported(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows");}
    public String statusKey(){return status;}
    public float level(){return level;}
    public int lastHresult(){return lastHresult;}
    public boolean originalMuted(){return originalMuted;}
    public void denoise(String mode,double strength){String selected=mode!=null&&List.of("off","cpu","nvidia").contains(mode)?mode:"off";denoise=new Denoise(selected,Double.isFinite(strength)?Math.max(0,Math.min(1,strength)):.35);}
    public String denoiseStatusKey(){return denoiseStatus;}
    public String denoiseDetailKey(){return denoiseDetail;}
    public boolean running(){Session current=session;return current!=null&&!current.cancelled;}
    public synchronized void start(int pid,boolean muteOriginal,Consumer<Pcm> sink){
        stop();level=0;lastHresult=0;originalMuted=false;
        if(!supported()||!Config.persistenceAllowed()){status="ui.spatialAudio.captureUnsupported";return;}
        if(pid<=4||pid==OWNER||sink==null){status="ui.spatialAudio.captureRejected";return;}
        if(muteOriginal){status="ui.spatialAudio.muteUnavailable";return;}
        if(isOwnAncestor(pid)){status="ui.spatialAudio.captureRejected";return;}
        CompletableFuture<Void> previous=stopped;Session next=new Session();stopped=next.finished;session=next;status="ui.spatialAudio.captureStarting";
        IO.execute(()->{
            try{previous.get(7,TimeUnit.SECONDS);if(session==next&&!next.cancelled)capture(next,pid,false,sink);}
            catch(Exception failure){next.stop();if(session==next)status="ui.spatialAudio.captureFailed";}
            finally{next.finished.complete(null);}
        });
    }
    private static boolean isOwnAncestor(long pid){
        ProcessHandle current=ProcessHandle.current();
        while(true){var parent=current.parent();if(parent.isEmpty())return false;current=parent.get();if(current.pid()==pid)return true;}
    }
    private static synchronized Path helper()throws IOException{
        synchronized(Config.persistenceLock()){
            if(!Config.persistenceAllowed())throw new IOException("Restart required after initialization");
            if(helper!=null&&Files.isRegularFile(helper))return helper;
            Path directory=FabricLoader.getInstance().getConfigDir().resolve("cheatprogram/runtime");Files.createDirectories(directory);
            byte[] bytes;
            try(InputStream input=AppAudioCapture.class.getResourceAsStream("/assets/cheatprogram/audio/process-audio.exe")){
                if(input==null)throw new IOException("Missing process audio helper");bytes=input.readAllBytes();
            }
            String hash;
            try{hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0,16);}
            catch(NoSuchAlgorithmException impossible){throw new IOException(impossible);}
            Path target=directory.resolve("process-audio-"+hash+".exe");
            if(!Files.isRegularFile(target)||!Arrays.equals(Files.readAllBytes(target),bytes))Files.write(target,bytes);
            helper=target;return target;
        }
    }
    private void capture(Session owner,int pid,boolean muteOriginal,Consumer<Pcm> sink){
        Process process=null;
        try{
            process=new ProcessBuilder(helper().toString(),"capture",Integer.toString(pid),Long.toString(OWNER),muteOriginal?"1":"0").start();
            owner.process=process;if(owner.cancelled||session!=owner){process.getOutputStream().close();return;}
            Process current=process;IO.execute(()->discard(current.getErrorStream()));
            owner.control=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.US_ASCII));
            if(owner.cancelled||session!=owner){owner.closeControl();return;}
            owner.lease=WATCH.scheduleAtFixedRate(()->{
                if(owner.cancelled||session!=owner||!Config.persistenceAllowed()){owner.stop();return;}
                try{owner.send("P");}catch(IOException ignored){owner.stop();}
            },0,1,TimeUnit.SECONDS);
            if(owner.cancelled)owner.lease.cancel(false);
            try(DataInputStream input=new DataInputStream(new BufferedInputStream(process.getInputStream(),32*1024))){
                while(session==owner&&!owner.cancelled){
                    int magic=integer(input),code=integer(input),rate=integer(input),channels=integer(input),frames=integer(input),bytes=integer(input),error=integer(input),muted=integer(input);long sequence=Long.reverseBytes(input.readLong());
                    if(magic!=MAGIC||rate!=48000||channels!=2||frames<0||frames>48000||bytes<0||bytes>192000||bytes!=frames*4)throw new IOException("Invalid application audio packet");
                    if(session!=owner||owner.cancelled)break;
                    lastHresult=error;originalMuted=muted!=0;
                    status=switch(code){case 1->"ui.spatialAudio.captureLive";case 2->"ui.spatialAudio.captureReady";case 3->"ui.spatialAudio.captureSilent";case 4->"ui.spatialAudio.captureStopped";case 101->"ui.spatialAudio.captureUnsupported";case 103->"ui.spatialAudio.muteUnavailable";case 104->"ui.spatialAudio.captureRejected";default->"ui.spatialAudio.captureFailed";};
                    if(code>=100||code==4)break;
                    if(bytes==0){level=0;continue;}
                    if(code!=1)throw new IOException("Unexpected application audio payload");
                    byte[] pcm=input.readNBytes(bytes);if(pcm.length!=bytes)throw new EOFException();
                    if(session!=owner||owner.cancelled)break;
                    Denoise settings=denoise;
                    if(!settings.mode.equals(owner.denoiseMode)){owner.cpu.reset();if(owner.nvidia!=null){owner.nvidia.close();owner.nvidia=null;}owner.denoiseMode=settings.mode;}
                    denoiseDetail="";
                    if(settings.mode.equals("nvidia")){
                        if(owner.nvidia==null)owner.nvidia=new NvidiaAudioEffects();
                        byte[] processed=owner.nvidia.process(pcm,settings.strength);
                        denoiseDetail=owner.nvidia.statusKey();
                        if(processed!=null){pcm=processed;denoiseStatus=processed.length==0?"ui.spatialAudio.denoisePreparing":"ui.spatialAudio.denoiseNvidia";}
                        else{pcm=owner.cpu.process(pcm,settings.strength);denoiseStatus="ui.spatialAudio.denoiseCpuFallback";}
                    }else if(settings.mode.equals("cpu")){pcm=owner.cpu.process(pcm,settings.strength);denoiseStatus="ui.spatialAudio.denoiseCpu";}
                    else denoiseStatus="ui.spatialAudio.denoiseOff";
                    if(pcm.length==0)continue;
                    if((pcm.length&3)!=0||pcm.length>192000)throw new IOException("Invalid processed application audio packet");
                    double sum=0;for(int i=0;i<pcm.length;i+=2){int sample=(short)((pcm[i]&255)|(pcm[i+1]<<8));sum+=(double)sample*sample;}
                    level=(float)Math.min(1,Math.sqrt(sum/Math.max(1,pcm.length/2))/32768.0);
                    // Format conversion can dither a silent stream by +/- one PCM16 count.
                    if(level<0.0001f)status="ui.spatialAudio.captureSilent";
                    sink.accept(new Pcm(pcm,rate,channels,sequence));
                }
            }
        }catch(Throwable failure){if(session==owner&&!owner.cancelled)status="ui.spatialAudio.captureFailed";}
        finally{owner.stop();if(owner.nvidia!=null)owner.nvidia.close();if(process!=null){try{if(!process.waitFor(3500,TimeUnit.MILLISECONDS)){process.destroyForcibly();process.waitFor(1500,TimeUnit.MILLISECONDS);}}catch(InterruptedException interrupted){Thread.currentThread().interrupt();process.destroyForcibly();}}if(session==owner){level=0;originalMuted=false;}}
    }
    /** Mild capture-only noise reduction: linked stereo gate plus a continuous high-pass filter. */
    public static final class CpuDenoiser {
        private double previousLeft,previousRight,highLeft,highRight,envelope,gate=1;
        public void reset(){previousLeft=previousRight=highLeft=highRight=envelope=0;gate=1;}
        /** Input belongs to this capture session. Off/zero strength returns the same bytes untouched. */
        public byte[] process(byte[] pcm,double strength){
            if(pcm==null||pcm.length<4||!Double.isFinite(strength)||strength<=0)return pcm;
            double amount=Math.max(0,Math.min(1,strength));
            double coefficient=Math.exp(-2*Math.PI*(20+30*amount)/48000),threshold=.0003+.006*amount*amount;
            double floor=Math.pow(10,(-5-29*amount)/20),low=threshold*.6,high=threshold*2;
            for(int at=0;at+3<pcm.length;at+=4){
                double left=sample(pcm,at)/32768.0,right=sample(pcm,at+2)/32768.0;
                highLeft=coefficient*(highLeft+left-previousLeft);highRight=coefficient*(highRight+right-previousRight);previousLeft=left;previousRight=right;
                double energy=(highLeft*highLeft+highRight*highRight)*.5;envelope+=(energy-envelope)*(energy>envelope?.01036:.000833);
                double level=Math.sqrt(Math.max(0,envelope)),t=Math.max(0,Math.min(1,(level-low)/(high-low)));
                double target=floor+(1-floor)*t*t*(3-2*t);gate+=(target-gate)*(target>gate?.01036:.0002604);
                put(pcm,at,limited(highLeft*gate));put(pcm,at+2,limited(highRight*gate));
            }
            return pcm;
        }
        private static short limited(double value){double magnitude=Math.abs(value);double result=magnitude<=.98?value:Math.copySign(.98+.02*Math.tanh((magnitude-.98)/.02),value);return (short)Math.round(result*32767);}
        private static int sample(byte[] pcm,int at){return (short)((pcm[at]&255)|(pcm[at+1]<<8));}
        private static void put(byte[] pcm,int at,short sample){pcm[at]=(byte)sample;pcm[at+1]=(byte)(sample>>>8);}
    }
    private static int integer(DataInputStream input)throws IOException{return Integer.reverseBytes(input.readInt());}
    private static void discard(InputStream input){try(input){input.transferTo(OutputStream.nullOutputStream());}catch(IOException ignored){}}
    /** Nonblocking; a subsequent start waits for this helper's complete shutdown off the game thread. */
    public synchronized void stop(){Session current=session;session=null;if(current!=null)current.stop();level=0;originalMuted=false;status="ui.spatialAudio.captureIdle";denoiseStatus="ui.spatialAudio.denoiseOff";denoiseDetail="";}
    @Override public void close(){stop();}
    private static final class Session {
        final CompletableFuture<Void> finished=new CompletableFuture<>();
        final AtomicBoolean stopping=new AtomicBoolean();
        final CpuDenoiser cpu=new CpuDenoiser();String denoiseMode="off";NvidiaAudioEffects nvidia;
        volatile Process process;volatile boolean cancelled;volatile BufferedWriter control;volatile ScheduledFuture<?> lease;
        synchronized void send(String command)throws IOException{BufferedWriter writer=control;if(writer!=null){writer.write(command);writer.newLine();writer.flush();}}
        void stop(){if(!stopping.compareAndSet(false,true))return;cancelled=true;ScheduledFuture<?> task=lease;if(task!=null)task.cancel(false);
            IO.execute(this::closeControl);
            Process active=process;if(active!=null)WATCH.schedule(()->{if(active.isAlive())active.destroyForcibly();},3,TimeUnit.SECONDS);
        }
        synchronized void closeControl(){try{send("Q");if(control!=null)control.close();}catch(IOException ignored){}}
    }
}


