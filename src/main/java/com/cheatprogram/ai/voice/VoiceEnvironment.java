package com.cheatprogram.ai.voice;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit, local dependency checks. A missing voice pack does not make the environment fail. */
public final class VoiceEnvironment {
    private static final ExecutorService CHECKS=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Local voice environment check");t.setDaemon(true);return t;});
    private static final AtomicLong GENERATION=new AtomicLong();
    private static volatile Process checking;
    private static volatile boolean busy;
    private static volatile Result last;
    public record Result(boolean passed,boolean voicePackPresent,String summary,List<String> details,Path folder){}
    private VoiceEnvironment(){}
    public static Path packs(){return VoiceSettings.directory().resolve("voicepacks").toAbsolutePath().normalize();}
    public static boolean checking(){return busy;}
    public static Result last(){return last;}

    public static CompletableFuture<Result> check(VoiceProfile profile){
        if(!Config.persistenceAllowed())return CompletableFuture.failedFuture(new IllegalStateException(Lang.t("ui.voice.env.reset")));
        if(SpeechVoices.installing())return CompletableFuture.failedFuture(new IllegalStateException(Lang.t("ui.voice.env.busy")));
        synchronized(VoiceEnvironment.class){
            if(busy)return CompletableFuture.failedFuture(new IllegalStateException(Lang.t("ui.voice.env.checking")));
            busy=true;
        }
        long token=GENERATION.incrementAndGet();
        VoiceProfile actor=VoiceProfile.load(profile==null?new JsonObject():profile.save());
        return CompletableFuture.supplyAsync(()->{
            Path report=null;
            try{
                if(token!=GENERATION.get()||!Config.persistenceAllowed())throw new java.io.InterruptedIOException("Cancelled");
                SpeechVoices.setup();
                VoiceSettings.INSTANCE.discoverLocalAssets();
                JsonObject settings=VoiceSettings.INSTANCE.save();
                Path python=VoiceSettings.INSTANCE.resolvePython();
                String device="inherit".equals(actor.device)?VoiceProfile.text(settings,"device","cpu"):actor.device;
                String model=actor.modelPath.isBlank()?VoiceProfile.text(settings,"modelPath",""):actor.modelPath;
                String voices=actor.voicePackPath.isBlank()?VoiceProfile.text(settings,"voicesPath",""):actor.voicePackPath;
                String vocab=actor.vocabConfigPath.isBlank()?VoiceProfile.text(settings,"vocabConfigPath",""):actor.vocabConfigPath;
                report=Files.createTempFile(VoiceSettings.directory(),"environment-check-",".json");
                ProcessBuilder command=new ProcessBuilder(List.of(python.toString(),"-I","-B",VoiceSettings.directory().resolve("check-environment.py").toString(),
                        "--device",device,"--model",model,"--voices",voices,"--vocab",vocab,"--report",report.toString()));
                command.directory(VoiceSettings.directory().toFile());
                command.environment().put("PYTHONUTF8","1");command.environment().put("PYTHONDONTWRITEBYTECODE","1");
                command.environment().put("HF_HUB_OFFLINE","1");command.environment().put("TRANSFORMERS_OFFLINE","1");
                configureCaches(command);
                command.redirectOutput(ProcessBuilder.Redirect.DISCARD);command.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process process;
                synchronized(VoiceEnvironment.class){
                    if(token!=GENERATION.get()||!Config.persistenceAllowed())throw new java.io.InterruptedIOException("Cancelled");
                    checking=process=command.start();
                }
                if(!process.waitFor(90,TimeUnit.SECONDS)){destroy(process);throw new IllegalStateException(Lang.t("ui.voice.env.timeout"));}
                if(token!=GENERATION.get()||!Config.persistenceAllowed())throw new java.io.InterruptedIOException("Cancelled");
                if(!Files.isRegularFile(report)||Files.size(report)>128*1024)throw new IllegalStateException(Lang.t("ui.voice.env.checkFailed"));
                JsonObject json=JsonParser.parseString(Files.readString(report,StandardCharsets.UTF_8)).getAsJsonObject();
                boolean passed=VoiceProfile.bool(json,"environmentReady",false),hasPack=VoiceProfile.bool(json,"voicePackPresent",false);
                List<String> details=new java.util.ArrayList<>();
                if(json.has("checks"))for(var item:json.getAsJsonArray("checks")){
                    JsonObject check=item.getAsJsonObject();String name=VoiceProfile.text(check,"id","dependency");
                    details.add(Lang.t("ui.voice.env.item."+name)+": "+Lang.t(VoiceProfile.bool(check,"ok",false)?"ui.voice.env.pass":"ui.voice.env.fail")
                            +(VoiceProfile.text(check,"detail","").isBlank()?"":" · "+VoiceProfile.text(check,"detail","")));
                }
                String packDetail=VoiceProfile.text(json,"voicePackDetail","");
                if(!packDetail.isBlank())details.add(Lang.t("ui.voice.env.packDetail",packDetail));
                String summary=Lang.t(passed?(hasPack?"ui.voice.env.readyWithPack":"ui.voice.env.readyNoPack"):"ui.voice.env.notReady");
                Result result=new Result(passed,hasPack,summary,List.copyOf(details),packs());
                synchronized(VoiceEnvironment.class){if(token==GENERATION.get())last=result;}
                return result;
            }catch(Exception error){
                if(token!=GENERATION.get()||!Config.persistenceAllowed())throw new java.util.concurrent.CompletionException(new java.io.InterruptedIOException("Cancelled"));
                Result result=new Result(false,false,Lang.t("ui.voice.env.notReady"),List.of(clean(error.getMessage())),packs());
                synchronized(VoiceEnvironment.class){if(token==GENERATION.get())last=result;}
                return result;
            }finally{
                Process old=checking;if(old!=null&&old.isAlive())destroy(old);checking=null;
                if(report!=null)try{Files.deleteIfExists(report);}catch(Exception ignored){}
                synchronized(VoiceEnvironment.class){busy=false;}
            }
        },CHECKS);
    }
    private static String clean(String value){if(value==null)return Lang.t("ui.voice.env.checkFailed");return value.replace('\r',' ').replace('\n',' ').substring(0,Math.min(400,value.length()));}
    static void configureCaches(ProcessBuilder builder){
        Path cache=VoiceSettings.directory().resolve("cache").toAbsolutePath().normalize();
        for(String name:List.of("TMP","TEMP","XDG_CACHE_HOME","JIEBA_TMP_DIR"))builder.environment().put(name,cache.toString());
        builder.environment().put("HF_HOME",cache.resolve("huggingface").toString());
        builder.environment().put("MPLCONFIGDIR",cache.resolve("matplotlib").toString());
        builder.environment().put("NUMBA_CACHE_DIR",cache.resolve("numba").toString());
    }
    public static synchronized void cancelCheck(){GENERATION.incrementAndGet();destroy(checking);checking=null;last=null;}
    private static void destroy(Process process){
        if(process==null)return;
        try{process.toHandle().descendants().forEach(p->{try{p.destroyForcibly();}catch(Exception ignored){}});}catch(Exception ignored){}
        try{process.destroyForcibly();}catch(Exception ignored){}
    }
    /** Prevents owned helpers from recreating files while ConfigReset moves the owned directory. */
    public static CompletableFuture<Void> stopForReset(){
        cancelCheck();SpeechVoices.cancelInstallation();SpeechVoices.shutdown();
        CompletableFuture<Void> checkDrain=CompletableFuture.runAsync(()->{},CHECKS);
        return CompletableFuture.allOf(checkDrain,SpeechVoices.drainForReset());
    }
}
