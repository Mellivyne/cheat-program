package com.cheatprogram.dev;

import com.cheatprogram.media.player.ArtworkPreloadCursor;
import java.util.BitSet;

/** Standalone CPU checks: compile with ArtworkPreloadCursor; no game or network. */
public final class ArtworkPreloadCheck {
    public static void main(String[] args){
        for(int count:new int[]{0,1,17,128,2048,45000}){
            ArtworkPreloadCursor cursor=new ArtworkPreloadCursor();cursor.reset(count,count/2);
            BitSet seen=new BitSet();
            for(int step=0;step<count;step++){
                // Browsing changes every update; progress must survive and cover every index once.
                int focus=(int)((long)step*137%Math.max(1,count));
                int index=cursor.next(focus);
                require(index>=0&&index<count,"Index out of bounds");require(!seen.get(index),"Duplicate preload");seen.set(index);
            }
            require(seen.cardinality()==count,"Library not fully covered");
            require(cursor.remaining()==0&&cursor.next(0)==-1,"Completed library restarts every frame");
        }
        ArtworkPreloadCursor cursor=new ArtworkPreloadCursor();cursor.reset(1000,450);
        require(cursor.next(450)==450,"Browse center is not first");require(cursor.next(450)==451,"Forward neighbor not warmed");
        require(cursor.next(900)==900,"Changed browse position not prioritized");
        cursor.defer(900);require(cursor.next(900)==900,"Rejected queue entry was skipped");
        cursor.failed(450);cursor.failed(900);while(cursor.next(900)>=0){ }
        require(cursor.hasFailures(),"Failures lost");cursor.retryFailures();
        require(cursor.remaining()==2,"Successful tracks repeated during retry");
        require(cursor.next(900)==900&&cursor.next(900)==450&&cursor.next(900)==-1,"Failure retry set incorrect");
        cursor.reset(2,Integer.MAX_VALUE);require(cursor.next(Integer.MAX_VALUE)==1,"Large center not bounded");
        require(!cursor.hasFailures(),"Old-library failure state retained");cursor.reset(-4,0);require(cursor.next(0)==-1,"Negative library size");
        System.out.println("ARTWORK_PRELOAD_PASS full library up to 45000, moving focus, queue contention, bounded retries, reset");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
