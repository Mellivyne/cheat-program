package com.cheatprogram.ai.voice;

import com.cheatprogram.core.Lang;
import com.cheatprogram.util.DeviceLanguage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Local-only microphone preferences. Deliberately outside module profiles and configuration codes. */
public final class VoiceInputConfig {
    public boolean enabled,autoSend=true,autoStop=true,showHud=true;
    public int key=-1,maxSeconds=45,silenceMillis=1100,recognitionTimeout=180;
    public double threshold=.015;
    public String engine="windows",language=DeviceLanguage.system(),microphone="",python="python",model="",device="cpu";
    public static final List<String> LANGUAGES=List.of("auto","zh","en","ja","ko","zh_tw","es","fr","de");
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static Path path(){return FabricLoader.getInstance().getConfigDir().resolve("cheatprogram/voice-input.json");}
    public static VoiceInputConfig load(){
        VoiceInputConfig cfg=new VoiceInputConfig();
        try{Path p=path();if(Files.isRegularFile(p)&&Files.size(p)<16384){VoiceInputConfig saved=JSON.fromJson(Files.readString(p),VoiceInputConfig.class);if(saved!=null)cfg=saved;}}catch(Exception ignored){ }
        cfg.sanitize();return cfg;
    }
    public void sanitize(){
        key=key>=32&&key<=348||key>=1002&&key<=1007?key:-1;
        maxSeconds=Math.max(5,Math.min(300,maxSeconds));silenceMillis=Math.max(400,Math.min(5000,silenceMillis));recognitionTimeout=Math.max(15,Math.min(900,recognitionTimeout));
        threshold=Double.isFinite(threshold)?Math.max(.001,Math.min(.3,threshold)):.015;
        engine="whisper".equals(engine)?"whisper":"windows";device="cuda".equals(device)?"cuda":"cpu";
        language=language==null?DeviceLanguage.system():LANGUAGES.contains(language)?language:"en";
        microphone=clean(microphone,256);python=clean(python,4096);model=clean(model,4096);if(python.isBlank())python="python";
    }
    private static String clean(String s,int limit){if(s==null)return "";s=s.replaceAll("[\\x00-\\x1f\\x7f]","").strip();return s.substring(0,Math.min(limit,s.length()));}
    public void save(){synchronized(com.cheatprogram.core.Config.persistenceLock()){if(!com.cheatprogram.core.Config.persistenceAllowed())return;sanitize();try{Path p=path();Files.createDirectories(p.getParent());Path tmp=p.resolveSibling("voice-input.json.tmp");Files.writeString(tmp,JSON.toJson(this),StandardCharsets.UTF_8);try{Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,p,StandardCopyOption.REPLACE_EXISTING);}}catch(Exception ignored){ }}}
    public String actualLanguage(){return language.equals("auto")?Lang.language():language;}
    public String culture(){return switch(actualLanguage()){case "zh"->"zh-CN";case "zh_tw"->"zh-TW";case "ja"->"ja-JP";case "ko"->"ko-KR";case "es"->"es-ES";case "fr"->"fr-FR";case "de"->"de-DE";default->"en-US";};}
    public String whisperLanguage(){return actualLanguage().equals("zh_tw")?"zh":actualLanguage();}
    public VoiceInputConfig snapshot(){VoiceInputConfig copy=JSON.fromJson(JSON.toJson(this),VoiceInputConfig.class);copy.sanitize();return copy;}
}
