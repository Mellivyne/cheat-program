package com.cheatprogram.dev;

import com.cheatprogram.ai.AiParticleDesigner;
import com.cheatprogram.core.*;
import com.cheatprogram.util.ParticleStudio;
import com.google.gson.*;

/** Isolated runtime checks: generation data must remain portable and cannot replace the live project. */
public final class ParticleDesignerCheck {
    public static void run()throws Exception{
        var project=ParticleStudio.project();int rounds=project.rounds.size(),emitters=ParticleStudio.total();long revision=ParticleStudio.revision();
        JsonObject raw=JsonParser.parseString("""
            {"rounds":[{"name":"Orbit fixture","duration":200,"hold":false,"emitters":[
                {"particle":"minecraft:end_rod","x":0,"y":1,"z":0,"count":2,"every":4,"path":{"kind":"circle","radius":1.5,"duration":100}}
            ]}]}
            """).getAsJsonObject();
        var result=AiParticleDesigner.decode(raw);
        if(!result.code().startsWith("CPS1.")||result.prepared().particles().rounds.size()!=1)throw new AssertionError("Missing code or composition");
        var decoded=ScopedPresetCodec.decode(ScopedPresetCodec.Scope.PARTICLES,result.code());
        var prepared=FeaturePresets.validate(decoded);var emitter=prepared.particles().rounds.getFirst().emitters.getFirst();
        if(emitter.fixed||!emitter.world.isEmpty()||!emitter.dimension.isEmpty()||emitter.anchorX!=0||emitter.anchorY!=0||emitter.anchorZ!=0)throw new AssertionError("Private anchors leaked into code");
        if(emitter.count!=2||emitter.every!=4||emitter.path.radius!=1.5||!emitter.path.kind.equals("circle"))throw new AssertionError("Composition changed across code round trip");
        if(decoded.data().getAsJsonObject("settings").size()!=0)throw new AssertionError("AI changed unrelated settings");
        String payload=decoded.data().toString();for(String secret:new String[]{"apiKey","Authorization","endpoint","images","data:image"})if(payload.contains(secret))throw new AssertionError("Credentials or images in particle code");
        for(String bad:new String[]{"fixed","apiKey","world","dimension","anchorX"}){
            var invalid=raw.deepCopy();invalid.getAsJsonArray("rounds").get(0).getAsJsonObject().getAsJsonArray("emitters").get(0).getAsJsonObject().addProperty(bad,"private");
            rejected(invalid);
        }
        var invalid=raw.deepCopy();invalid.getAsJsonArray("rounds").get(0).getAsJsonObject().getAsJsonArray("emitters").get(0).getAsJsonObject().addProperty("count",1.5);rejected(invalid);
        invalid=raw.deepCopy();invalid.getAsJsonArray("rounds").get(0).getAsJsonObject().getAsJsonArray("emitters").get(0).getAsJsonObject().getAsJsonObject("path").addProperty("radius",-1);rejected(invalid);
        if(project!=ParticleStudio.project()||rounds!=project.rounds.size()||emitters!=ParticleStudio.total()||revision!=ParticleStudio.revision())throw new AssertionError("Generating a draft mutated the current project");
        System.out.println("PARTICLE_DESIGNER_CHECK_PASS code-roundtrip native-data private-content-rejection invalid-values project-unchanged");
    }
    private static void rejected(JsonObject raw)throws Exception{
        try{AiParticleDesigner.decode(raw);throw new AssertionError("Invalid/private particle design accepted");}catch(java.io.IOException|IllegalArgumentException expected){}
    }
}
