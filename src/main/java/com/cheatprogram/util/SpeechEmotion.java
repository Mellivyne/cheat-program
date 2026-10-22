package com.cheatprogram.util;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Explicit conversational metadata, never an instruction or a game action. */
public final class SpeechEmotion {
    public static final List<String> VALUES=List.of("neutral","happy","sad","angry","excited","fearful","calm");
    private static final Pattern PREFIX=Pattern.compile("^\\s*\\[emotion:([a-zA-Z_]+)(?::([0-9]+(?:\\.[0-9]+)?))?]\\s*");
    public record Utterance(String text,String emotion,double intensity) {}
    private SpeechEmotion(){}
    public static String normalize(String value){String emotion=value==null?"neutral":value.strip().toLowerCase(Locale.ROOT);return VALUES.contains(emotion)?emotion:"neutral";}
    public static double intensity(double value){return Double.isFinite(value)?Math.max(0,Math.min(1,value)):.6;}
    public static Utterance parse(String text,boolean enabled){
        String clean=text==null?"":text;
        var match=PREFIX.matcher(clean);
        if(!match.find())return new Utterance(clean,"neutral",.6);
        double strength=.6;
        try{if(match.group(2)!=null)strength=Double.parseDouble(match.group(2));}catch(NumberFormatException ignored){}
        return new Utterance(clean.substring(match.end()),enabled?normalize(match.group(1)):"neutral",enabled?intensity(strength):0);
    }
}
