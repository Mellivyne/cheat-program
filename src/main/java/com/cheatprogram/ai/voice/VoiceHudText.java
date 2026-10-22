package com.cheatprogram.ai.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Display copies only. Tool calls, command arguments, user intent and conversation history never pass here. */
public final class VoiceHudText {
    public static final int MAX_CODEPOINTS=240,MAX_LINES=5;
    private VoiceHudText() { }
    public static String display(String value) {
        if(value==null)return "";
        String clean=value.replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]","").replace('\t',' ').strip();
        if(clean.codePointCount(0,clean.length())<=MAX_CODEPOINTS)return clean;
        return clean.substring(0,clean.offsetByCodePoints(0,MAX_CODEPOINTS-1)).stripTrailing()+"…";
    }
    public static List<String> wrap(String value,double width,int maximum,ToDoubleFunction<String> measure) {
        String text=display(value);int limit=Math.max(1,Math.min(MAX_LINES,maximum));
        double available=Double.isFinite(width)?Math.max(1,width):1;
        List<String> lines=new ArrayList<>();StringBuilder line=new StringBuilder();int offset=0;
        while(offset<text.length()&&lines.size()<limit) {
            int cp=text.codePointAt(offset);int count=Character.charCount(cp);
            if(cp=='\n'){lines.add(line.toString());line.setLength(0);offset+=count;continue;}
            String next=line.toString()+new String(Character.toChars(cp));
            if(line.isEmpty()&&measure.applyAsDouble(next)>available)return lines.isEmpty()?List.of(measure.applyAsDouble("…")<=available?"…":""):List.copyOf(lines);
            if(!line.isEmpty()&&measure.applyAsDouble(next)>available){lines.add(line.toString().stripTrailing());line.setLength(0);continue;}
            if(!line.isEmpty()||!Character.isWhitespace(cp))line.appendCodePoint(cp);
            offset+=count;
        }
        if(!line.isEmpty()&&lines.size()<limit)lines.add(line.toString().stripTrailing());
        if(offset<text.length()&&!lines.isEmpty()) {
            String last=lines.get(lines.size()-1);
            while(!last.isEmpty()&&measure.applyAsDouble(last+"…")>available)last=last.substring(0,last.offsetByCodePoints(last.length(),-1));
            lines.set(lines.size()-1,last+"…");
        }
        return List.copyOf(lines);
    }
}
