package com.cheatprogram.media;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic Unicode typewriter reveal. Elapsed time belongs to the caller's playback clock. */
public final class TypewriterText {
    public record Frame(String text, boolean complete, int visibleUnits, int totalUnits) { }
    private static final Pattern GRAPHEME = Pattern.compile("\\X");
    private static final int CACHE_LIMIT = 48, MAX_CACHED_LENGTH = 16_384;
    private static final Map<String, int[]> BOUNDARIES = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, int[]> entry) { return size() > CACHE_LIMIT; }
    };
    private TypewriterText() { }

    /**
     * One unit is an extended grapheme cluster, including a whole combined emoji or accented letter.
     * Zero/negative/NaN time reveals nothing. Zero/negative/NaN speed is stopped. Positive infinity
     * finishes immediately when both time and speed are positive. No state is accumulated on pause/seek.
     */
    public static Frame frame(String source, double elapsed, double charsPerSecond) {
        String text = source == null ? "" : source;
        int[] ends = boundaries(text);
        int total = ends.length;
        if (total == 0) return new Frame("", true, 0, 0);
        int visible = 0;
        if (elapsed > 0 && charsPerSecond > 0) {
            double amount = elapsed * charsPerSecond;
            visible = amount >= total ? total : Math.max(0, (int) Math.floor(amount));
        }
        return new Frame(visible == total ? text : visible == 0 ? "" : text.substring(0, ends[visible - 1]),
                visible == total, visible, total);
    }

    private static int[] boundaries(String text) {
        if (text.length() <= MAX_CACHED_LENGTH) {
            synchronized (BOUNDARIES) {
                int[] cached = BOUNDARIES.get(text);
                if (cached != null) return cached;
            }
        }
        Matcher matcher = GRAPHEME.matcher(text);
        ArrayList<Integer> ends = new ArrayList<>();
        while (matcher.find()) ends.add(matcher.end());
        int[] result = new int[ends.size()];
        for (int i = 0; i < result.length; i++) result[i] = ends.get(i);
        if (text.length() <= MAX_CACHED_LENGTH) synchronized (BOUNDARIES) { BOUNDARIES.put(text, result); }
        return result;
    }
}
