package com.cheatprogram.ai.voice;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cheatprogram.core.Config;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Only small local configuration is persisted; model files are selected explicitly. */
public final class VoiceSettings {
    public static final VoiceSettings INSTANCE=new VoiceSettings();
    public boolean enabled=true;
    public String pythonExecutable="",device="cpu",modelPath="",voicesPath="",vocabConfigPath="",engine="kokoro_onnx",localEndpoint="http://127.0.0.1:9880/tts";
    public int threads=4;
    private volatile String ioError="";
    private VoiceSettings(){loadFromDisk();}
    public static Path directory(){return FabricLoader.getInstance().getConfigDir().resolve("cheatprogram-open").resolve("voice");}
    public synchronized JsonObject save(){
        JsonObject j=new JsonObject();j.addProperty("enabled",enabled);j.addProperty("pythonExecutable",pythonExecutable);j.addProperty("threads",threads);
        j.addProperty("device",device);j.addProperty("modelPath",modelPath);j.addProperty("voicesPath",voicesPath);j.addProperty("vocabConfigPath",vocabConfigPath);
        j.addProperty("engine",engine);j.addProperty("localEndpoint",localEndpoint);return j;
    }
    public synchronized void saveToDisk(){
        synchronized(Config.persistenceLock()){
        if(!Config.persistenceAllowed())return;
        try{Files.createDirectories(directory());Path tmp=directory().resolve("settings.json.tmp");Files.writeString(tmp,new GsonBuilder().setPrettyPrinting().create().toJson(save()),StandardCharsets.UTF_8);Files.move(tmp,directory().resolve("settings.json"),StandardCopyOption.REPLACE_EXISTING);ioError="";}
        catch(Exception e){ioError="语音设置保存失败: "+e.getMessage();}
        }
    }
    public synchronized void loadFromDisk(){
        try{Path path=directory().resolve("settings.json");if(!Files.isRegularFile(path))return;JsonObject j=JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject();
            enabled=VoiceProfile.bool(j,"enabled",true);pythonExecutable=VoiceProfile.text(j,"pythonExecutable","");device=VoiceProfile.text(j,"device","cpu");
            modelPath=VoiceProfile.text(j,"modelPath","");voicesPath=VoiceProfile.text(j,"voicesPath","");vocabConfigPath=VoiceProfile.text(j,"vocabConfigPath","");
            engine=VoiceProfile.text(j,"engine","kokoro_onnx");localEndpoint=VoiceProfile.text(j,"localEndpoint","http://127.0.0.1:9880/tts");threads=(int)VoiceProfile.number(j,"threads",4,1,32);ioError="";
        }catch(Exception e){ioError="语音设置读取失败: "+e.getMessage();}
    }
    public String diagnostics(){
        String model=exists(modelPath)?"已选择本地模型":"未选择有效 ONNX 模型";
        String voices=exists(voicesPath)?"已选择本地音色包":"未选择有效 voices.bin 音色包";
        String python;
        try{
            Path executable=resolvePython();
            python=preparedEnvironment(executable)?"本地依赖已准备（尚未合成验证）":"已找到 Python，依赖将在读取时检查；可点“安装本地语音环境”准备";
        }catch(IllegalStateException e){python=e.getMessage();}
        return String.join("；",python,model,voices,"设备 "+device,ioError).replaceAll("；$","");
    }
    /** Never guess a system Python: its version and packages can differ from the prepared voice venv. */
    Path resolvePython(){
        String configured=pythonExecutable==null?"":pythonExecutable.strip();
        Path executable;
        try{executable=configured.isBlank()?directory().resolve(".venv/Scripts/python.exe"):Path.of(configured);}
        catch(Exception e){throw new IllegalStateException("Python 路径无效，请在本地引擎页重新选择 Python 可执行文件");}
        if(!Files.isRegularFile(executable))throw new IllegalStateException(configured.isBlank()
                ?"尚未安装本地语音环境：请到“本地引擎”页点“安装本地语音环境”或“安装并下载中文模型／音色”"
                :"所选 Python 文件不存在，请重新选择，或点“安装本地语音环境”创建专用环境");
        return executable.toAbsolutePath().normalize();
    }
    /** The newest local pack is selected automatically only while the chosen file is absent. */
    public synchronized void discoverLocalAssets(){
        if(!exists(modelPath))modelPath=findAsset(directory().resolve("models"),".onnx",true);
        if(!exists(voicesPath)){
            String found=findAsset(directory().resolve("voicepacks"),".bin",false);
            if(found.isBlank())found=findAsset(directory().resolve("voicepacks"),".npz",false);
            if(found.isBlank())found=findAsset(directory().resolve("models"),".bin",false);
            if(!found.isBlank())voicesPath=found;
        }
    }
    private static String findAsset(Path folder,String extension,boolean model){
        if(!Files.isDirectory(folder))return "";
        try(var paths=Files.list(folder)){
            return paths.filter(p->Files.isRegularFile(p)&&!Files.isSymbolicLink(p)&&p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(extension))
                    .filter(p->{try{return Files.size(p)>(model?1024*1024:1024);}catch(Exception e){return false;}})
                    .sorted(java.util.Comparator.comparingLong((Path p)->{try{return Files.getLastModifiedTime(p).toMillis();}catch(Exception e){return 0;}}).reversed())
                    .limit(1).map(p->p.toAbsolutePath().normalize().toString()).findFirst().orElse("");
        }catch(Exception ignored){return "";}
    }
    static boolean preparedEnvironment(Path executable){
        try{
            Path manifest=directory().resolve("environment.json");
            if(!Files.isRegularFile(manifest))return false;
            JsonObject record=JsonParser.parseString(Files.readString(manifest,StandardCharsets.UTF_8)).getAsJsonObject();
            String prepared=VoiceProfile.text(record,"python","");
            return VoiceProfile.bool(record,"dependenciesReady",false)&&!prepared.isBlank()
                    &&executable.equals(Path.of(prepared).toAbsolutePath().normalize());
        }catch(Exception e){return false;}
    }
    static boolean exists(String path){try{return path!=null&&!path.isBlank()&&Files.isRegularFile(Path.of(path));}catch(Exception e){return false;}}
}
