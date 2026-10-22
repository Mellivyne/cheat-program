package com.cheatprogram.render;

import com.cheatprogram.compat.gl.VertexBuffer;

import com.cheatprogram.audio.AcousticScene;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.cheatprogram.compat.gl.CoreShaders;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Immutable probe geometry is uploaded only when it changes, not on every video frame. */
public final class AcousticRayRenderer implements AutoCloseable {
    private static final int UPLOAD_SEGMENTS_PER_FRAME = 1024;
    private final List<Layer> layers = new ArrayList<>();
    private record Layer(int bounce, VertexBuffer vertices) { }
    private List<AcousticScene.Segment> snapshot;
    private int count;
    private double opacity = -1;
    private Vec3 origin = Vec3.ZERO;

    public int prepare(AcousticScene.Response response, int limit, double alpha) {
        if (!RenderSystem.isOnRenderThread()) throw new IllegalStateException("Acoustic geometry uploads require the render thread");
        List<AcousticScene.Segment> segments = response == null ? List.of() : response.segments();
        int wanted = Math.min(Math.max(0, limit), segments.size());
        opacity = Double.isFinite(alpha) ? Math.max(0, Math.min(1, alpha)) : 0;
        if (wanted < count || !AcousticScene.sameSegmentPrefix(snapshot, segments, count)) {
            release(); count = 0;
        }
        snapshot = segments;
        if (wanted == 0) return 0;
        if (wanted == count) return count;
        if (count == 0) origin = segments.getFirst().from();
        int until = Math.min(wanted, count + UPLOAD_SEGMENTS_PER_FRAME);
        // A high-quality partial bake appends a bounded batch. Earlier VBOs are immutable and retained.
        List<List<AcousticScene.Segment>> groups = new ArrayList<>(17);
        for (int i = 0; i <= 16; i++) groups.add(new ArrayList<>());
        for (int i = count; i < until; i++) {
            var segment = segments.get(i);
            groups.get(Math.max(0, Math.min(16, segment.bounce()))).add(segment);
        }
        try { for (int bounce = 0; bounce < groups.size(); bounce++) {
            var group = groups.get(bounce); if (group.isEmpty()) continue;
            BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
            for (var segment : group) {
                double dx = segment.to().x - segment.from().x, dy = segment.to().y - segment.from().y, dz = segment.to().z - segment.from().z;
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz); if (length < 1e-8) continue;
                int color = Math.max(0, Math.min(255, (int)Math.round(255 * Math.sqrt(Math.max(.02, Math.min(1, segment.energy())))))) << 24 | 0xffffff;
                vertex(builder, segment.from(), color, dx / length, dy / length, dz / length);
                vertex(builder, segment.to(), color, dx / length, dy / length, dz / length);
            }
            MeshData mesh = builder.build(); if (mesh == null) continue;
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            try { buffer.bind(); buffer.upload(mesh); layers.add(new Layer(bounce, buffer)); }
            catch (RuntimeException failure) { buffer.close(); throw failure; }
            finally { VertexBuffer.unbind(); }
        }
        } catch (RuntimeException failure) {
            release(); snapshot = null; count = 0; opacity = -1; throw failure;
        }
        count = until;
        return count;
    }

    private void vertex(BufferBuilder builder, Vec3 point, int color, double nx, double ny, double nz) {
        builder.addVertex((float)(point.x - origin.x), (float)(point.y - origin.y), (float)(point.z - origin.z))
                .setColor(color).setNormal((float)nx, (float)ny, (float)nz);
    }

    public void draw(Render3D renderer, boolean animated, double seconds) {
        if (!RenderSystem.isOnRenderThread()) throw new IllegalStateException("Acoustic geometry drawing requires the render thread");
        if (layers.isEmpty()) return;
        var model = renderer.viewMatrix().translate((float)(origin.x - renderer.camera.x),
                (float)(origin.y - renderer.camera.y), (float)(origin.z - renderer.camera.z));
        var projection = renderer.projectionMatrix();
        RenderSystem.setShader(CoreShaders.RENDERTYPE_LINES);
        try { for (Layer layer : layers) {
            float pulse = animated ? (float)(.35 + .65 * Math.pow(.5 + .5 * Math.sin(seconds * 4 - layer.bounce() * .8), 2)) : 1;
            RenderSystem.setShaderColor(1, 1, 1, pulse * (float) opacity);
            try { layer.vertices().bind(); layer.vertices().drawWithShader(model, projection, RenderSystem.getShader()); }
            finally { VertexBuffer.unbind(); }
        } } finally { RenderSystem.setShaderColor(1, 1, 1, 1); }
    }

    private void release() { for (Layer layer : layers) layer.vertices().close(); layers.clear(); }
    @Override public void close() {
        if (!RenderSystem.isOnRenderThread()) { RenderSystem.recordRenderCall(this::close); return; }
        release(); snapshot = null; count = 0; opacity = -1;
    }
}
