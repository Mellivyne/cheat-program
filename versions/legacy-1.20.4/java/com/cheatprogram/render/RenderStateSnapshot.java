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

/** Captures native and RenderSystem state around an isolated offscreen pass. */
public final class RenderStateSnapshot {
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
        public RenderStateSnapshot() {
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
        public void restore() {
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
