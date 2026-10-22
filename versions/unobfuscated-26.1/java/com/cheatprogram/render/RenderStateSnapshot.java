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
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.HumanoidModel;
import com.cheatprogram.compat.gl.CompiledShaderProgram;
import com.cheatprogram.compat.gl.CoreShaders;
import com.cheatprogram.compat.gl.FogParameters;
import net.minecraft.client.renderer.MultiBufferSource;
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

/** Captures native and RenderSystem state around an isolated offscreen pass. */
public final class RenderStateSnapshot {
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
        public RenderStateSnapshot() {
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
        public void restore() {
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
