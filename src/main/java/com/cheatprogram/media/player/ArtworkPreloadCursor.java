package com.cheatprogram.media.player;

import java.util.BitSet;

/** Visits a library once, favoring the latest browse position without losing prior progress. */
public final class ArtworkPreloadCursor {
    private final BitSet visited=new BitSet();
    private final BitSet failed=new BitSet();
    private int size,next,center=-1,remaining;

    public void reset(int count,int focus){
        size=Math.max(0,count);remaining=size;visited.clear();failed.clear();center=-1;next=bound(focus);
    }
    public int size(){return size;}
    public int remaining(){return remaining;}
    public boolean hasFailures(){return !failed.isEmpty();}
    public int next(int focus){
        if(remaining==0)return -1;
        int current=bound(focus);
        if(current!=center){center=current;next=current;}
        int index=visited.nextClearBit(next);
        if(index>=size)index=visited.nextClearBit(0);
        if(index>=size)return -1;
        visited.set(index);remaining--;next=index+1;
        return index;
    }
    /** Queue contention must not permanently skip a cover. */
    public void defer(int index){
        if(index>=0&&index<size&&visited.get(index)){visited.clear(index);failed.clear(index);remaining++;next=index;}
    }
    public void failed(int index){if(index>=0&&index<size&&visited.get(index))failed.set(index);}
    /** A delayed retry pass contains only failures, leaving successful covers alone. */
    public void retryFailures(){remaining+=failed.cardinality();visited.andNot(failed);failed.clear();next=0;center=-1;}
    private int bound(int value){return Math.max(0,Math.min(Math.max(0,size-1),value));}
}
