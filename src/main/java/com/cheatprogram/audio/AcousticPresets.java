package com.cheatprogram.audio;
import com.google.gson.JsonObject;
import java.util.Map;

/** One-time upgrade of untouched earlier defaults; later explicit choices are never rewritten. */
public final class AcousticPresets {
    private AcousticPresets(){}
    public static JsonObject migrate(JsonObject original){
        try{if(original.has("signalDefaultsVersion")&&original.get("signalDefaultsVersion").getAsInt()>=3)return original;}catch(RuntimeException invalid){}
        JsonObject migrated=original.deepCopy();
        // The user requested the preceding sampling preset back, retaining added routing/denoise controls.
        for(var entry:Map.of("rays",2048,"bounces",8,"budget",256,"lineLimit",8192).entrySet())migrated.addProperty(entry.getKey(),entry.getValue());
        double volume=130;try{if(migrated.has("volume"))volume=Math.max(130,migrated.get("volume").getAsDouble());}catch(RuntimeException invalid){}
        migrated.addProperty("volume",Double.isFinite(volume)?Math.min(200,volume):130);
        migrated.addProperty("signalDefaultsVersion",3);return migrated;
    }
}
