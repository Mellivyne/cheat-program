package com.cheatprogram.media;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Current-line sampling. Confirmed sung text accumulates without exposing future words. */
public final class SungLyrics {
    public record Cue(int index,String visible) { }
    public record Row(String visible,String layout) { }
    public record PresentedRow(String text,String layout,float progress,float futureOpacity) { }
    private final Map<Integer,String> observed=new LinkedHashMap<>();
    private int previous=-1;
    private double previousSeconds;
    private long previousNanos;
    private long playbackRevision=Long.MIN_VALUE;
    private LrcLyrics timeline;
    private long version;
    public long version(){return version;}
    public void clear(){observed.clear();previous=-1;previousNanos=0;timeline=null;version++;}
    public String observed(int index){return observed.getOrDefault(index,"");}
    public List<Integer> history(){return List.copyOf(observed.keySet());}
    /** Decoder seeks and track changes are explicit discontinuities, including very short seeks. */
    public Cue sample(LrcLyrics lyrics,double seconds,double duration,boolean playing,long now,long revision){
        if(playbackRevision!=revision){clear();playbackRevision=revision;}
        return sample(lyrics,seconds,duration,playing,now);
    }
    public Cue sample(LrcLyrics lyrics,double seconds,double duration,boolean playing,long now){
        if(timeline!=lyrics){clear();timeline=lyrics;}
        double elapsed=previousNanos==0?0:Math.max(0,(now-previousNanos)/1e9),delta=seconds-previousSeconds;
        if(previousNanos!=0&&(delta<-.05||delta>elapsed+.15)){clear();timeline=lyrics;}
        int index=lyrics.lineAt(seconds,duration);
        previous=index;previousSeconds=seconds;previousNanos=now;
        if(index<0)return new Cue(-1,"");
        return new Cue(index,lyrics.visiblePrefix(index,seconds,duration));
    }
    private void remember(int index,String prefix){if(prefix.isBlank())return;observed.put(index,prefix);while(observed.size()>32)observed.remove(observed.keySet().iterator().next());}
    /** Translations are sentence-aligned to the visible original, never sampled as an independent song. */
    public static String translationFor(LrcLyrics translated,LrcLyrics.Line original,String visible,double seconds){
        return translationFor(translated,original,visible,seconds,true);
    }
    public static String translationFor(LrcLyrics translated,LrcLyrics.Line original,String visible,double seconds,boolean preview){
        var line=translationLine(translated,original,visible,seconds);
        return line==null?"":preview?line.text():line.visiblePrefix(seconds);
    }
    /** Matching text and its complete layout share one native translated sentence. */
    public static LrcLyrics.Line translationLine(LrcLyrics translated,LrcLyrics.Line original,String visible,double seconds){
        if(translated==null||original==null||visible==null||visible.isBlank()||seconds<original.start()||!translated.synced())return null;
        var lines=translated.lines();int lo=0,hi=lines.size();
        while(lo<hi){int mid=(lo+hi)>>>1;if(lines.get(mid).start()<original.start())lo=mid+1;else hi=mid;}
        int closest=-1;double gap=.350001;
        for(int i=Math.max(0,lo-2);i<Math.min(lines.size(),lo+2);i++){if(lines.get(i).text().isBlank())continue;double d=Math.abs(lines.get(i).start()-original.start());if(d<gap){gap=d;closest=i;}}
        // Native word and line translations can have slightly different sentence boundaries.
        if(closest<0){double best=0;for(int i=Math.max(0,lo-2);i<Math.min(lines.size(),lo+2);i++){var candidate=lines.get(i);double d=Math.abs(candidate.start()-original.start()),overlap=Math.min(candidate.end(),original.end())-Math.max(candidate.start(),original.start());
            double fraction=overlap/Math.max(.001,Math.min(candidate.end()-candidate.start(),original.end()-original.start()));
            if(!candidate.text().isBlank()&&d<=1.25&&overlap>0&&fraction>=.5&&fraction>best){best=fraction;closest=i;}}}
        if(closest<0)return null;
        var line=lines.get(closest);
        return seconds<line.start()||seconds>=line.end()?null:line;
    }
    /** Chunks retain complete measurement text; only the observed prefix enters any glyph path. */
    public static List<Row> rows(String visible,List<String> chunks){
        List<Row> result=new ArrayList<>();int at=0;
        for(String chunk:chunks){int count=Math.max(0,Math.min(chunk.length(),visible.length()-at));result.add(new Row(count==0?"":chunk.substring(0,count),chunk));at+=chunk.length();}
        return List.copyOf(result);
    }
    /** Preview is an explicit display preference; it never changes the observed sung history. */
    public static List<PresentedRow> present(String visible,List<String> chunks,boolean preview,float opacity){
        float dim=Float.isFinite(opacity)?Math.max(0,Math.min(1,opacity)):.25f;
        List<PresentedRow> result=new ArrayList<>();
        for(Row row:rows(visible,chunks))result.add(new PresentedRow(preview?row.layout():row.visible(),row.layout(),
                preview?(row.layout().isEmpty()?0:row.visible().length()/(float)row.layout().length()):-1,dim));
        return List.copyOf(result);
    }
    public static List<Integer> upcoming(LrcLyrics lyrics,int shown,int requested){
        if(lyrics==null||shown<0||shown>=lyrics.lines().size())return List.of();
        List<Integer> result=new ArrayList<>();int limit=Math.min(3,Math.max(0,requested));
        for(int i=shown+1;i<lyrics.lines().size()&&result.size()<limit;i++)if(!lyrics.lines().get(i).text().isBlank())result.add(i);
        return List.copyOf(result);
    }
}
