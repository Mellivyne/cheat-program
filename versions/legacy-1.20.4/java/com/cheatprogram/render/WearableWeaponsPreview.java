package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.module.visual.WearableWeapons;
import com.cheatprogram.compat.legacy.LegacyProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.cheatprogram.compat.legacy.LegacyRenderSystem;
import com.cheatprogram.compat.legacy.LegacyBufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.cheatprogram.compat.legacy.LegacyByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.cheatprogram.compat.legacy.LegacyTesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.cheatprogram.compat.legacy.LegacyVertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.ShaderInstance;
import com.cheatprogram.compat.legacy.LegacyCoreShaders;
import com.cheatprogram.compat.legacy.LegacyFogParameters;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Pose;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.LinkedHashMap;
import java.util.Map;

/** Independent render state and FBO: dragging never mutates the real player's aim or pose. */
public final class WearableWeaponsPreview implements AutoCloseable {
    private TextureTarget target;
    private boolean ready, failed;
    public float yaw = 145, pitch = -8, zoom = 1, panX, panY;
    public void reset() { yaw = 145; pitch = -8; zoom = 1; panX = panY = 0; failed = false; }
    public boolean ready() { return ready; }
    public String statusKey() { return Minecraft.getInstance().player == null ? "ui.wearable.noworld" : "ui.wearable.previewFailed"; }
    /** All bounds are framebuffer pixels, exactly like Canvas.px()/py() and R2D. */
    public void draw(GuiGraphics graphics, float x, float y, float width, float height, WearableWeapons module) {
        ready = false;
        Minecraft mc = Minecraft.getInstance();
        if (module == null || mc.player == null || !module.showPreview() || width < 2 || height < 2 || failed || !R2D.isActive()) return;
        int pixelsX = Math.max(1, Math.min(1024, Math.round(width))), pixelsY = Math.max(1, Math.min(1024, Math.round(height)));
        SavedState saved = new SavedState();
        try {
            graphics.flush();
            try {
                LegacyRenderSystem.disableScissor();
                if (target == null) { target = new TextureTarget(pixelsX, pixelsY, true, Minecraft.ON_OSX); target.setClearColor(0, 0, 0, 0); target.setFilterMode(GL11.GL_LINEAR); }
                else if (target.width != pixelsX || target.height != pixelsY) target.resize(pixelsX, pixelsY,Minecraft.ON_OSX);
                LegacyRenderSystem.depthMask(true); LegacyRenderSystem.colorMask(true, true, true, true);
                target.clear(Minecraft.ON_OSX); target.bindWrite(true);
                LegacyRenderSystem.enableDepthTest(); LegacyRenderSystem.depthFunc(GL11.GL_LEQUAL);
                LegacyRenderSystem.enableBlend(); LegacyRenderSystem.defaultBlendFunc(); LegacyRenderSystem.enableCull();
                LegacyRenderSystem.setShaderFog(LegacyFogParameters.NO_FOG); LegacyRenderSystem.setShaderColor(1, 1, 1, 1);
                LegacyRenderSystem.getModelViewStack().identity();
                float aspect = pixelsX / (float)pixelsY;
                LegacyRenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(-aspect, aspect, -1, 1, -100, 100), LegacyProjectionType.ORTHOGRAPHIC);
                Lighting.setupForEntityInInventory();
                PlayerRenderer renderer = (PlayerRenderer)(Object)mc.getEntityRenderDispatcher().getRenderer(mc.player);
                // The entity-taking overload reuses the renderer's live state; allocate our own instead.
                AbstractClientPlayer state=com.cheatprogram.compat.legacy.LegacyPlayerPreview.player();

                PoseStack pose = new PoseStack();
                pose.translate(finite(panX, .45f) * Math.min(1, aspect), -finite(panY, .45f), 0);
                pose.mulPose(Axis.XP.rotationDegrees(finite(pitch, 65)));
                pose.mulPose(Axis.YP.rotationDegrees(finite(yaw, 360)));
                // A standing player is 1.8 blocks tall. Reserve space for back-mounted items
                // and keep panning bounded in the same normalized frame as the projection.
                float scale = .76f * Math.min(1, aspect) * Math.max(.35f, Math.min(2.4f, finite(zoom, 2.4f)));
                pose.scale(scale, scale, scale);
                pose.translate(0, -.9, 0);
                boolean oldPreview = WearableWeaponsLayer.previewDrawing();
                WearableWeaponsLayer.setPreviewDrawing(true);
                try (PreviewBuffers buffers = new PreviewBuffers()) {
                    renderer.render(state,0,1,pose,buffers,15728880);
                    buffers.draw(target);
                } finally { WearableWeaponsLayer.setPreviewDrawing(oldPreview); }
            } finally { Lighting.setupFor3DItems(); saved.restore(); }
            R2D.applyState(); R2D.pushClip(x, y, width, height);
            try {
                saved.intersectClip();
                float guiScale = (float)mc.getWindow().getGuiScale(); Matrix4f pose = R2D.canvasPose();
                int fade = R2D.fade(0xffffffff) >>> 24, color = fade << 24 | fade << 16 | fade << 8 | fade;
                LegacyBufferBuilder quad = LegacyTesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                quad.addVertex(pose, x / guiScale, y / guiScale, 0).setUv(0, 1).setColor(color);
                quad.addVertex(pose, x / guiScale, (y + height) / guiScale, 0).setUv(0, 0).setColor(color);
                quad.addVertex(pose, (x + width) / guiScale, (y + height) / guiScale, 0).setUv(1, 0).setColor(color);
                quad.addVertex(pose, (x + width) / guiScale, y / guiScale, 0).setUv(1, 1).setColor(color);
                LegacyRenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                LegacyRenderSystem.setShaderColor(1, 1, 1, 1); LegacyRenderSystem.setShader(LegacyCoreShaders.POSITION_TEX_COLOR);
                LegacyRenderSystem.setShaderTexture(0, target.getColorTextureId()); com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(quad.buildOrThrow());
                ready = true;
            } finally { R2D.popClip(); }
        } catch (Exception error) { failed = true; CheatProgram.LOGGER.warn("Wearable weapon preview failed", error); }
        finally { saved.restore(); }
    }
    /**
     * Vanilla RenderType.draw() selects MAIN_TARGET (or a transparency target) during
     * setup. The shared BufferSource therefore leaks the player onto the real screen
     * using the small FBO viewport. Own every mesh and bind our FBO AFTER layer setup.
     */
    private static final class PreviewBuffers implements MultiBufferSource, AutoCloseable {
        private final Map<RenderType, Batch> batches = new LinkedHashMap<>();
        private static final class Batch implements AutoCloseable {
            final LegacyByteBufferBuilder storage = new LegacyByteBufferBuilder(128 * 1024);
            final LegacyBufferBuilder vertices;
            Batch(RenderType type) { vertices = new LegacyBufferBuilder(storage, type.mode(), type.format()); }
            @Override public void close() { storage.close(); }
        }
        @Override public LegacyVertexConsumer getBuffer(RenderType type) {
            return batches.computeIfAbsent(type, Batch::new).vertices;
        }
        void draw(TextureTarget target) {
            for (int pass = 0; pass < 3; pass++) for (var entry : batches.entrySet()) {
                RenderType type = entry.getKey();
                boolean glint = type == RenderType.glint() || type == RenderType.glintTranslucent()
                        || type == RenderType.entityGlint() || type == RenderType.armorEntityGlint();
                if ((glint ? 2 : ((com.cheatprogram.mixin.LegacyRenderTypeAccessor)(Object)type).cheatprogram$sortOnUpload() ? 1 : 0) != pass) continue;
                Batch batch = entry.getValue();
                var mesh = batch.vertices.build();
                if (mesh == null) continue;
                
                type.setupRenderState();
                try {
                    target.bindWrite(true);
                    LegacyRenderSystem.disableScissor();
                    LegacyRenderSystem.setShaderColor(1, 1, 1, 1);
                    com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(mesh);
                } finally { type.clearRenderState(); }
            }
        }
        @Override public void close() { for (Batch batch : batches.values()) batch.close(); batches.clear(); }
    }
    private static float finite(float value, float bound) { return Float.isFinite(value) ? Math.max(-bound, Math.min(bound, value)) : 0; }
    @Override public void close() {
        if (target != null) { SavedState saved = new SavedState(); try { target.destroyBuffers(); target = null; } finally { saved.restore(); } }
        ready = failed = false;
    }
    private static final class SavedState {
        final int[] viewport = new int[4], scissor = new int[4], colorMask = new int[4];
        final int[] shaderTextures = new int[12], textureBindings = new int[12];
        final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final boolean clipped = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), blend = GL11.glIsEnabled(GL11.GL_BLEND);
        final boolean polygonOffset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL), colorLogic = GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP);
        final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC), srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB), srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        final int logicOperation = GL11.glGetInteger(GL11.GL_LOGIC_OP_MODE), activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING), program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        final float offsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR), offsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        final float lineWidth = LegacyRenderSystem.getShaderLineWidth(), glintAlpha = LegacyRenderSystem.getShaderGlintAlpha();
        final Matrix4f projection = new Matrix4f(LegacyRenderSystem.getProjectionMatrix()), modelView = new Matrix4f(LegacyRenderSystem.getModelViewStack()), textureMatrix = new Matrix4f(LegacyRenderSystem.getTextureMatrix());
        final LegacyProjectionType projectionType = LegacyRenderSystem.getProjectionType();
        final LegacyFogParameters fog = LegacyRenderSystem.getShaderFog();
        final float[] color = LegacyRenderSystem.getShaderColor().clone();
        final ShaderInstance shader = LegacyRenderSystem.getShader();
        final Vector3f light0, light1;
        SavedState() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport); GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor); GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorMask);
            for (int i = 0; i < shaderTextures.length; i++) {
                shaderTextures[i] = LegacyRenderSystem.getShaderTexture(i);
                LegacyRenderSystem.activeTexture(GL13.GL_TEXTURE0 + i); textureBindings[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            }
            LegacyRenderSystem.activeTexture(activeTexture);
            // Materialize the current global light directions into an existing shader's
            // CPU uniforms; no reflection/accessor or shader activation is needed.
            Vector3f first = null, second = null;
            ShaderInstance probe = LegacyCoreShaders.RENDERTYPE_ENTITY_SOLID.get();
            if (probe != null && probe.LIGHT0_DIRECTION != null && probe.LIGHT1_DIRECTION != null) {
                LegacyRenderSystem.setupShaderLights(probe);
                var a = probe.LIGHT0_DIRECTION.getFloatBuffer(); var b = probe.LIGHT1_DIRECTION.getFloatBuffer();
                first = new Vector3f(a.get(0), a.get(1), a.get(2)); second = new Vector3f(b.get(0), b.get(1), b.get(2));
            }
            light0 = first; light1 = second;
        }
        void intersectClip() {
            if (!clipped) return; int[] current = new int[4]; GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, current);
            int x = Math.max(current[0], scissor[0]), y = Math.max(current[1], scissor[1]);
            int right = Math.min(current[0] + current[2], scissor[0] + scissor[2]), top = Math.min(current[1] + current[3], scissor[1] + scissor[3]);
            LegacyRenderSystem.enableScissor(x, y, Math.max(0, right - x), Math.max(0, top - y));
        }
        void restore() {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer); GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer); LegacyRenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            LegacyRenderSystem.setProjectionMatrix(projection, projectionType); LegacyRenderSystem.getModelViewStack().set(modelView); LegacyRenderSystem.setTextureMatrix(textureMatrix);
            LegacyRenderSystem.setShaderFog(fog); LegacyRenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            for (int i = 0; i < shaderTextures.length; i++) {
                LegacyRenderSystem.setShaderTexture(i, shaderTextures[i]); LegacyRenderSystem.activeTexture(GL13.GL_TEXTURE0 + i); GlStateManager._bindTexture(textureBindings[i]);
            }
            LegacyRenderSystem.activeTexture(activeTexture); if (shader == null) LegacyRenderSystem.clearShader(); else LegacyRenderSystem.setShader(shader);
            if (light0 != null) LegacyRenderSystem.setShaderLights(new Vector3f(light0), new Vector3f(light1));
            LegacyRenderSystem.lineWidth(lineWidth); LegacyRenderSystem.setShaderGlintAlpha(glintAlpha);
            if (depth) LegacyRenderSystem.enableDepthTest(); else LegacyRenderSystem.disableDepthTest(); if (cull) LegacyRenderSystem.enableCull(); else LegacyRenderSystem.disableCull(); if (blend) LegacyRenderSystem.enableBlend(); else LegacyRenderSystem.disableBlend();
            LegacyRenderSystem.depthFunc(depthFunction); LegacyRenderSystem.depthMask(depthMask); LegacyRenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha); LegacyRenderSystem.colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            LegacyRenderSystem.polygonOffset(offsetFactor, offsetUnits); if (polygonOffset) LegacyRenderSystem.enablePolygonOffset(); else LegacyRenderSystem.disablePolygonOffset();
            GlStateManager._logicOp(logicOperation); if (colorLogic) LegacyRenderSystem.enableColorLogicOp(); else LegacyRenderSystem.disableColorLogicOp();
            BufferUploader.invalidate(); GlStateManager._glBindVertexArray(vertexArray); GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer); GlStateManager._glUseProgram(program);
            if (clipped) LegacyRenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]); else LegacyRenderSystem.disableScissor();
        }
    }
}
