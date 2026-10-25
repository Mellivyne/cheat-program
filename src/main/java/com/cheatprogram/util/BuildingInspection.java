package com.cheatprogram.util;

import com.cheatprogram.util.BuildingFiles.Blueprint;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Immutable material counts and horizontal slices, indexed in the same order as saved buildings. */
public final class BuildingInspection {
    public record Material(String id,long count,CompoundTag state) { }
    private final Blueprint source;
    private final List<Material> total;
    private final Map<Integer,List<Material>> floors=new HashMap<>();
    public BuildingInspection(Blueprint source){this(source,()->true);}
    public BuildingInspection(Blueprint source,java.util.function.BooleanSupplier current){this.source=Objects.requireNonNull(source);total=count(-1,current);}
    public List<Material> materials(int floor){return floor<0?total:floors.computeIfAbsent(Math.max(0,Math.min(source.height()-1,floor)),this::count);}
    public Blueprint slice(int floor){
        floor=Math.max(0,Math.min(source.height()-1,floor));int area=Math.multiplyExact(source.width(),source.depth()),offset=Math.multiplyExact(floor,area);
        Map<Integer,CompoundTag> entities=new HashMap<>();
        for(var entry:source.blockEntities().entrySet())if(entry.getKey()>=offset&&entry.getKey()<offset+area)entities.put(entry.getKey()-offset,entry.getValue());
        return new Blueprint(source.name(),source.width(),1,source.depth(),source.palette(),Arrays.copyOfRange(source.blocks(),offset,offset+area),Map.copyOf(entities));
    }
    private List<Material> count(int floor){return count(floor,()->true);}
    private List<Material> count(int floor,java.util.function.BooleanSupplier current){
        Map<String,Long> counts=new HashMap<>();Map<String,CompoundTag> states=new HashMap<>();
        String[] names=new String[source.palette().size()];
        for(int i=0;i<names.length;i++){String id=String.valueOf(source.palette().get(i).get("Name")).replace("\"","");names[i]=id;states.putIfAbsent(id,source.palette().get(i));}
        int area=source.width()*source.depth(),from=floor<0?0:floor*area,to=floor<0?source.count():from+area;
        long[] paletteCounts=new long[names.length];
        for(int i=from;i<to;i++){if((i&65535)==0&&!current.getAsBoolean())throw new java.util.concurrent.CancellationException();paletteCounts[source.blocks()[i]]++;}
        for(int i=0;i<names.length;i++){String id=names[i];if(paletteCounts[i]!=0&&!id.equals("minecraft:air")&&!id.equals("minecraft:cave_air")&&!id.equals("minecraft:void_air"))counts.merge(id,paletteCounts[i],Long::sum);}
        return counts.entrySet().stream().map(e->new Material(e.getKey(),e.getValue(),states.get(e.getKey())))
                .sorted(Comparator.comparingLong(Material::count).reversed().thenComparing(Material::id)).toList();
    }
}
