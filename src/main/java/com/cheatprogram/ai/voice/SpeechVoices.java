package com.cheatprogram.ai.voice;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import net.minecraft.client.Minecraft;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Local Python inference and JavaSound playback; neither runs on a Minecraft thread. */
public final class SpeechVoices {
    private static final ExecutorService WORK=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Local AI speech");t.setDaemon(true);return t;});
    private static final ScheduledExecutorService TIMEOUT=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Local speech timeout");t.setDaemon(true);return t;});
    private static final ExecutorService SETUP=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"Local speech setup");t.setDaemon(true);return t;});
    private static final ConcurrentLinkedQueue<Job> QUEUE=new ConcurrentLinkedQueue<>();
    private static final ConcurrentHashMap<UUID,AtomicLong> GENERATIONS=new ConcurrentHashMap<>();
    private static final AtomicLong EPOCH=new AtomicLong();
    private static final AtomicBoolean DRAINING=new AtomicBoolean();
    private static final AtomicBoolean INSTALLING=new AtomicBoolean();
    private static final AtomicLong INSTALL_GENERATION=new AtomicLong();
    private static final Object PROTOCOL=new Object();
    private static volatile Process process;
    private static volatile Process installation;
    private static BufferedReader reader;
    private static BufferedWriter writer;
    private static String processPython="";
    private static volatile SourceDataLine playback;
    private static volatile UUID activeId;
    private static volatile String status="尚未启动本地语音",lastError="",provider="";
    private static Object boundWorld;
    static{Runtime.getRuntime().addShutdownHook(new Thread(()->{shutdown();destroyTree(installation);},"Stop local speech"));}
    private record Job(UUID id,VoiceProfile voice,String text,String emotion,double intensity,long generation,long epoch){}
    private SpeechVoices(){}

    public static void speak(UUID id,VoiceProfile voice,String text,String emotion,double intensity){
        if(!Config.persistenceAllowed())return;
        if(id==null||voice==null||!voice.enabled||!VoiceSettings.INSTANCE.enabled||text==null||text.isBlank())return;
        long generation=GENERATIONS.computeIfAbsent(id,ignored->new AtomicLong()).get();
        QUEUE.add(new Job(id,VoiceProfile.load(voice.save()),text,emotion==null?"neutral":emotion,
                Double.isFinite(intensity)?Math.max(0,Math.min(1,intensity)):0,generation,EPOCH.get()));
        drain();
    }
    private static void drain(){
        if(INSTALLING.get())return;
        if(!DRAINING.compareAndSet(false,true))return;
        WORK.execute(()->{
            try{Job job;while(!INSTALLING.get()&&(job=QUEUE.poll())!=null){
                if(cancelled(job))continue;activeId=job.id;
                try{readMessage(job);}catch(Exception e){if(!cancelled(job)){lastError=message(e);status="语音未就绪: "+lastError;}}
                finally{activeId=null;}
            }}finally{DRAINING.set(false);if(!QUEUE.isEmpty())drain();}
        });
    }
    private static boolean cancelled(Job job){
        AtomicLong generation=GENERATIONS.get(job.id);
        return job.epoch!=EPOCH.get()||generation==null||generation.get()!=job.generation||!VoiceSettings.INSTANCE.enabled;
    }
    private static void readMessage(Job job)throws Exception{
        String text=job.text.replaceAll("§.","").strip();
        // Short windows bound first-audio latency and memory; every window is retained, with no total text cap.
        for(int offset=0;offset<text.length()&&!cancelled(job);){
            int end=offset,preferred=-1,count=0;
            while(end<text.length()&&count++<160){int cp=text.codePointAt(end);end+=Character.charCount(cp);
                if("。！？!?；;\n".indexOf(cp)>=0){preferred=end;if(count>=12)break;}
                else if(Character.isWhitespace(cp))preferred=end;
            }
            if(end<text.length()&&preferred>offset)end=preferred;
            String segment=text.substring(offset,end).strip();offset=end;if(segment.isEmpty())continue;
            Path output=null;
            try{
                setup();Files.createDirectories(VoiceSettings.directory().resolve("cache"));
                output=Files.createTempFile(VoiceSettings.directory().resolve("cache"),"speech-",".wav");
                JsonObject request=request(job.voice);request.addProperty("command","speak");request.addProperty("text",segment);
                request.addProperty("emotion",job.emotion);request.addProperty("intensity",job.intensity);
                request.addProperty("output",output.toAbsolutePath().toString());status="本地合成中";
                if(cancelled(job))break;
                JsonObject response=exchange(request,job);
                if(response.has("provider"))provider=response.get("provider").getAsString();
                if(!cancelled(job)){status="播放中 · "+provider;play(output,job);lastError="";}
            }finally{if(output!=null)deleteOutput(output);}
        }
        if(!cancelled(job))status="本地语音就绪 · "+provider;
    }
    private static JsonObject request(VoiceProfile profile){
        JsonObject request=new JsonObject();request.add("profile",profile.save());request.add("settings",VoiceSettings.INSTANCE.save());return request;
    }
    public static CompletableFuture<List<String>> listVoices(VoiceProfile profile){
        if(!Config.persistenceAllowed())return CompletableFuture.failedFuture(new IllegalStateException(Lang.t("ui.voice.env.reset")));
        if(INSTALLING.get())return CompletableFuture.failedFuture(new IllegalStateException("本地语音环境正在准备，请完成后读取音色"));
        VoiceProfile snapshot=VoiceProfile.load(profile==null?new JsonObject():profile.save());
        long epoch=EPOCH.get();
        return CompletableFuture.supplyAsync(()->{
            try{if(INSTALLING.get())throw new IllegalStateException("本地语音正在准备，请完成后读取音色");
                setup();JsonObject request=request(snapshot);request.addProperty("command","voices");JsonObject result=exchange(request,null,epoch);
                List<String> list=new ArrayList<>();JsonArray voices=result.getAsJsonArray("voices");if(voices!=null)for(var v:voices)list.add(v.getAsString());return List.copyOf(list);
            }catch(Exception e){lastError=message(e);throw new java.util.concurrent.CompletionException(e);}
        },WORK);
    }
    private static JsonObject exchange(JsonObject request,Job job)throws Exception{
        return exchange(request,job,EPOCH.get());
    }
    private static JsonObject exchange(JsonObject request,Job job,long epoch)throws Exception{
        synchronized(PROTOCOL){
            if(epoch!=EPOCH.get())throw new java.io.InterruptedIOException("语音环境已关闭");
            if(job!=null&&cancelled(job))throw new java.io.InterruptedIOException("语音已取消");
            // A missing local environment should direct the player to preparation, without launching
            // an unrelated global Python or producing its raw numpy traceback.
            VoiceSettings.INSTANCE.resolvePython();
            validateFiles(request);
            ensureProcess();Process current=process;
            if(epoch!=EPOCH.get()){killProcess();throw new java.io.InterruptedIOException("语音环境已关闭");}
            if(job!=null&&cancelled(job)){killProcess();throw new java.io.InterruptedIOException("语音已取消");}
            var timer=TIMEOUT.schedule(()->{if(current.isAlive())current.destroyForcibly();},300,TimeUnit.SECONDS);
            try{
                writer.write(request.toString());writer.newLine();writer.flush();
                String line=reader.readLine();if(line==null)throw new IllegalStateException(lastError.isBlank()?"本地 Python 已退出；请检查依赖和模型文件":lastError);
                JsonObject result=JsonParser.parseString(line).getAsJsonObject();
                if(epoch!=EPOCH.get())throw new java.io.InterruptedIOException("语音环境已关闭");
                if(!result.has("ok")||!result.get("ok").getAsBoolean())throw new IllegalStateException(VoiceProfile.text(result,"error","本地语音失败"));
                return result;
            }catch(Exception e){killProcess();throw e;}finally{timer.cancel(false);}
        }
    }
    private static void validateFiles(JsonObject request){
        VoiceSettings.INSTANCE.discoverLocalAssets();
        JsonObject settings=request.getAsJsonObject("settings"),profile=request.getAsJsonObject("profile");
        if(settings==null)settings=new JsonObject();if(profile==null)profile=new JsonObject();
        if(!VoiceSettings.exists(VoiceProfile.text(settings,"modelPath","")))settings.addProperty("modelPath",VoiceSettings.INSTANCE.modelPath);
        if(!VoiceSettings.exists(VoiceProfile.text(settings,"voicesPath","")))settings.addProperty("voicesPath",VoiceSettings.INSTANCE.voicesPath);
        if("speak".equals(VoiceProfile.text(request,"command","")))
            requiredFile(inheritedPath(profile,"modelPath",settings,"modelPath"),"ONNX 模型");
        requiredFile(inheritedPath(profile,"voicePackPath",settings,"voicesPath"),"voices.bin / NPZ 音色包");
        String config=inheritedPath(profile,"vocabConfigPath",settings,"vocabConfigPath");
        if(!config.isBlank())requiredFile(config,"config.json 词表");
    }
    private static String inheritedPath(JsonObject profile,String key,JsonObject settings,String fallback){
        String own=VoiceProfile.text(profile,key,"").strip();return own.isBlank()?VoiceProfile.text(settings,fallback,"").strip():own;
    }
    private static void requiredFile(String path,String label){
        if(!VoiceSettings.exists(path))throw new IllegalStateException("请先在“本地引擎”页选择有效的本地 "+label+"；也可点“安装并下载中文模型／音色”自动准备");
    }
    private static void ensureProcess()throws Exception{
        VoiceSettings settings=VoiceSettings.INSTANCE;
        if(!"kokoro_onnx".equals(settings.engine))throw new IllegalStateException("当前实现为本地 Kokoro ONNX；其他引擎尚未启用");
        Path executable=settings.resolvePython();List<String> command=new ArrayList<>();
        command.add(executable.toString());
        String key=String.join("\u0000",command);
        if(process!=null&&process.isAlive()&&key.equals(processPython))return;
        killProcess();
        if(reader!=null)try{reader.close();}catch(Exception ignored){}
        if(writer!=null)try{writer.close();}catch(Exception ignored){}
        command.add("-u");command.add(VoiceSettings.directory().resolve("kokoro_worker.py").toAbsolutePath().toString());
        command.add("--output-root");command.add(VoiceSettings.directory().resolve("cache").toAbsolutePath().toString());
        ProcessBuilder builder=new ProcessBuilder(command);builder.directory(VoiceSettings.directory().toFile());
        builder.environment().put("PYTHONUTF8","1");builder.environment().put("PYTHONDONTWRITEBYTECODE","1");
        builder.environment().put("HF_HUB_OFFLINE","1");builder.environment().put("TRANSFORMERS_OFFLINE","1");
        builder.environment().put("HF_HUB_DISABLE_TELEMETRY","1");
        VoiceEnvironment.configureCaches(builder);
        lastError="";Process started;
        synchronized(Config.persistenceLock()){
            if(!Config.persistenceAllowed())throw new IllegalStateException(Lang.t("ui.voice.env.reset"));
            started=builder.start();process=started;processPython=key;
        }
        reader=new BufferedReader(new InputStreamReader(started.getInputStream(),StandardCharsets.UTF_8));
        writer=new BufferedWriter(new OutputStreamWriter(started.getOutputStream(),StandardCharsets.UTF_8));
        Thread errors=new Thread(()->{try(var stream=new BufferedReader(new InputStreamReader(started.getErrorStream(),StandardCharsets.UTF_8))){
            String line;while((line=stream.readLine())!=null){if(line.length()>1000)line=line.substring(0,1000);if(!line.isBlank()&&process==started)lastError=line;}
        }catch(Exception ignored){}},"Local speech diagnostics");errors.setDaemon(true);errors.start();
        // Probe inside this exact selected interpreter, without loading a model or synthesizing audio.
        var startupTimeout=TIMEOUT.schedule(()->{if(started.isAlive())started.destroyForcibly();},30,TimeUnit.SECONDS);
        try{
            writer.write("{\"command\":\"probe\"}");writer.newLine();writer.flush();
            String line=reader.readLine();
            if(line==null)throw new IllegalStateException("本地 Python 无法启动，请点“安装本地语音环境”修复；详情见语音目录 setup.log");
            JsonObject probe=JsonParser.parseString(line).getAsJsonObject();
            if(!VoiceProfile.bool(probe,"ok",false))throw new IllegalStateException(VoiceProfile.text(probe,"error","本地语音依赖未准备，请点“安装本地语音环境”"));
        }catch(Exception e){killProcess();throw e;}finally{startupTimeout.cancel(false);}
    }
    private static void play(Path wav,Job job)throws Exception{
        try(AudioInputStream input=AudioSystem.getAudioInputStream(wav.toFile())){
            AudioFormat source=input.getFormat();AudioFormat pcm=new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,source.getSampleRate(),16,source.getChannels(),source.getChannels()*2,source.getSampleRate(),false);
            try(AudioInputStream audio=AudioSystem.getAudioInputStream(pcm,input)){
                SourceDataLine line=AudioSystem.getSourceDataLine(pcm);playback=line;
                try{line.open(pcm);line.start();byte[] buffer=new byte[4096];int count;
                    while(!cancelled(job)&&(count=audio.read(buffer))!=-1){
                        double gain=Math.max(0,Math.min(2,job.voice.volume));
                        if(gain!=1)for(int i=0;i+1<count;i+=2){int v=(short)((buffer[i]&255)|(buffer[i+1]<<8));int out=(int)Math.max(-32768,Math.min(32767,Math.round(v*gain)));buffer[i]=(byte)out;buffer[i+1]=(byte)(out>>8);}
                        int written=0;while(written<count&&!cancelled(job))written+=line.write(buffer,written,count-written);
                    }
                    if(!cancelled(job))line.drain();
                }finally{line.stop();line.flush();line.close();if(playback==line)playback=null;}
            }
        }
    }
    public static void stop(UUID id){
        if(id==null)return;GENERATIONS.computeIfAbsent(id,ignored->new AtomicLong()).incrementAndGet();QUEUE.removeIf(job->job.id.equals(id));
        if(id.equals(activeId)){stopPlayback();killProcess();}
    }
    public static void shutdown(){
        EPOCH.incrementAndGet();QUEUE.clear();GENERATIONS.clear();stopPlayback();killProcess();status="本地语音已停止";
    }
    private static void stopPlayback(){SourceDataLine line=playback;if(line!=null){try{line.stop();line.flush();line.close();}catch(Exception ignored){}playback=null;}}
    private static void killProcess(){Process old=process;process=null;destroyTree(old);}
    private static void destroyTree(Process owned){
        if(owned==null)return;
        try{owned.toHandle().descendants().forEach(child->{try{child.destroyForcibly();}catch(Exception ignored){}});}catch(Exception ignored){}
        try{owned.destroyForcibly();}catch(Exception ignored){}
    }
    private static void deleteOutput(Path output){
        try{Files.deleteIfExists(output);}catch(Exception first){
            TIMEOUT.schedule(()->{try{Files.deleteIfExists(output);}catch(Exception ignored){}},500,TimeUnit.MILLISECONDS);
        }
    }
    public static void tickLifecycle(){
        Object current=Minecraft.getInstance().level;
        if(boundWorld!=current){if(boundWorld!=null)shutdown();boundWorld=current;}
        if(!VoiceSettings.INSTANCE.enabled&&(!QUEUE.isEmpty()||activeId!=null||process!=null))shutdown();
    }
    public static String diagnostics(){return VoiceSettings.INSTANCE.diagnostics()+"；"+status+(lastError.isBlank()?"":"；"+lastError);}
    public static String lastError(){return lastError;}
    public static String status(UUID id){return id!=null&&id.equals(activeId)?status:"";}
    /** Runs only after an explicit GUI button click. Installation is never triggered by speak/setup. */
    public static CompletableFuture<String> installDefault(String device,boolean downloadModels){
        if(!Config.persistenceAllowed())return CompletableFuture.failedFuture(new IllegalStateException(Lang.t("ui.voice.env.reset")));
        if(!"cpu".equals(device)&&!"cuda".equals(device))return CompletableFuture.completedFuture("设备必须选择 CPU 或 NVIDIA CUDA");
        if(!INSTALLING.compareAndSet(false,true))return CompletableFuture.failedFuture(new IllegalStateException(Lang.t("ui.voice.env.busy")));
        VoiceEnvironment.cancelCheck();long token=INSTALL_GENERATION.incrementAndGet();
        shutdown();lastError="";status="正在准备本地语音环境；详情见 setup.log";
        return CompletableFuture.supplyAsync(()->{
            try{
                if(token!=INSTALL_GENERATION.get()||!Config.persistenceAllowed())throw new java.io.InterruptedIOException(Lang.t("ui.voice.env.cancelled"));
                setup();Path directory=VoiceSettings.directory();
                List<String> command=new ArrayList<>(List.of("powershell.exe","-NoProfile","-NonInteractive","-WindowStyle","Hidden","-ExecutionPolicy","Bypass","-File",
                        directory.resolve("install-voice.ps1").toAbsolutePath().toString(),"-Device",device,"-DownloadBaseModel"));
                if(downloadModels)command.add("-DownloadModels");
                if("cuda".equals(device))command.add("-CudaRuntimeDlls");
                String configuredPython=VoiceSettings.INSTANCE.pythonExecutable.strip();
                Path ownPython=directory.resolve(".venv/Scripts/python.exe").toAbsolutePath().normalize();
                if(!configuredPython.isBlank()&&VoiceSettings.exists(configuredPython)
                        &&!Path.of(configuredPython).toAbsolutePath().normalize().equals(ownPython)){
                    command.add("-Python");command.add(configuredPython);
                }
                Process running;
                synchronized(Config.persistenceLock()){
                    if(token!=INSTALL_GENERATION.get()||!Config.persistenceAllowed())throw new java.io.InterruptedIOException(Lang.t("ui.voice.env.cancelled"));
                    ProcessBuilder builder=new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("setup.log").toFile());
                    builder.environment().put("PYTHONUTF8","1");builder.environment().put("PYTHONDONTWRITEBYTECODE","1");
                    builder.environment().put("PIP_CACHE_DIR",directory.resolve("downloads/pip-cache").toString());
                    VoiceEnvironment.configureCaches(builder);
                    installation=running=builder.start();
                }
                long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(45);
                while(!running.waitFor(1,TimeUnit.SECONDS)){
                    if(token!=INSTALL_GENERATION.get()||!Config.persistenceAllowed())throw new java.io.InterruptedIOException(Lang.t("ui.voice.env.cancelled"));
                    if(System.nanoTime()>deadline)throw new IllegalStateException(Lang.t("ui.voice.env.installTimeout"));
                }
                int exit=running.exitValue();
                if(token!=INSTALL_GENERATION.get()||!Config.persistenceAllowed())throw new java.io.InterruptedIOException(Lang.t("ui.voice.env.cancelled"));
                if(exit!=0)throw new IllegalStateException("准备失败（退出码 "+exit+"），请查看 "+directory.resolve("setup.log"));
                if(!Files.isRegularFile(ownPython)||!VoiceSettings.preparedEnvironment(ownPython))
                    throw new IllegalStateException("准备脚本未生成有效的依赖清单，不能确认环境已安装；请查看 "+directory.resolve("setup.log"));
                VoiceSettings settings=VoiceSettings.INSTANCE;
                synchronized(settings){
                    Path python=directory.resolve(".venv/Scripts/python.exe"),model=directory.resolve("models/kokoro-v1.1-zh.onnx"),voices=directory.resolve("models/voices-v1.1-zh.bin");
                    settings.pythonExecutable=python.toAbsolutePath().toString();
                    if(!VoiceSettings.exists(settings.modelPath)&&Files.isRegularFile(model))settings.modelPath=model.toAbsolutePath().toString();
                    if(!VoiceSettings.exists(settings.voicesPath)&&Files.isRegularFile(voices))settings.voicesPath=voices.toAbsolutePath().toString();
                    settings.device=device;settings.saveToDisk();
                    settings.discoverLocalAssets();
                }
                lastError="";status=Lang.t("ui.voice.env.prepared",VoiceEnvironment.packs());return status;
            }catch(Exception e){lastError=message(e);status=Lang.t("ui.voice.env.installError",lastError);throw new java.util.concurrent.CompletionException(e);}
            finally{Process old=installation;destroyTree(old);if(old!=null)try{old.waitFor(1200,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}installation=null;INSTALLING.set(false);if(!QUEUE.isEmpty())drain();}
        },SETUP);
    }
    public static boolean installing(){return INSTALLING.get();}
    public static void cancelInstallation(){INSTALL_GENERATION.incrementAndGet();destroyTree(installation);}
    static CompletableFuture<Void> drainForReset(){return CompletableFuture.allOf(CompletableFuture.runAsync(()->{},SETUP),CompletableFuture.runAsync(()->{},WORK));}
    /** Extracts readable local scripts only; does not execute Python, install packages or download models. */
    public static synchronized void setup()throws java.io.IOException{
        synchronized(Config.persistenceLock()){
        if(!Config.persistenceAllowed())throw new java.io.IOException(Lang.t("ui.voice.env.reset"));
        Path directory=VoiceSettings.directory();Files.createDirectories(directory);
        Files.createDirectories(VoiceEnvironment.packs());
        Files.createDirectories(directory.resolve("cache"));
        for(String name:List.of("kokoro_worker.py","install-voice.ps1","check-environment.py","README.md","NOTICE.md")){
            try(var input=SpeechVoices.class.getResourceAsStream("/voice/"+name)){
                if(input==null)throw new java.io.IOException("缺少本地语音资源: "+name);
                Path destination=directory.resolve(name);byte[] bytes=input.readAllBytes();
                if(!Files.isRegularFile(destination)||!java.util.Arrays.equals(bytes,Files.readAllBytes(destination)))Files.write(destination,bytes);
            }
        }
        }
    }
    private static String message(Exception e){String value=e.getMessage();return value==null?e.getClass().getSimpleName():value;}
}
