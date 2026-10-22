package com.cheatprogram.render;

import com.cheatprogram.media.LyricText;

import com.cheatprogram.compat.gl.GlStateManager;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.cheatprogram.compat.gl.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import com.cheatprogram.compat.gl.CoreShaders;
import com.cheatprogram.compat.gl.FogParameters;
import com.cheatprogram.compat.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Transparent world-space glyphs. No camera-facing HUD projection or opaque backing plane. */
public final class WorldSubtitleRenderer {
    public record Motion(String mode,double seconds,float amount,float speed) {
        public static final Motion NONE=new Motion("lyrics_none",0,0,0);
    }
    /** Scale/rise control the visible prefix; layoutText keeps the full line's width while typing. */
    public record Line(String text, float alpha, float scale, float rise, float progress, String layoutText,
                       float shift,float rotation,Motion motion,float futureOpacity) {
        public Line {
            text = text == null ? "" : text;
            layoutText = layoutText == null || layoutText.isEmpty() ? text : layoutText;
            motion=motion==null?Motion.NONE:motion;
            futureOpacity=bounded(futureOpacity,0,1,.25f);
        }
        public Line(String text,float alpha,float scale,float rise,float progress,String layoutText,float shift,float rotation,Motion motion){
            this(text,alpha,scale,rise,progress,layoutText,shift,rotation,motion,.25f);
        }
        public Line(String text,float alpha,float scale,float rise,float progress,String layoutText){
            this(text,alpha,scale,rise,progress,layoutText,0,0,Motion.NONE);
        }
        public Line(String text, float alpha, float scale, float rise, float progress) {
            this(text, alpha, scale, rise, progress, text);
        }
    }
    private record Layout(String text, Fonts.Glyph[] glyphs, float width) { }
    private record Row(Line line, Layout layout, float unit, float height, float width,int sungGlyphs) { }
    private static final Minecraft MC = Minecraft.getInstance();
    private static final int PIXELS = 64, MAX_CODEPOINTS = 512, MAX_LINES = 12;
    private static final Fonts.Face FACE = Fonts.Face.MEDIUM;
    private static final Map<String, Layout> LAYOUTS = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Layout> entry) { return size() > 48; }
    };
    private static long fontRevision = -1;
    private static ByteBufferBuilder vanillaMemory;

    private WorldSubtitleRenderer() { }

    /**
     * Sizes and offsets use blocks. Rotation follows AppCastPlane: yaw around -Y, then pitch X and roll Z.
     * A line shrinks to maxWidth instead of truncating its Unicode text. Each call draws at most 512 code points.
     */
    public static void draw(Render3D renderer,Vec3 center,float yaw,float pitch,float roll,List<Line> lines,
                            float letterHeight,float maxWidth,float lineGap,boolean throughWalls,boolean vanillaFont,
                            float glow,PaletteText.Style palette){
        draw(renderer,center,yaw,pitch,roll,lines,letterHeight,maxWidth,lineGap,throughWalls,vanillaFont,glow,2.2f,palette);
    }
    public static void draw(Render3D renderer, Vec3 center, float yaw, float pitch, float roll,
                            List<Line> lines, float letterHeight, float maxWidth, float lineGap,
                            boolean throughWalls, boolean vanillaFont, float glow,float glowRadius, PaletteText.Style palette) {
        if (renderer == null || center == null || lines == null || lines.isEmpty() || MC.level == null
                || !Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(center.z)
                || renderer.camera.distanceToSqr(center) > 512 * 512) return;
        float height = bounded(letterHeight, .025f, 16, .45f), limit = bounded(maxWidth, .05f, 512, 12);
        float gap = bounded(lineGap, 0, 16, .12f), halo = bounded(glow, 0, 1, 0),radius=bounded(glowRadius,0,10,2.2f);
        PaletteText.Style colors = palette == null ? new PaletteText.Style("palette_white", 0) : palette;
        RenderStateSnapshot saved = new RenderStateSnapshot();
        int unpackAlignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int unpackRow = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int unpackPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        int unpackRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        try {
            long revision = Fonts.revision();
            if (fontRevision != revision) { LAYOUTS.clear(); fontRevision = revision; }
            boolean vanilla = vanillaFont || Fonts.useVanilla();
            List<Row> rows = rows(lines, height, limit, vanilla);
            if (rows.isEmpty()) return;
            if (!vanilla && !prepareAtlas(rows)) { vanilla = true; rows = rows(lines, height, limit, true); }
            Matrix4f plane = new Matrix4f().translation((float) (center.x - renderer.camera.x),
                            (float) (center.y - renderer.camera.y), (float) (center.z - renderer.camera.z))
                    .rotateY((float) Math.toRadians(-finite(yaw, 0)))
                    .rotateX((float) Math.toRadians(finite(pitch, 0)))
                    .rotateZ((float) Math.toRadians(finite(roll, 0)));
            RenderSystem.getModelViewStack().set(renderer.viewMatrix());
            RenderSystem.setShaderFog(FogParameters.NO_FOG);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull(); RenderSystem.depthMask(false);
            if (throughWalls) RenderSystem.disableDepthTest(); else RenderSystem.enableDepthTest();
            float totalHeight = Math.max(0, rows.size() - 1) * gap;
            for (Row row : rows) totalHeight += row.height;
            float top = totalHeight * .5f;
            for (Row row : rows) {
                float cy = top - row.height * .5f + bounded(row.line.rise, -32, 32, 0);
                Matrix4f pose = new Matrix4f(plane).translate(bounded(row.line.shift,-16,16,0), cy, 0)
                        .rotateZ((float)Math.toRadians(bounded(row.line.rotation,-90,90,0))).scale(row.unit, -row.unit, row.unit);
                if (vanilla) drawVanilla(row, pose, throughWalls, halo,radius, colors);
                else drawAtlas(row, pose, throughWalls, halo,radius, colors);
                top -= row.height + gap;
            }
        } finally {
            GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, unpackAlignment);
            GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, unpackRow);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, unpackPixels);
            GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, unpackRows);
            saved.restore();
        }
    }

    /** Release optional vanilla staging memory when the client stops; subsequent drawing recreates it. */
    public static void release() {
        LAYOUTS.clear();
        if (vanillaMemory != null) { vanillaMemory.close(); vanillaMemory = null; }
    }

    private static List<Row> rows(List<Line> lines, float height, float maxWidth, boolean vanilla) {
        List<Row> result = new ArrayList<>();
        int left = MAX_CODEPOINTS;
        for (Line line : lines) {
            if (line == null || line.text == null || left <= 0 || result.size() >= MAX_LINES) continue;
            int available=left;
            String text = clean(line.text, left), fullText = clean(line.layoutText, left);
            left -= Math.max(text.codePointCount(0, text.length()), fullText.codePointCount(0, fullText.length()));
            if (text.isBlank() && fullText.isBlank() || bounded(line.alpha, 0, 1, 0) <= 0) continue;
            Layout layout = vanilla ? new Layout(text, null, MC.font.width(Component.literal(text)) * (PIXELS / 9f))
                    : LAYOUTS.computeIfAbsent(text, WorldSubtitleRenderer::layout);
            float fullWidth = text.equals(fullText) ? layout.width : vanilla
                    ? MC.font.width(Component.literal(fullText)) * (PIXELS / 9f)
                    : LAYOUTS.computeIfAbsent(fullText, WorldSubtitleRenderer::layout).width;
            float width = Math.max(layout.width, fullWidth);
            float unit = height * bounded(line.scale, .05f, 8, 1) / PIXELS;
            if (width * unit > maxWidth) unit = maxWidth / Math.max(.001f, width);
            int sungGlyphs=text.codePointCount(0,text.length());
            if(line.progress>=0){int count=Math.max(0,Math.min(line.text.length(),Math.round(line.text.length()*bounded(line.progress,0,1,0))));String prefix=clean(LyricText.prefix(line.text,count),available);
                sungGlyphs=Math.min(sungGlyphs,prefix.codePointCount(0,prefix.length()));}
            result.add(new Row(line, layout, unit, unit * PIXELS, width,sungGlyphs));
        }
        return result;
    }

    private static String clean(String source, int limit) {
        StringBuilder text = new StringBuilder();
        for (int offset = 0, count = 0; offset < source.length() && count < limit; count++) {
            int cp = source.codePointAt(offset); offset += Character.charCount(cp);
            if (Character.isISOControl(cp)) { if (Character.isWhitespace(cp)) text.append(' '); }
            else text.appendCodePoint(cp);
        }
        return text.toString();
    }

    private static Layout layout(String text) {
        Fonts.Glyph[] glyphs = new Fonts.Glyph[text.codePointCount(0, text.length())];
        float width = 0;
        for (int offset = 0, ordinal = 0; offset < text.length(); ordinal++) {
            int cp = text.codePointAt(offset); offset += Character.charCount(cp);
            Fonts.Glyph glyph = Fonts.glyph(FACE, PIXELS, cp); glyphs[ordinal] = glyph; width += glyph.advance;
        }
        return new Layout(text, glyphs, width);
    }

    /** Resolve before beginning any mesh: atlas rollover must never invalidate a half-built batch. */
    private static boolean prepareAtlas(List<Row> rows) {
        for (int attempt = 0; attempt < 3; attempt++) {
            for (Row row : rows) for (Fonts.Glyph glyph : row.layout.glyphs) if (glyph.vanilla == null) Fonts.region(glyph);
            boolean stable = true;
            for (Row row : rows) for (Fonts.Glyph glyph : row.layout.glyphs)
                if (glyph.bitmap != null && glyph.generation != Fonts.ATLAS.generation) stable = false;
            if (stable) return true;
        }
        return false;
    }

    private static void drawAtlas(Row row, Matrix4f pose, boolean throughWalls, float glow,float radius, PaletteText.Style palette) {
        float alpha = bounded(row.line.alpha, 0, 1, 0);
        int passes = glow > .001f&&radius>.001f ? 9 : 1;
        for (int pass = passes - 1; pass >= 0; pass--) {
            float dx = 0, dy = 0, strength = alpha;
            if (pass > 0) {
                double angle=(pass-1)*Math.PI/4;dx=(float)Math.cos(angle)*radius;dy=(float)Math.sin(angle)*radius;
                strength *= glow * .22f;
            }
            BufferBuilder buffer = null;
            float pen = -row.width * .5f;int ordinal=0;
            for (Fonts.Glyph glyph : row.layout.glyphs) {
                float glyphStrength=visibility(row,ordinal,strength);
                Matrix4f glyphPose=glyphPose(pose,row,pen,glyph.advance,ordinal++);
                if (glyph.vanilla != null) {
                    if (buffer != null) { flushAtlas(buffer); buffer = null; }
                    drawNative(glyph.vanilla, new Matrix4f(glyphPose).translate(pen + dx, PIXELS * .36f - 7 * PIXELS / 9f + dy, .001f)
                                    .scale(PIXELS / 9f, PIXELS / 9f, 1), row, throughWalls, glyphStrength,
                            pen + dx, PIXELS / 9f, palette);
                } else {
                    Atlas.Region region = Fonts.region(glyph);
                    if (region != null) {
                        if (buffer == null) buffer = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                        float x = pen + glyph.offsetX + dx, y = PIXELS * .36f + glyph.offsetY + dy;
                        int strips = Math.max(1, (int) Math.ceil(region.w() / 4f));
                        for (int strip = 0; strip < strips; strip++) {
                            float a = (float) strip / strips, b = (float) (strip + 1) / strips;
                            float x0 = x + region.w() * a, x1 = x + region.w() * b;
                            float u0 = region.u0() + (region.u1() - region.u0()) * a;
                            float u1 = region.u0() + (region.u1() - region.u0()) * b;
                            int c0 = color(row, x0, glyphStrength, palette), c1 = color(row, x1, glyphStrength, palette);
                            buffer.addVertex(glyphPose, x0, y, 0).setUv(u0, region.v0()).setColor(c0);
                            buffer.addVertex(glyphPose, x0, y + region.h(), 0).setUv(u0, region.v1()).setColor(c0);
                            buffer.addVertex(glyphPose, x1, y + region.h(), 0).setUv(u1, region.v1()).setColor(c1);
                            buffer.addVertex(glyphPose, x1, y, 0).setUv(u1, region.v0()).setColor(c1);
                        }
                    }
                }
                pen += glyph.advance;
            }
            if (buffer != null) flushAtlas(buffer);
        }
    }

    private static void flushAtlas(BufferBuilder buffer) {
        RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
        RenderSystem.setShaderTexture(0, Fonts.ATLAS.id());
        BufferUploader.drawWithShader(buffer.buildOrThrow());
    }

    private static Matrix4f glyphPose(Matrix4f pose,Row row,float pen,float advance,int ordinal){
        Motion m=row.line.motion;if(!SubtitleMotion.glyphsMove(m.mode))return pose;
        var glyph=SubtitleMotion.glyph(m.mode,m.seconds,ordinal,m.amount,m.speed);float center=pen+advance*.5f;
        return new Matrix4f(pose).translate(center+glyph.x()*PIXELS,glyph.y()*PIXELS,0)
                .rotateZ((float)Math.toRadians(glyph.rotation())).translate(-center,0,0);
    }

    private static void drawVanilla(Row row, Matrix4f pose, boolean throughWalls, float glow,float radius, PaletteText.Style palette) {
        int passes = glow > .001f&&radius>.001f ? 9 : 1;
        float alpha = bounded(row.line.alpha, 0, 1, 0), factor = PIXELS / 9f;
        for (int pass = passes - 1; pass >= 0; pass--) {
            float dx = 0, dy = 0, strength = alpha;
            if (pass > 0) {
                double angle=(pass-1)*Math.PI/4;dx=(float)Math.cos(angle)*radius;dy=(float)Math.sin(angle)*radius;
                strength *= glow * .22f;
            }
            if(SubtitleMotion.glyphsMove(row.line.motion.mode)){
                MultiBufferSource.BufferSource buffers=nativeBuffers();
                float pen=-row.width*.5f;int ordinal=0;
                for(int at=0;at<row.layout.text.length();){int cp=row.layout.text.codePointAt(at);String glyph=new String(Character.toChars(cp));at+=Character.charCount(cp);
                    float advance=MC.font.width(Component.literal(glyph))*factor;
                    float glyphStrength=visibility(row,ordinal,strength);
                    Matrix4f nativePose=new Matrix4f(glyphPose(pose,row,pen,advance,ordinal++)).translate(pen+dx,PIXELS*.36f-7*factor+dy,0).scale(factor,factor,1);
                    drawNative(glyph,nativePose,row,throughWalls,glyphStrength,pen+dx,factor,palette,buffers);pen+=advance;
                }
                buffers.endBatch();restoreNativeState(throughWalls);
            }else{
                Matrix4f nativePose = new Matrix4f(pose).translate(-row.width * .5f + dx,
                        PIXELS * .36f - 7 * factor + dy, 0).scale(factor, factor, 1);
                int total=row.layout.text.codePointCount(0,row.layout.text.length());
                if(row.sungGlyphs>0&&row.sungGlyphs<total){
                    int end=row.layout.text.offsetByCodePoints(0,row.sungGlyphs);String sung=row.layout.text.substring(0,end),future=row.layout.text.substring(end);
                    MultiBufferSource.BufferSource buffers=nativeBuffers();
                    drawNative(sung,nativePose,row,throughWalls,strength,-row.width*.5f+dx,factor,palette,buffers);
                    float advance=MC.font.getSplitter().stringWidth(sung);
                    drawNative(future,new Matrix4f(nativePose).translate(advance,0,0),row,throughWalls,strength*row.line.futureOpacity,-row.width*.5f+dx+advance*factor,factor,palette,buffers);
                    buffers.endBatch();restoreNativeState(throughWalls);
                }else drawNative(row.layout.text,nativePose,row,throughWalls,visibility(row,0,strength),-row.width*.5f+dx,factor,palette);
            }
        }
    }

    private static void drawNative(String text, Matrix4f pose, Row row, boolean throughWalls, float alpha,
                                   float origin, float pixelsPerUnit, PaletteText.Style palette) {
        MultiBufferSource.BufferSource buffers=nativeBuffers();
        drawNative(text,pose,row,throughWalls,alpha,origin,pixelsPerUnit,palette,buffers);
        buffers.endBatch();restoreNativeState(throughWalls);
    }
    private static MultiBufferSource.BufferSource nativeBuffers(){
        if (vanillaMemory == null) vanillaMemory = new ByteBufferBuilder(16384);
        return MultiBufferSource.immediate(vanillaMemory);
    }
    private static void drawNative(String text,Matrix4f pose,Row row,boolean throughWalls,float alpha,
                                   float origin,float pixelsPerUnit,PaletteText.Style palette,MultiBufferSource.BufferSource buffers){
        Matrix4f inverse = new Matrix4f(pose).invert();
        com.cheatprogram.compat.GuiBridge.drawInBatch(MC.font,Component.literal(text), 0, 0, 0xffffffff, false, pose,
                type -> new ColoredVertex(buffers.getBuffer(type), inverse, row, origin, pixelsPerUnit, alpha, palette),
                throughWalls ? Font.DisplayMode.SEE_THROUGH : Font.DisplayMode.NORMAL, 0, 15728880);
    }
    private static void restoreNativeState(boolean throughWalls){
        RenderSystem.depthMask(false);
        if (throughWalls) RenderSystem.disableDepthTest(); else RenderSystem.enableDepthTest();
        RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.disableCull();
    }

    /** Both edges are independently shaded, including within wide CJK glyphs and the native font fallback. */
    private static int color(Row row, float x, float alpha, PaletteText.Style palette) {
        float position = (x + row.width * .5f) / Math.max(1, row.width);
        int rgb = PaletteText.rgb(palette, position);
        // Preview visibility belongs to the glyph, never a shifted halo/bitmap vertex.
        float brightness = 1;
        int r = Math.round((rgb >> 16 & 255) * brightness), g = Math.round((rgb >> 8 & 255) * brightness);
        int b = Math.round((rgb & 255) * brightness), a = Math.round(Math.max(0, Math.min(1, alpha)) * 255);
        return a << 24 | r << 16 | g << 8 | b;
    }
    private static float visibility(Row row,int ordinal,float alpha){return ordinal<row.sungGlyphs?alpha:alpha*row.line.futureOpacity;}

    private static final class ColoredVertex implements VertexConsumer {
        private final VertexConsumer target;
        private final Matrix4f inverse;
        private final Row row;
        private final float origin, pixelsPerUnit, alpha;
        private final PaletteText.Style palette;
        private int color;
        private ColoredVertex(VertexConsumer target, Matrix4f inverse, Row row, float origin,
                              float pixelsPerUnit, float alpha, PaletteText.Style palette) {
            this.target = target; this.inverse = inverse; this.row = row; this.origin = origin;
            this.pixelsPerUnit = pixelsPerUnit; this.alpha = alpha; this.palette = palette;
        }
        @Override public VertexConsumer addVertex(float x, float y, float z) {
            float localX = inverse.m00() * x + inverse.m10() * y + inverse.m20() * z + inverse.m30();
            color = color(row, origin + localX * pixelsPerUnit, alpha, palette);
            target.addVertex(x, y, z); return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            target.setColor(r * (color >> 16 & 255) / 255, g * (color >> 8 & 255) / 255,
                    b * (color & 255) / 255, a * (color >>> 24) / 255); return this;
        }
        @Override public VertexConsumer setUv(float u, float v) { target.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { target.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { target.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { target.setNormal(x, y, z); return this; }
        @Override public VertexConsumer setLineWidth(float width) { target.setLineWidth(width); return this; }
        @Override public VertexConsumer setColor(int argb) { return setColor(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24); }
    }

    private static float finite(float value, float fallback) { return Float.isFinite(value) ? value : fallback; }
    private static float bounded(float value, float low, float high, float fallback) {
        return Math.max(low, Math.min(high, finite(value, fallback)));
    }
}
