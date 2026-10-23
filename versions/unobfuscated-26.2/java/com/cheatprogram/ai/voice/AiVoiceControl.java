package com.cheatprogram.ai.voice;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Lang;
import com.cheatprogram.gui.ClickGuiScreen;
import com.cheatprogram.gui.WorldPanelInputScreen;
import com.cheatprogram.render.WorldControlPanel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.glfw.GLFW;

import javax.sound.sampled.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit, cancellable local microphone capture. Raw audio never goes to an API. */
public final class AiVoiceControl {
    public enum Stage { IDLE,LISTENING,TRANSCRIBING,READY,ERROR }
    public record State(Stage stage,String statusKey,String transcript,float level,long changedAt){ }
    private static final Minecraft MC=Minecraft.getInstance();
    private static final VoiceInputConfig CONFIG=VoiceInputConfig.load();
    private static final ExecutorService WORK=Executors.newCachedThreadPool(job->{Thread t=new Thread(job,"CheatProgram-speech-input");t.setDaemon(true);return t;});
    private static final ScheduledExecutorService WATCH=Executors.newSingleThreadScheduledExecutor(job->{Thread t=new Thread(job,"CheatProgram-microphone-watch");t.setDaemon(true);return t;});
    private static final AudioFormat FORMAT=new AudioFormat(16000,16,1,true,false);
    private static volatile State state=new State(Stage.IDLE,"ui.voiceInput.idle","",0,0);
    private static volatile Session current;
	private static volatile ClientLevel transcriptWorld;
    private static final class Session {
        final VoiceInputConfig settings;final ClientLevel world;final long started=System.nanoTime();
        volatile boolean cancelled,finish;volatile TargetDataLine line;volatile Process process;volatile Path wav;
        Session(VoiceInputConfig cfg){settings=cfg;world=MC.level;}
    }
    private AiVoiceControl(){ }
    public static VoiceInputConfig config(){return CONFIG;}
    /** Voice control was removed from AI Control; old saved bindings cannot reactivate capture. */
    public static boolean available(){return false;}
    public static State state(){return state;}
    public static boolean busy(){return current!=null;}
    private static void publish(Stage stage,String key,String text,float amplitude){state=new State(stage,key,text,amplitude,System.currentTimeMillis());}
	private static synchronized boolean publish(Session owner,Stage stage,String key,String text,float amplitude){if(current!=owner||owner.cancelled)return false;publish(stage,key,text,amplitude);return true;}
    public static boolean key(int key,int action){
        if(!available())return false;
        if(!com.cheatprogram.core.Config.persistenceAllowed()||ClientSettings.INSTANCE==null)return false;
        if(action!=GLFW.GLFW_PRESS||!CONFIG.enabled||key!=CONFIG.key||key==ClientSettings.INSTANCE.guiKey||MC.player==null||MC.gui.overlay()!=null)return false;
        boolean allowed=MC.gui.screen()==null||MC.gui.screen() instanceof ClickGuiScreen gui&&gui.voiceKeyAllowed()
                ||MC.gui.screen() instanceof WorldPanelInputScreen&&WorldControlPanel.page()!=null&&WorldControlPanel.page().voiceKeyAllowed();
        if(!allowed)return false;toggle();return true;
    }
    public static void toggle(){
        Session s=current;
        if(s!=null){if(state.stage()==Stage.LISTENING){s.finish=true;closeLine(s);}else cancel();return;}
        start();
    }
    public static synchronized void start(){
        if(!available())return;
        if(!com.cheatprogram.core.Config.persistenceAllowed())return;
        if(busy())return;
        if(!CONFIG.enabled){publish(Stage.ERROR,"ui.voiceInput.disabled","",0);return;}
        if(MC.player==null||MC.level==null||MC.getConnection()==null){publish(Stage.ERROR,"ui.ai.singleplayer","",0);return;}
        if(!AiController.config().hasApiKey()){publish(Stage.ERROR,"ui.voiceInput.needKey","",0);return;}
        VoiceInputConfig settings=CONFIG.snapshot();
        try{if(settings.engine.equals("whisper")&&(settings.model.isBlank()||!Files.isDirectory(Path.of(settings.model)))){publish(Stage.ERROR,"ui.voiceInput.needModel","",0);return;}}catch(InvalidPathException invalid){publish(Stage.ERROR,"ui.voiceInput.needModel","",0);return;}
        if(settings.engine.equals("windows")&&!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("windows")){publish(Stage.ERROR,"ui.voiceInput.windowsOnly","",0);return;}
        if(AiController.isRunning())AiController.stop();AiController.clearVoiceReply();CONFIG.save();transcriptWorld=null;Session next=new Session(settings);current=next;publish(Stage.LISTENING,"ui.voiceInput.listening","",0);WORK.execute(()->record(next));
    }
    private static void closeLine(Session session){WORK.execute(()->{TargetDataLine line=session.line;if(line!=null){try{line.stop();}catch(Exception ignored){ }try{line.close();}catch(Exception ignored){ }}});}
    private static void destroyProcess(Process process){if(process==null)return;try{process.toHandle().descendants().forEach(child->{try{child.destroyForcibly();}catch(Exception ignored){ }});}catch(Exception ignored){ }try{process.destroyForcibly();}catch(Exception ignored){ }WORK.execute(()->{try{process.getInputStream().close();}catch(IOException ignored){ }try{process.getErrorStream().close();}catch(IOException ignored){ }try{process.getOutputStream().close();}catch(IOException ignored){ }});}
    public static synchronized void cancel(){Session s=current;current=null;transcriptWorld=null;if(s!=null){s.cancelled=true;closeLine(s);destroyProcess(s.process);}AiController.clearVoiceReply();publish(Stage.IDLE,"ui.voiceInput.cancelled","",0);}
    public static void lifecycle(){
        Session s=current;
        if(s!=null&&(!CONFIG.enabled||MC.level!=s.world||MC.player==null||!MC.player.isAlive()||!MC.isWindowActive()||MC.isPaused()))cancel();
        if(transcriptWorld!=null&&transcriptWorld!=MC.level)cancel();
    }
    public static void submitTranscript(){if(!available()||!com.cheatprogram.core.Config.persistenceAllowed())return;String text=state.transcript();if(text.isBlank()||transcriptWorld!=MC.level||MC.player==null||!MC.player.isAlive())return;if(AiController.submitVoice(text))publish(Stage.READY,"ui.voiceInput.sent",text,0);else publish(Stage.ERROR,"ui.voiceInput.rejected",text,0);}
    public static CompletableFuture<List<String>> microphones(){return CompletableFuture.supplyAsync(()->{
        ArrayList<String> names=new ArrayList<>();for(Mixer.Info info:AudioSystem.getMixerInfo()){try{if(AudioSystem.getMixer(info).isLineSupported(new DataLine.Info(TargetDataLine.class,FORMAT)))names.add(info.getName());}catch(Exception ignored){ }}return List.copyOf(names);
    },WORK);}
    private static TargetDataLine input(VoiceInputConfig cfg)throws LineUnavailableException{
        DataLine.Info wanted=new DataLine.Info(TargetDataLine.class,FORMAT);
        if(!cfg.microphone.isBlank())for(Mixer.Info info:AudioSystem.getMixerInfo())if(info.getName().equals(cfg.microphone))return (TargetDataLine)AudioSystem.getMixer(info).getLine(wanted);
        if(!cfg.microphone.isBlank())throw new LineUnavailableException("Selected microphone missing");
        return (TargetDataLine)AudioSystem.getLine(wanted);
    }
    private static void record(Session owner){
        cleanupOrphanAudio();
        Path wav=null;String failure="ui.voiceInput.recordFailed";
        ScheduledFuture<?> deadline=WATCH.schedule(()->{if(current==owner&&!owner.cancelled){owner.finish=true;closeLine(owner);}},owner.settings.maxSeconds+5L,TimeUnit.SECONDS);
        try{
            TargetDataLine line=input(owner.settings);owner.line=line;if(owner.cancelled||owner.finish){line.close();if(!owner.cancelled)finishError(owner,"ui.voiceInput.noSpeech");return;}
            line.open(FORMAT,16000);if(owner.cancelled||owner.finish){line.close();if(!owner.cancelled)finishError(owner,"ui.voiceInput.noSpeech");return;}line.start();
            ByteArrayOutputStream pcm=new ByteArrayOutputStream();byte[] block=new byte[1600];boolean heard=false;long lastSpeech=System.nanoTime();
            while(!owner.cancelled&&!owner.finish){
                int read;try{read=line.read(block,0,block.length);}catch(RuntimeException stopped){if(owner.finish&&!owner.cancelled)break;throw stopped;}if(read<=0){if(!line.isOpen())break;continue;}
                pcm.write(block,0,read);double squared=0;int samples=read/2;
                for(int i=0;i+1<read;i+=2){int sample=(short)((block[i]&255)|(block[i+1]<<8));double value=sample/32768.0;squared+=value*value;}
                float rms=(float)Math.sqrt(squared/Math.max(1,samples));long now=System.nanoTime();
                if(rms>=owner.settings.threshold){heard=true;lastSpeech=now;}
                publish(owner,Stage.LISTENING,"ui.voiceInput.listening","",Math.min(1,rms*8));
                if(owner.settings.autoStop&&heard&&now-lastSpeech>=owner.settings.silenceMillis*1_000_000L)break;
                if(now-owner.started>=owner.settings.maxSeconds*1_000_000_000L)break;
                if(!heard&&now-owner.started>8_000_000_000L)break;
            }
            try{line.stop();}catch(Exception ignored){ }try{line.close();}catch(Exception ignored){ }owner.line=null;
            if(owner.cancelled||current!=owner)return;
            if(!heard||pcm.size()<3200){finishError(owner,"ui.voiceInput.noSpeech");return;}
            synchronized(com.cheatprogram.core.Config.persistenceLock()){
                if(!com.cheatprogram.core.Config.persistenceAllowed()||owner.cancelled)return;
                Path directory=FabricLoader.getInstance().getConfigDir().resolve("cheatprogram/runtime/voice-input");Files.createDirectories(directory);wav=directory.resolve(ProcessHandle.current().pid()+"_"+UUID.randomUUID()+".wav");owner.wav=wav;
                byte[] audio=pcm.toByteArray();try(AudioInputStream stream=new AudioInputStream(new ByteArrayInputStream(audio),FORMAT,audio.length/FORMAT.getFrameSize())){AudioSystem.write(stream,AudioFileFormat.Type.WAVE,wav.toFile());}
            }
            if(!publish(owner,Stage.TRANSCRIBING,"ui.voiceInput.transcribing","",0))return;failure="ui.voiceInput.recognizeFailed";
            Reply reply=recognize(owner,wav);
            MC.execute(()->{
                synchronized(AiVoiceControl.class){
                if(current!=owner||owner.cancelled||MC.level!=owner.world||MC.player==null||!MC.isWindowActive())return;
                current=null;
                if(!reply.ok){publish(Stage.ERROR,reply.key,"",0);return;}
                if(reply.text.isBlank()){publish(Stage.ERROR,"ui.voiceInput.noSpeech","",0);return;}
                transcriptWorld=owner.world;publish(Stage.READY,"ui.voiceInput.ready",reply.text,0);
                if(owner.settings.autoSend)submitTranscript();
                }
            });
        }catch(Exception error){if(!owner.cancelled&&current==owner)finishError(owner,failure);}
        finally{deadline.cancel(false);TargetDataLine line=owner.line;if(line!=null){try{line.close();}catch(Exception ignored){ }}Process process=owner.process;if(process!=null){destroyProcess(process);try{process.waitFor(1200,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}}deleteAudio(wav);owner.wav=null;}
    }
    private static void finishError(Session owner,String key){MC.execute(()->{synchronized(AiVoiceControl.class){if(current==owner&&!owner.cancelled){current=null;publish(Stage.ERROR,key,"",0);}}});}
	private static void deleteAudio(Path path){if(path==null)return;try{Files.deleteIfExists(path);}catch(IOException first){WATCH.schedule(()->{try{Files.deleteIfExists(path);}catch(IOException ignored){ }},1500,TimeUnit.MILLISECONDS);}}
    private static void cleanupOrphanAudio(){
        Path directory=com.cheatprogram.core.Config.directory().resolve("runtime/voice-input");if(!Files.isDirectory(directory)||Files.isSymbolicLink(directory))return;
        try(var files=Files.list(directory)){files.filter(p->!Files.isSymbolicLink(p)&&Files.isRegularFile(p)&&p.getFileName().toString().matches("[0-9]+_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.wav")).limit(128).forEach(p->{try{long pid=Long.parseLong(p.getFileName().toString().split("_",2)[0]);if(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))return;Files.deleteIfExists(p);}catch(Exception ignored){ }});}catch(IOException ignored){ }
    }
    private record Reply(boolean ok,String key,String text){ }
    private static Reply recognize(Session owner,Path wav)throws Exception{
        VoiceInputConfig cfg=owner.settings;List<String> command;
        if(cfg.engine.equals("windows")){
            Path helper=resource("windows-recognize.ps1");String systemRoot=System.getenv().getOrDefault("SystemRoot","C:\\Windows");
            command=List.of(Path.of(systemRoot,"System32/WindowsPowerShell/v1.0/powershell.exe").toString(),"-NoLogo","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",helper.toString(),"-Wave",wav.toString(),"-Culture",cfg.culture(),"-OwnerPid",Long.toString(ProcessHandle.current().pid()));
        }else{
            Path helper=resource("whisper-input.py");command=List.of(cfg.python,helper.toString(),"--wave",wav.toString(),"--model",cfg.model,"--language",cfg.whisperLanguage(),"--device",cfg.device,"--owner",Long.toString(ProcessHandle.current().pid()),"--timeout",Integer.toString(cfg.recognitionTimeout));
        }
        ProcessBuilder builder=new ProcessBuilder(command);builder.environment().put("PYTHONIOENCODING","utf-8");builder.environment().put("HF_HUB_OFFLINE","1");builder.environment().put("TRANSFORMERS_OFFLINE","1");
        if(owner.cancelled||current!=owner)throw new InterruptedException();
        Process p; synchronized(com.cheatprogram.core.Config.persistenceLock()){if(!com.cheatprogram.core.Config.persistenceAllowed()||owner.cancelled||current!=owner)throw new InterruptedException();p=builder.start();owner.process=p;}if(owner.cancelled||current!=owner){destroyProcess(p);throw new InterruptedException();}
        WORK.execute(()->{try(InputStream err=p.getErrorStream()){byte[] discard=new byte[2048];while(err.read(discard)!=-1){ }}catch(IOException ignored){ }});
        Future<String> output=WORK.submit(()->{try(InputStream in=p.getInputStream()){byte[] bytes=in.readNBytes(131073);if(bytes.length>131072)throw new IOException("Result too large");return new String(bytes,StandardCharsets.UTF_8);}});
        if(!p.waitFor(cfg.recognitionTimeout,TimeUnit.SECONDS)){destroyProcess(p);output.cancel(true);return new Reply(false,"ui.voiceInput.timeout","");}
        String result=output.get(3,TimeUnit.SECONDS).strip();
        if(p.exitValue()!=0)return new Reply(false,"ui.voiceInput.recognizeFailed","");
        JsonObject json=JsonParser.parseString(result).getAsJsonObject();
        if(!json.has("ok")||!json.get("ok").getAsBoolean()){
            String key=json.has("error")?json.get("error").getAsString():"";
            return new Reply(false,switch(key){case "language"->"ui.voiceInput.noLanguage";case "dependency"->"ui.voiceInput.noDependency";case "model"->"ui.voiceInput.needModel";case "empty"->"ui.voiceInput.noSpeech";default->"ui.voiceInput.recognizeFailed";},"");
        }
        String text=json.get("text").getAsString().replaceAll("[\\x00-\\x1f\\x7f]"," ").strip();
        if(text.length()>32768)text=text.substring(0,32768);
        return new Reply(true,"",text);
    }
    private static synchronized Path resource(String name)throws IOException{
        synchronized(com.cheatprogram.core.Config.persistenceLock()){
        if(!com.cheatprogram.core.Config.persistenceAllowed())throw new IOException("Configuration reset pending");
        Path directory=FabricLoader.getInstance().getConfigDir().resolve("cheatprogram/runtime/voice-input");Files.createDirectories(directory);Path path=directory.resolve(name);
        try(InputStream input=AiVoiceControl.class.getResourceAsStream("/assets/cheatprogram/speech/"+name)){if(input==null)throw new IOException("Missing recognizer helper");Files.write(path,input.readAllBytes());}return path;}
    }
    public static void shutdown(){cancel();CONFIG.save();}
}
