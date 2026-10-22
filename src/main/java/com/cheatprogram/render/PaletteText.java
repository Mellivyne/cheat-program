package com.cheatprogram.render;

import com.cheatprogram.util.MotionCurve;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared, font-aware spatial palettes for the list and text watermark. */
public final class PaletteText {
    public record Style(String palette, float phase) {
        public boolean colored() { return "palette_rainbow".equals(palette) || "palette_trans".equals(palette); }
    }
    private record LayoutKey(Fonts.Face face, int size, String text) { }
    private record Layout(float width, float[] centers) { }
    private static final Map<LayoutKey, Layout> LAYOUTS = new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<LayoutKey, Layout> entry) { return size() > 192; }
    };
    private static final int[] TRANS = {0x55CDFC, 0xF7A8B8, 0xFFFFFF, 0xF7A8B8};
    private static final int[] FLOW_RAINBOW = {0xFF4040, 0xFFFF40, 0x40FF40, 0x40FFFF, 0x4040FF, 0xFF40FF};
    private static long fontRevision = -1;
    private PaletteText() { }

    /** The quintic curve slows at the seam and accelerates through the middle; the period stays exact. */
    public static float phase(double seconds, double period, double nonlinearStrength, boolean reverse, double offset) {
        double cycles = Math.max(0, seconds) / Math.max(.05, period);
        float linear = (float) (cycles - Math.floor(cycles));
        float strength = clamp((float) nonlinearStrength);
        float progress = linear + (MotionCurve.smootherStep(linear) - linear) * strength;
        return wrap((reverse ? -progress : progress) + (float) offset);
    }

    /** Periodic speed modulation, with the same positive velocity on both sides of every loop seam. */
    public static float flowingPhase(double seconds, double period, double nonlinearStrength, boolean reverse, double offset) {
        double cycles = Math.max(0, seconds) / Math.max(.05, period);
        double t = cycles - Math.floor(cycles);
        double amplitude = .65 * Math.max(0, Math.min(1, nonlinearStrength));
        double progress = t - amplitude * Math.sin(t * Math.PI * 2) / (Math.PI * 2);
        return wrap((float) ((reverse ? -progress : progress) + offset));
    }

    /** The moving list uses a continuous color field, including inside each glyph. */
    private static int flowingRgb(Style style, float position) {
        if ("palette_trans".equals(style.palette)) {
            float coordinate = position * 4 + style.phase * 4;
            int band = (int) Math.floor(coordinate);
            float blend = MotionCurve.smootherStep(coordinate - band);
            return mix(TRANS[Math.floorMod(band, 4)], TRANS[Math.floorMod(band + 1, 4)], blend);
        }
        if ("palette_rainbow".equals(style.palette)) {
            float coordinate = wrap(position + style.phase + .015f) * 6;
            int band = (int) Math.floor(coordinate);
            return mix(FLOW_RAINBOW[band % 6], FLOW_RAINBOW[(band + 1) % 6], MotionCurve.smootherStep(coordinate - band));
        }
        return rgb(style, position);
    }

    /** A circular sweep has no off-screen interval between the old tail and the new head. */
    public static float flowingClarity(float position, float phase, float bandWidth) {
        float distance = Math.abs(wrap(position) - wrap(phase));
        distance = Math.min(distance, 1 - distance);
        return 1 - MotionCurve.smootherStep(clamp(distance / Math.max(.01f, bandWidth * .5f)));
    }

    public static float drawFlowing(Canvas canvas, Fonts.Face face, String text, float x, float cy, float size,
                                    int color, Style style, float gradientX, float gradientWidth, float brightness, float blur) {
        float pixels = size * canvas.scale;
        float baseline = canvas.py(cy) + Fonts.pixelSize(pixels) * .36f;
        float origin = canvas.px(x), gradientOrigin = canvas.px(gradientX);
        float span = Math.max(1, gradientWidth * canvas.scale), light = clamp(brightness);
        java.util.function.DoubleToIntFunction field = coordinate -> scale(flowingRgb(style, (float) ((coordinate - gradientOrigin) / span)), light);
        int alpha = color >>> 24;
        float radius = Math.min(2.5f, blur) * canvas.scale;
        float blend = MotionCurve.smootherStep(clamp((radius / canvas.scale - .35f) / .85f));
        if (radius > 0 && blend > 0) {
            int outer = Math.round(alpha * .15f * blend);
            R2D.textSpatialColors(face, text, origin - radius, baseline, pixels, outer, field);
            R2D.textSpatialColors(face, text, origin + radius, baseline, pixels, outer, field);
            R2D.textSpatialColors(face, text, origin, baseline - radius, pixels, outer, field);
            R2D.textSpatialColors(face, text, origin, baseline + radius, pixels, outer, field);
        }
        return R2D.textSpatialColors(face, text, origin, baseline, pixels, Math.round(alpha * (1 - .6f * blend)), field) / canvas.scale;
    }

    /** Trans colors have five equal nominal bands B/P/W/P/B, with soft joins and a four-step looping phase. */
    public static int rgb(Style style, float position) {
        if ("palette_trans".equals(style.palette)) {
            float coordinate = Math.max(0, Math.min(4, clamp(position) * 5 - .5f)) + wrap(style.phase) * 4;
            int band = (int) Math.floor(coordinate);
            float blend = MotionCurve.smootherStep(clamp((coordinate - band - .35f) / .3f));
            return mix(TRANS[Math.floorMod(band, 4)], TRANS[Math.floorMod(band + 1, 4)], blend);
        }
        if ("palette_rainbow".equals(style.palette)) return rainbow(wrap(position + style.phase + .015f));
        return 0xFFFFFF;
    }

    public static float draw(Canvas canvas, Fonts.Face face, String text, float x, float cy, float size,
                             int color, Style style, float gradientX, float gradientWidth, float brightness, float blur) {
        float pixels = size * canvas.scale;
        float baseline = canvas.py(cy) + Fonts.pixelSize(pixels) * .36f;
        float width = drawPixels(face, text, canvas.px(x), baseline, pixels, color, style,
                canvas.px(gradientX), gradientWidth * canvas.scale, brightness, Math.min(2.5f, blur) * canvas.scale);
        return width / canvas.scale;
    }

    /** Returns the same advance as ordinary R2D text; color and blur cannot change layout. */
    public static float drawPixels(Fonts.Face face, String text, float x, float baseline, float size,
                                   int color, Style style, float gradientX, float gradientWidth,
                                   float brightness, float blur) {
        if (text == null || text.isEmpty()) return 0;
        Layout layout = layout(face, text, size);
        int[] colors = new int[layout.centers.length];
        float light = clamp(brightness);
        for (int i = 0; i < colors.length; i++) {
            int rgb = rgb(style, (x + layout.centers[i] - gradientX) / Math.max(1, gradientWidth));
            colors[i] = scale(rgb, light);
        }
        int alpha = color >>> 24;
        float blend = MotionCurve.smootherStep(clamp((blur - .35f) / .85f));
        if (blur > 0 && blend > 0) {
            float radius = blur;
            int outer = Math.round(alpha * .15f * blend);
            R2D.textColors(face, text, x - radius, baseline, size, outer, i -> colors[i]);
            R2D.textColors(face, text, x + radius, baseline, size, outer, i -> colors[i]);
            R2D.textColors(face, text, x, baseline - radius, size, outer, i -> colors[i]);
            R2D.textColors(face, text, x, baseline + radius, size, outer, i -> colors[i]);
        }
        R2D.textColors(face, text, x, baseline, size, Math.round(alpha * (1 - .6f * blend)), i -> colors[i]);
        return layout.width;
    }

    private static Layout layout(Fonts.Face face, String text, float size) {
        long revision = Fonts.revision();
        if (revision != fontRevision) { LAYOUTS.clear(); fontRevision = revision; }
        LayoutKey key = new LayoutKey(face, Fonts.pixelSize(size), text);
        return LAYOUTS.computeIfAbsent(key, ignored -> {
            float[] centers = new float[text.codePointCount(0, text.length())];
            float advance = 0;
            for (int i = 0, ordinal = 0; i < text.length(); ordinal++) {
                int cp = text.codePointAt(i);
                String glyph = new String(Character.toChars(cp));
                float width = R2D.textWidth(face, glyph, size);
                centers[ordinal] = advance + width * .5f;
                advance += width;
                i += Character.charCount(cp);
            }
            return new Layout(R2D.textWidth(face, text, size), centers);
        });
    }
    private static int scale(int rgb, float brightness) {
        return Math.round(((rgb >> 16) & 255) * brightness) << 16
                | Math.round(((rgb >> 8) & 255) * brightness) << 8 | Math.round((rgb & 255) * brightness);
    }
    private static int rainbow(float hue) {
        float h = hue * 6, fraction = h - (float) Math.floor(h), low = .25f;
        float rising = low + .75f * fraction, falling = 1 - .75f * fraction;
        float r, g, b;
        switch ((int) h % 6) {
            case 0 -> { r = 1; g = rising; b = low; }
            case 1 -> { r = falling; g = 1; b = low; }
            case 2 -> { r = low; g = 1; b = rising; }
            case 3 -> { r = low; g = falling; b = 1; }
            case 4 -> { r = rising; g = low; b = 1; }
            default -> { r = 1; g = low; b = falling; }
        }
        return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }
    private static int mix(int first, int second, float progress) {
        int r = Math.round(((first >> 16) & 255) + (((second >> 16) & 255) - ((first >> 16) & 255)) * progress);
        int g = Math.round(((first >> 8) & 255) + (((second >> 8) & 255) - ((first >> 8) & 255)) * progress);
        int b = Math.round((first & 255) + ((second & 255) - (first & 255)) * progress);
        return r << 16 | g << 8 | b;
    }
    private static float clamp(float value) { return Math.max(0, Math.min(1, value)); }
    private static float wrap(float value) { return value - (float) Math.floor(value); }
}
