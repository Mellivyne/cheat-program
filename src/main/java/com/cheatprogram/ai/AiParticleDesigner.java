package com.cheatprogram.ai;

import com.cheatprogram.core.FeaturePresets;
import com.cheatprogram.core.ScopedPresetCodec;
import com.cheatprogram.module.visual.ParticleEffects;
import com.cheatprogram.util.ParticleStudio;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Produces validated particle data and a portable code, never executable model output. */
public final class AiParticleDesigner {
    public static final AiConfig CONNECTION=new AiConfig(FabricLoader.getInstance().getConfigDir().resolve("cheatprogram-open/ai-particles.json"));
    private static final Gson GSON=new Gson();
    private static final ExecutorService IO=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"Cheat Program particle designer");t.setDaemon(true);return t;});
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Set<String> EMITTER_KEYS=Set.of("particle","options","shown","rotateWithPlayer","symmetry","x","y","z","pitch","yaw","roll","vx","vy","vz","spread","scale","every","count","start","duration","lifetime","path");
    private static final Set<String> ROUND_KEYS=Set.of("name","duration","hold","emitters");
    public record Design(JsonObject plan,FeaturePresets.Prepared prepared,String code) { }

    public CompletableFuture<Design> generate(String requirement,JsonObject previous,List<AiImage> images,BooleanSupplier current){
        // Credentials and attachments are captured for this explicitly submitted request.
        var uri=CONNECTION.completionUri();String key=CONNECTION.apiKey(),model=CONNECTION.model();List<AiImage> attached=List.copyOf(images);
        JsonObject context=previous==null?null:previous.deepCopy();
        return CompletableFuture.supplyAsync(()->{
            try{
                if(key.isBlank())throw new IllegalArgumentException("ui.aiParticles.needKey");
                JsonArray history=new JsonArray();
                history.add(AiProtocol.message("system",instructions()));
                if(context!=null)history.add(AiProtocol.message("assistant",context.toString()));
                history.add(AiProtocol.message("user",requirement,attached));
                var exchange=AiProtocol.exchange(HTTP,uri,model,key,history,new JsonArray(),current);
                if(!current.getAsBoolean())throw new CancellationException();
                String text=exchange.reply().get("content").getAsString().strip();
                if(text.startsWith("```")){int start=text.indexOf('\n'),end=text.lastIndexOf("```");if(start>=0&&end>start)text=text.substring(start+1,end).strip();}
                return decode(JsonParser.parseString(text).getAsJsonObject());
            }catch(CancellationException e){throw e;}catch(Exception e){
                String message=e.getMessage();
                if(message!=null&&(message.startsWith("ui.aiParticles.")||message.startsWith("ui.ai.images.")))throw new CompletionException(new IllegalArgumentException(message));
                if(!attached.isEmpty()&&e instanceof AiProtocol.ProtocolException)throw new CompletionException(new IllegalArgumentException("ui.aiParticles.imageRejected"));
                throw new CompletionException(new IllegalArgumentException("ui.aiParticles.failed"));
            }
        },IO);
    }

    private static String instructions(){
        StringBuilder types=new StringBuilder();
        for(var choice:ParticleEffects.choices())types.append(choice.id()).append(" options=").append(ParticleEffects.defaultOptions(choice.id())).append('\n');
        return "Design a real local Minecraft particle composition for Minecraft "+FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString()+". Return ONLY JSON, no prose or encoded code: {\"rounds\":[{\"name\":\"name\",\"duration\":200,\"hold\":false,\"emitters\":[{\"particle\":\"minecraft:end_rod\",\"x\":0,\"y\":1,\"z\":0,\"every\":3,\"count\":1,\"path\":{\"kind\":\"circle\",\"radius\":1,\"duration\":100,\"plane\":\"xz\"}}]}]}. "
                +"Do not return scripts, commands, file paths, credentials, URLs, world names or fixed anchors. Produce the COMPLETE revised design after each request, preserving unchanged parts. Native options must match the particle's exact SNBT schema shown below. Prefer simple registered particle types where possible. "
                +"Optional emitter fields: options:string, shown:boolean, rotateWithPlayer:boolean, symmetry:none/x/y/z/xy/xz/yz/xyz, x/y/z (-16..16 recommended), pitch/yaw/roll(-360..360), vx/vy/vz(-4..4), spread(0..8), scale(.05..8), every(1..1200 ticks), count(1..100), start/duration(0..72000), lifetime(0..1200 ticks). Keep every>=2,count<=8 unless explicitly requested; budget for smooth rendering. "
                +"Optional path: kind=none/line/circle/helix/figure8/wave/spiral/bounce/custom, loop=loop/pingpong/once, plane=xz/xy/yz, interpolation=linear/smooth, clock=emitter/particle, duration=1..72000, radius=0..16, height=-16..16, phase=0..360, nativeMotion:boolean, points=[{x,y,z},...] (2..64 points, each -16..16). Max128 rounds,256 emitters per round,4096 total. Round duration1..72000 ticks,name<=80 chars. Omitted fields receive editor defaults. "
                +"If images are attached, use their composition, palette and movement cues as a visual reference while building native particles. Never claim exact reproduction of unrelated image content. The application validates the data and generates the CPS1 share code itself. Available particles and option templates:\n"+types;
    }

    /** A complete validation boundary, shared by network results and development checks. */
    public static Design decode(JsonObject raw)throws IOException{
        if(raw==null)throw invalid();
        ScopedPresetCodec.only(raw,Set.of("rounds"));
        JsonArray rounds=raw.getAsJsonArray("rounds");if(rounds==null||rounds.isEmpty()||rounds.size()>ParticleStudio.MAX_ROUNDS)throw invalid();
        JsonArray normalized=new JsonArray();int count=0;
        Set<String> registered=new HashSet<>();for(var choice:ParticleEffects.choices())registered.add(choice.id());
        for(JsonElement item:rounds){
            JsonObject row=item.getAsJsonObject();ScopedPresetCodec.only(row,ROUND_KEYS);
            JsonObject round=GSON.toJsonTree(new ParticleStudio.Round()).getAsJsonObject();
            for(var e:row.entrySet())if(!e.getKey().equals("emitters"))round.add(e.getKey(),e.getValue().deepCopy());
            exactInteger(round.get("duration"));
            JsonArray emitters=row.getAsJsonArray("emitters");if(emitters==null||emitters.isEmpty()||emitters.size()>ParticleStudio.MAX_EMITTERS||(count+=emitters.size())>ParticleStudio.MAX_TOTAL)throw invalid();
            JsonArray clean=new JsonArray();
            for(JsonElement element:emitters){
                JsonObject input=element.getAsJsonObject();ScopedPresetCodec.only(input,EMITTER_KEYS);
                JsonObject emitter=GSON.toJsonTree(new ParticleStudio.Emitter()).getAsJsonObject();
                for(var entry:input.entrySet()){
                    if(entry.getKey().equals("path")){
                        JsonObject path=emitter.getAsJsonObject("path");for(var property:entry.getValue().getAsJsonObject().entrySet())path.add(property.getKey(),property.getValue().deepCopy());
                    }else emitter.add(entry.getKey(),entry.getValue().deepCopy());
                }
                for(String field:List.of("every","count","start","duration","lifetime"))exactInteger(emitter.get(field));
                if(!registered.contains(emitter.get("particle").getAsString()))throw invalid();
                emitter.addProperty("fixed",false);emitter.addProperty("world","");emitter.addProperty("dimension","");
                clean.add(emitter);
            }
            round.add("emitters",clean);normalized.add(round);
        }
        JsonObject project=new JsonObject();project.addProperty("version",2);project.add("rounds",normalized);
        JsonObject data=new JsonObject();data.add("settings",new JsonObject());data.add("project",project);
        var decoded=new ScopedPresetCodec.Decoded(ScopedPresetCodec.Scope.PARTICLES,data,Map.of());
        var prepared=FeaturePresets.validate(decoded);
        String code=ScopedPresetCodec.encode(ScopedPresetCodec.Scope.PARTICLES,data,Map.of());
        // Keep the compact original plan as revision context, without IDs or private coordinates.
        return new Design(raw.deepCopy(),prepared,code);
    }
    private static void exactInteger(JsonElement value){try{value.getAsBigDecimal().intValueExact();}catch(Exception error){throw invalid();}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("ui.aiParticles.invalid");}
}
