package com.cheatprogram.ai;

import com.cheatprogram.util.BuildingFiles.Blueprint;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.CompoundTag;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Provider requests produce data only. Nothing is placed in a world without the normal import action. */
public final class AiBuildingDesigner {
    public static final AiConfig CONNECTION=new AiConfig(FabricLoader.getInstance().getConfigDir().resolve("cheatprogram-open/ai-building.json"));
    private static final ExecutorService IO=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"Cheat Program building designer");t.setDaemon(true);return t;});
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
    public record Design(Blueprint building,JsonObject plan) { }
    public CompletableFuture<Design> generate(String requirement,JsonObject previous,java.util.function.BooleanSupplier current){return generate(requirement,previous,List.of(),current);}
    public CompletableFuture<Design> generate(String requirement,JsonObject previous,List<AiImage> attachments,java.util.function.BooleanSupplier current){
        List<AiImage> images=List.copyOf(attachments);
        final java.net.URI uri;final String key,model;
        try{uri=CONNECTION.completionUri();key=CONNECTION.apiKey();model=CONNECTION.model();}catch(RuntimeException invalid){return CompletableFuture.failedFuture(new IllegalArgumentException("ui.building.ai.failed"));}
        return CompletableFuture.supplyAsync(()->{
            try{
                if(key.isBlank())throw new IllegalArgumentException("ui.building.ai.needKey");
                JsonArray history=new JsonArray();
                history.add(AiProtocol.message("system","Design a complete Minecraft building. Return ONLY JSON: {\"name\":\"name\",\"size\":[width,height,depth],\"palette\":[{\"Name\":\"minecraft:air\"},{\"Name\":\"minecraft:stone_bricks\",\"Properties\":{}}],\"fills\":[{\"from\":[x,y,z],\"to\":[x,y,z],\"block\":1}]}. Coordinates are inclusive, nonnegative and inside size. Fills execute in order. Index zero MUST be air. Use legal vanilla block IDs and properties for the user's Minecraft version. Include architectural details, usable entrances, interiors and stairs. Use compact cuboid fills, never scripts or commands. For revisions return the COMPLETE updated plan, preserving unchanged parts. No arbitrary design-size cap; very large plans may exceed the user's memory. Do not include explanations."));
                if(previous!=null)history.add(AiProtocol.message("assistant",previous.toString()));
                history.add(AiProtocol.message("user","Minecraft "+net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString()+". "+requirement,images));
                var exchange=AiProtocol.exchange(HTTP,uri,model,key,history,new JsonArray(),current);
                String text=exchange.reply().get("content").getAsString().strip();
                if(text.startsWith("```")){int start=text.indexOf('\n'),end=text.lastIndexOf("```");if(start>=0&&end>start)text=text.substring(start+1,end).strip();}
                JsonObject plan=JsonParser.parseString(text).getAsJsonObject();return new Design(decode(plan,current),plan);
            }catch(AiProtocol.ProtocolException e){throw new CompletionException(new IllegalArgumentException(e.getMessage().equals(com.cheatprogram.core.Lang.t("ui.ai.images.unsupported"))?"ui.ai.images.unsupported":"ui.building.ai.failed"));}catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new CompletionException(new IllegalArgumentException("ui.building.ai.failed"));}
        },IO);
    }
    public static Blueprint decode(JsonObject plan){
        return decode(plan,()->true);
    }
    private static Blueprint decode(JsonObject plan,java.util.function.BooleanSupplier current){
        JsonArray size=plan.getAsJsonArray("size");if(size==null||size.size()!=3)throw invalid();
        int w=integer(size.get(0)),h=integer(size.get(1)),d=integer(size.get(2));
        if(w<1||h<1||d<1)throw invalid();
        long volume;try{volume=Math.multiplyExact(Math.multiplyExact((long)w,h),d);}catch(ArithmeticException e){throw invalid();}
        // A resource check, not a design-size preference. Reject before allocation.
        long available=Runtime.getRuntime().maxMemory()-(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());
        if(volume>Integer.MAX_VALUE-8||volume*16>available)throw new IllegalArgumentException("ui.building.ai.memory");
        JsonArray palette=plan.getAsJsonArray("palette");if(palette==null||palette.isEmpty())throw invalid();
        List<CompoundTag> states=new ArrayList<>();
        for(JsonElement element:palette){JsonObject state=element.getAsJsonObject();String id=state.get("Name").getAsString();if(!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(net.minecraft.resources.Identifier.parse(id)))throw invalid();
            CompoundTag tag=new CompoundTag();tag.putString("Name",id);
            if(state.has("Properties")){CompoundTag props=new CompoundTag();for(var e:state.getAsJsonObject("Properties").entrySet())props.putString(e.getKey(),e.getValue().getAsString());tag.put("Properties",props);}states.add(tag);}
        if(!palette.get(0).getAsJsonObject().get("Name").getAsString().equals("minecraft:air"))throw invalid();
        // Validate the entire plan before applying it; malformed revisions cannot replace a good preview.
        JsonArray fills=plan.getAsJsonArray("fills");if(fills==null)throw invalid();
        for(JsonElement element:fills){JsonObject fill=element.getAsJsonObject();JsonArray a=fill.getAsJsonArray("from"),b=fill.getAsJsonArray("to");int block=integer(fill.get("block"));if(a.size()!=3||b.size()!=3||block<0||block>=states.size())throw invalid();
            for(int i=0;i<3;i++){int low=integer(a.get(i)),high=integer(b.get(i)),limit=i==0?w:i==1?h:d;if(low<0||high<low||high>=limit)throw invalid();}}
        int[] blocks=new int[(int)volume];
        for(JsonElement element:fills){if(Thread.currentThread().isInterrupted()||!current.getAsBoolean())throw new CancellationException();JsonObject fill=element.getAsJsonObject();var a=fill.getAsJsonArray("from");var b=fill.getAsJsonArray("to");int block=integer(fill.get("block"));
            for(int y=integer(a.get(1));y<=integer(b.get(1));y++){if(!current.getAsBoolean())throw new CancellationException();for(int z=integer(a.get(2));z<=integer(b.get(2));z++)Arrays.fill(blocks,(y*d+z)*w+integer(a.get(0)),(y*d+z)*w+integer(b.get(0))+1,block);}}
        return new Blueprint(com.cheatprogram.util.BuildingFiles.cleanName(plan.has("name")?plan.get("name").getAsString():"AI building"),w,h,d,List.copyOf(states),blocks,Map.of());
    }
    private static int integer(JsonElement value){try{return value.getAsBigDecimal().intValueExact();}catch(Exception e){throw invalid();}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("ui.building.ai.invalid");}
}
