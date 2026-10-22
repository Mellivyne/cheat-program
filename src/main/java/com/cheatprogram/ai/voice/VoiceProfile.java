package com.cheatprogram.ai.voice;

import com.cheatprogram.util.DeviceLanguage;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/** Per actor voice configuration. No API keys or cloud credentials are used. */
public final class VoiceProfile {
    public boolean enabled=false, emotionEnabled=true;
    public String voiceID="zf_001", language=defaultLanguage(), modelPath="", voicePackPath="", vocabConfigPath="", device="inherit";
    public double speed=1, pitch=0, volume=1, strength=1;
    public final Map<String,String> emotionVoices=new LinkedHashMap<>();
    public JsonObject save(){
        JsonObject j=new JsonObject();j.addProperty("enabled",enabled);j.addProperty("emotionEnabled",emotionEnabled);
        j.addProperty("voiceID",voiceID);j.addProperty("language",language);j.addProperty("modelPath",modelPath);
        j.addProperty("voicePackPath",voicePackPath);j.addProperty("vocabConfigPath",vocabConfigPath);j.addProperty("device",device);
        j.addProperty("speed",speed);j.addProperty("pitch",pitch);j.addProperty("volume",volume);j.addProperty("strength",strength);
        JsonObject voices=new JsonObject();emotionVoices.forEach(voices::addProperty);j.add("emotionVoices",voices);return j;
    }
    public static VoiceProfile load(JsonObject j){
        VoiceProfile p=new VoiceProfile();if(j==null)return p;
        p.enabled=bool(j,"enabled",false);p.emotionEnabled=bool(j,"emotionEnabled",true);
        p.voiceID=text(j,"voiceID","zf_001");p.language=text(j,"language",p.language);if(p.language.isBlank())p.language=defaultLanguage();p.modelPath=text(j,"modelPath","");
        p.voicePackPath=text(j,"voicePackPath","");p.vocabConfigPath=text(j,"vocabConfigPath","");p.device=text(j,"device","inherit");
        p.speed=number(j,"speed",1,.5,2);p.pitch=number(j,"pitch",0,-12,12);p.volume=number(j,"volume",1,0,2);p.strength=number(j,"strength",1,0,2);
        if(j.has("emotionVoices")&&j.get("emotionVoices").isJsonObject())j.getAsJsonObject("emotionVoices").entrySet().forEach(e->{if(e.getValue().isJsonPrimitive())p.emotionVoices.put(e.getKey(),e.getValue().getAsString());});
        return p;
    }
    private static String defaultLanguage(){String language=DeviceLanguage.system();return switch(language){case "zh_tw"->"zh";case "en"->"en-us";default->language;};}
    static String text(JsonObject j,String key,String fallback){try{return j.has(key)?j.get(key).getAsString():fallback;}catch(Exception e){return fallback;}}
    static boolean bool(JsonObject j,String key,boolean fallback){try{return j.has(key)?j.get(key).getAsBoolean():fallback;}catch(Exception e){return fallback;}}
    static double number(JsonObject j,String key,double fallback,double min,double max){try{double v=j.get(key).getAsDouble();return Double.isFinite(v)?Math.max(min,Math.min(max,v)):fallback;}catch(Exception e){return fallback;}}
}
