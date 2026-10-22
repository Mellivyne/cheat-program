package com.cheatprogram.media;

import java.util.regex.Pattern;

/** Unicode boundaries and conservative native lyric-segment classification. */
public final class LyricText {
    private static final Pattern GRAPHEME=Pattern.compile("\\X");
    private LyricText() { }
    public static int boundary(String text,int end) {
        if(text==null||end<=0)return 0;if(end>=text.length())return text.length();
        var matcher=GRAPHEME.matcher(text);int safe=0;
        while(matcher.find()&&matcher.end()<=end)safe=matcher.end();
        return safe;
    }
    public static String prefix(String text,int end) { return text.substring(0,boundary(text,end)); }
    /** A native word can be one western word or one CJK syllable/grapheme, never a whole phrase. */
    public static boolean singleUnit(String text) {
        if(text==null||text.isBlank())return false;
        var matcher=GRAPHEME.matcher(text);int units=0;boolean joinedWord=false;
        while(matcher.find()) {
            String cluster=matcher.group();int cp=cluster.codePointAt(0);
            if(Character.isWhitespace(cp)) { joinedWord=false;continue; }
            int type=Character.getType(cp);
            if(punctuation(type)){
                // Apostrophes/hyphens can join one native word; commas and slashes cannot.
                if(cp!='\''&&cp!=0x2019&&cp!='-'&&cp!=0x2010&&cp!=0x2011)joinedWord=false;
                continue;
            }
            var script=Character.UnicodeScript.of(cp);
            boolean word=Character.isLetterOrDigit(cp)&&script!=Character.UnicodeScript.HAN&&script!=Character.UnicodeScript.HIRAGANA
                    &&script!=Character.UnicodeScript.KATAKANA&&script!=Character.UnicodeScript.HANGUL;
            if(!word||!joinedWord)units++;
            if(units>1)return false;joinedWord=word;
        }
        return units==1;
    }
    public static boolean untimedGap(String text){
        return text.codePoints().allMatch(cp->Character.isWhitespace(cp)||punctuation(Character.getType(cp)));
    }
    private static boolean punctuation(int type){return type==Character.CONNECTOR_PUNCTUATION||type==Character.DASH_PUNCTUATION||type==Character.START_PUNCTUATION
            ||type==Character.END_PUNCTUATION||type==Character.INITIAL_QUOTE_PUNCTUATION||type==Character.FINAL_QUOTE_PUNCTUATION||type==Character.OTHER_PUNCTUATION;}
}
