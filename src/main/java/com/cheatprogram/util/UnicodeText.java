package com.cheatprogram.util;

import java.util.regex.Pattern;

/** Grapheme boundaries keep surrogate pairs, accents, flags and joined emoji intact during editing. */
public final class UnicodeText {
    private static final Pattern CLUSTER=Pattern.compile("\\X");
    private UnicodeText() { }
    public static String clip(String text,int maxChars) {
        if(text==null||maxChars<=0)return "";
        if(text.length()<=maxChars)return text;
        var matcher=CLUSTER.matcher(text);int end=0;
        while(matcher.find()&&matcher.end()<=maxChars)end=matcher.end();
        return text.substring(0,end);
    }
    public static int previous(String text,int caret) {
        int at=Math.max(0,Math.min(text.length(),caret));var matcher=CLUSTER.matcher(text);int last=0;
        while(matcher.find()){if(matcher.end()>=at)return matcher.start();last=matcher.end();}return last;
    }
    public static int next(String text,int caret) {
        int at=Math.max(0,Math.min(text.length(),caret));var matcher=CLUSTER.matcher(text);
        while(matcher.find())if(matcher.end()>at)return matcher.end();return text.length();
    }
    public static String displayName(String text,int maxClusters) {
        String clean=(text==null?"":text).replaceAll("[\\x00-\\x1f\\x7f]"," ");
        var matcher=CLUSTER.matcher(clean);int count=0,end=0;
        while(count<maxClusters&&matcher.find()){end=matcher.end();count++;}
        return clean.substring(0,end);
    }
}
