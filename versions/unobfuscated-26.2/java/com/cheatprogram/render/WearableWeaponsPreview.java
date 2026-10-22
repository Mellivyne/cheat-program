package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.module.visual.WearableWeapons;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.cheatprogram.compat.gl.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.cheatprogram.compat.gl.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.HumanoidModel;
import com.cheatprogram.compat.gl.CompiledShaderProgram;
import com.cheatprogram.compat.gl.CoreShaders;
import com.cheatprogram.compat.gl.FogParameters;
import com.cheatprogram.compat.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
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
    public void draw(GuiGraphicsExtractor graphics, float x, float y, float width, float height, WearableWeapons module) {
        ready = false;
        Minecraft mc = Minecraft.getInstance();
        if (module == null || mc.player == null || !module.showPreview() || width < 2 || height < 2 || failed || !R2D.isActive()) return;
        int pixelsX = Math.max(1, Math.min(1024, Math.round(width))), pixelsY = Math.max(1, Math.min(1024, Math.round(height)));
        SavedState saved = new SavedState();
        try {
            com.cheatprogram.compat.GuiBridge.flush(graphics);
            try {
                RenderSystem.disableScissor();
                if (target == null) { target = new TextureTarget("Cheat Program offscreen",pixelsX,pixelsY,true,com.mojang.blaze3d.GpuFormat.RGBA8_UNORM); com.cheatprogram.compat.gl.Framebuffers.setClearColor(target, 0, 0, 0, 0); com.cheatprogram.compat.gl.Framebuffers.setFilterMode(target, GL11.GL_LINEAR); }
                else if (target.width != pixelsX || target.height != pixelsY) target.resize(pixelsX, pixelsY);
                RenderSystem.depthMask(true); RenderSystem.colorMask(true, true, true, true);
                com.cheatprogram.compat.gl.Framebuffers.clear(target); com.cheatprogram.compat.gl.Framebuffers.bindWrite(target, true);
                RenderSystem.enableDepthTest(); RenderSystem.depthFunc(GL11.GL_GEQUAL);
                RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc(); RenderSystem.enableCull();
                RenderSystem.setShaderFog(FogParameters.NO_FOG); RenderSystem.setShaderColor(1, 1, 1, 1);
                RenderSystem.getModelViewStack().identity();
                float aspect = pixelsX / (float)pixelsY;
                RenderSystem.setProjectionMatrix(com.cheatprogram.compat.NativeProjection262.ortho(-aspect, aspect, -1, 1, -100, 100), ProjectionType.ORTHOGRAPHIC);
                mc.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
                AvatarRenderer renderer = (AvatarRenderer)(Object)mc.getEntityRenderDispatcher().getRenderer(mc.player);
                // The entity-taking overload reuses the renderer's live state; allocate our own instead.
                AvatarRenderState state = renderer.createRenderState();
                renderer.extractRenderState(mc.player, state, 1);
                state.bodyRot = 180; state.yRot = 0; state.xRot = 0;
                state.walkAnimationPos = state.walkAnimationSpeed = state.swimAmount = state.attackTime = 0;
                state.isCrouching = state.isVisuallySwimming = state.isFallFlying = state.isAutoSpinAttack = false;
                state.deathTime = 0; state.pose = Pose.STANDING; state.isSpectator = state.isInvisible = false;
                state.isPassenger = state.isUpsideDown = state.hasRedOverlay = false; state.bedOrientation = null;
                state.leftArmPose = state.rightArmPose = HumanoidModel.ArmPose.EMPTY;
                state.leftHandItemState.clear(); state.rightHandItemState.clear(); state.heldOnHead.clear();
                state.nameTag = null; state.scoreText = null;
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
                    buffers.nativeRenderer.avatar(renderer, state, pose, 15728880);
                    buffers.draw(target);
                } finally { WearableWeaponsLayer.setPreviewDrawing(oldPreview); }
            } finally { mc.gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D); saved.restore(); }
            R2D.applyState(); R2D.pushClip(x, y, width, height);
            try {
                saved.intersectClip();
                float guiScale = (float)mc.getWindow().getGuiScale(); Matrix4f pose = R2D.canvasPose();
                int fade = R2D.fade(0xffffffff) >>> 24, color = fade << 24 | fade << 16 | fade << 8 | fade;
                BufferBuilder quad = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
                quad.addVertex(pose, x / guiScale, y / guiScale, 0).setUv(0, 1).setColor(color);
                quad.addVertex(pose, x / guiScale, (y + height) / guiScale, 0).setUv(0, 0).setColor(color);
                quad.addVertex(pose, (x + width) / guiScale, (y + height) / guiScale, 0).setUv(1, 0).setColor(color);
                quad.addVertex(pose, (x + width) / guiScale, y / guiScale, 0).setUv(1, 1).setColor(color);
                RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                RenderSystem.setShaderColor(1, 1, 1, 1); RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
                RenderSystem.setShaderTexture(0, com.cheatprogram.compat.gl.Framebuffers.color(target)); BufferUploader.drawWithShader(quad.buildOrThrow());
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
    private static final class PreviewBuffers implements AutoCloseable {
        final com.cheatprogram.compat.NativePreviewRenderer nativeRenderer = new com.cheatprogram.compat.NativePreviewRenderer();
        void draw(TextureTarget target) { nativeRenderer.draw(target); }
        @Override public void close() { nativeRenderer.close(); }
    }
    private static float finite(float value, float bound) { return Float.isFinite(value) ? Math.max(-bound, Math.min(bound, value)) : 0; }
    @Override public void close() {
        if (target != null) { SavedState saved = new SavedState(); try { target.destroyBuffers(); target = null; } finally { saved.restore(); } }
        ready = failed = false;
    }
    private static final class SavedState {
        final int[] viewport = new int[4], scissor = new int[4], colorMask = new int[4];
        final int[] shaderTextures = new int[12], textureBindings = new int[12], samplerBindings = new int[12];
        final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        final boolean clipped = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), blend = GL11.glIsEnabled(GL11.GL_BLEND);
        final boolean polygonOffset = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL), colorLogic = GL11.glIsEnabled(GL11.GL_COLOR_LOGIC_OP);
        final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC), srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB), srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        final int equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB), equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
        final int logicOperation = GL11.glGetInteger(GL11.GL_LOGIC_OP_MODE), activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        final int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING), program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        final float offsetFactor = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR), offsetUnits = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
        final float lineWidth = RenderSystem.getShaderLineWidth(), glintAlpha = RenderSystem.getShaderGlintAlpha();
        final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix()), modelView = new Matrix4f(RenderSystem.getModelViewStack()), textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
        final ProjectionType projectionType = RenderSystem.getProjectionType();
        final FogParameters fog = RenderSystem.getShaderFog();
        final float[] color = RenderSystem.getShaderColor().clone();
        final CompiledShaderProgram shader = RenderSystem.getShader();
        final com.mojang.blaze3d.buffers.GpuBufferSlice lights = RenderSystem.getShaderLights();
        SavedState() {
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport); GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor); GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorMask);
            for (int i = 0; i < shaderTextures.length; i++) {
                shaderTextures[i] = RenderSystem.getShaderTexture(i);
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i); textureBindings[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D); samplerBindings[i] = GL11.glGetInteger(org.lwjgl.opengl.GL33.GL_SAMPLER_BINDING);
            }
            RenderSystem.activeTexture(activeTexture);
            // Materialize the current global light directions into an existing shader's
            // CPU uniforms; no reflection/accessor or shader activation is needed.

        }
        void intersectClip() {
            if (!clipped) return; int[] current = new int[4]; GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, current);
            int x = Math.max(current[0], scissor[0]), y = Math.max(current[1], scissor[1]);
            int right = Math.min(current[0] + current[2], scissor[0] + scissor[2]), top = Math.min(current[1] + current[3], scissor[1] + scissor[3]);
            RenderSystem.enableScissor(x, y, Math.max(0, right - x), Math.max(0, top - y));
        }
        void restore() {
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer); GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer); RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            RenderSystem.setProjectionMatrix(projection, projectionType); RenderSystem.getModelViewStack().set(modelView); RenderSystem.setTextureMatrix(textureMatrix);
            RenderSystem.setShaderFog(fog); RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
            for (int i = 0; i < shaderTextures.length; i++) {
                RenderSystem.setShaderTexture(i, shaderTextures[i]); RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i); GlStateManager._bindTexture(textureBindings[i]); org.lwjgl.opengl.GL33.glBindSampler(i, samplerBindings[i]);
            }
            RenderSystem.activeTexture(activeTexture); if (shader == null) RenderSystem.clearShader(); else RenderSystem.setShader(shader);
            RenderSystem.setShaderLights(lights);
            RenderSystem.lineWidth(lineWidth); RenderSystem.setShaderGlintAlpha(glintAlpha);
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest(); if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull(); if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            RenderSystem.depthFunc(depthFunction); RenderSystem.depthMask(depthMask); RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha); RenderSystem.colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
            GL20.glBlendEquationSeparate(equationRgb, equationAlpha);
            RenderSystem.polygonOffset(offsetFactor, offsetUnits); if (polygonOffset) RenderSystem.enablePolygonOffset(); else RenderSystem.disablePolygonOffset();
            GlStateManager._logicOp(logicOperation); if (colorLogic) RenderSystem.enableColorLogicOp(); else RenderSystem.disableColorLogicOp();
            BufferUploader.invalidate(); GlStateManager._glBindVertexArray(vertexArray); GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer); GlStateManager._glUseProgram(program);
            if (clipped) RenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]); else RenderSystem.disableScissor();
        }
    }
}
