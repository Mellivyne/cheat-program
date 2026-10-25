package com.cheatprogram.render;
import com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer;

import com.cheatprogram.compat.legacy.LegacyRenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.cheatprogram.compat.legacy.*;
import com.cheatprogram.compat.legacy.LegacyCoreShaders;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/** One buffer per display pass, rather than one draw call per trail segment. */
public final class WorldLines {
    public record Segment(Vec3 from, Vec3 to, int color) { }
    private WorldLines() { }
    public static void draw(Render3D renderer, List<Segment> lines, float width, boolean throughWalls) {
        if (lines.isEmpty() || renderer == null || !Float.isFinite(width)) return;
        boolean depth=GL11.glIsEnabled(GL11.GL_DEPTH_TEST),blend=GL11.glIsEnabled(GL11.GL_BLEND),cull=GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean mask=GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);float lineWidth=GL11.glGetFloat(GL11.GL_LINE_WIDTH);
        int srcRgb=GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB),dstRgb=GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),srcAlpha=GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA),dstAlpha=GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        var shader=LegacyRenderSystem.getShader();
        renderer.begin();
        try {
            if (!throughWalls) LegacyRenderSystem.enableDepthTest();
            LegacyRenderSystem.setShader(LegacyCoreShaders.RENDERTYPE_LINES);
            LegacyRenderSystem.lineWidth(Math.max(1,Math.min(16,width)));
            LegacyBufferBuilder buffer = LegacyTesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
            int drawn=0,visited=0;
            for (Segment line : lines) {
                if(drawn>=16384||++visited>32768)break;
                if(line==null||(line.color>>>24)==0||!finite(line.from)||!finite(line.to))continue;
                Vec3 normal = line.to.subtract(line.from).normalize();
                if (normal.lengthSqr() < .00001) continue;
                vertex(buffer, line.from.subtract(renderer.camera), line.color, normal);
                vertex(buffer, line.to.subtract(renderer.camera), line.color, normal);
                drawn++;
            }
            RenderedBuffer mesh=buffer.build();
            if(mesh!=null)com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(mesh);
        } finally {
            renderer.end();
            LegacyRenderSystem.depthMask(mask);LegacyRenderSystem.lineWidth(lineWidth);
            if(depth)LegacyRenderSystem.enableDepthTest();else LegacyRenderSystem.disableDepthTest();
            if(blend)LegacyRenderSystem.enableBlend();else LegacyRenderSystem.disableBlend();
            if(cull)LegacyRenderSystem.enableCull();else LegacyRenderSystem.disableCull();
            LegacyRenderSystem.blendFuncSeparate(srcRgb,dstRgb,srcAlpha,dstAlpha);
            if(shader==null)LegacyRenderSystem.clearShader();else LegacyRenderSystem.setShader(shader);
        }
    }
    private static boolean finite(Vec3 point){return point!=null&&Double.isFinite(point.x)&&Double.isFinite(point.y)&&Double.isFinite(point.z);}
    private static void vertex(LegacyBufferBuilder buffer, Vec3 point, int color, Vec3 normal) {
        buffer.addVertex((float)point.x, (float)point.y, (float)point.z).setColor(color)
                .setNormal((float)normal.x, (float)normal.y, (float)normal.z);
    }
}
