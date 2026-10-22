package com.cheatprogram.compat;
import net.minecraft.nbt.*;
/** Preserves legacy typed checks; the modern API's Optional default does not make wrong types valid. */
public final class NbtBridge {
    private NbtBridge(){}
    public static boolean contains(CompoundTag tag,String key,int type){Tag value=tag.get(key);return value!=null&&(type==99?value instanceof NumericTag:value.getId()==type);}
    public static ListTag getList(CompoundTag tag,String key,int type){ListTag value=tag.getListOrEmpty(key);for(Tag entry:value)if(entry.getId()!=type)return new ListTag();return value;}
}
