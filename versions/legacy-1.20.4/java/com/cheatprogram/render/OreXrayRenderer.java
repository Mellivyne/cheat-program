package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.module.visual.OreXray.Ore;
import com.cheatprogram.module.visual.OreXray.SectionKey;
import com.mojang.blaze3d.vertex.VertexBuffer.Usage;
import com.mojang.blaze3d.platform.GlStateManager;
import com.cheatprogram.compat.legacy.LegacyRenderSystem;
import com.cheatprogram.compat.legacy.LegacyBufferBuilder;
import com.cheatprogram.compat.legacy.LegacyByteBufferBuilder;
import com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import com.cheatprogram.compat.legacy.LegacyFogParameters;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Cached vanilla six-face models, rendered independently of the world's depth and chunk meshes. */
public final class OreXrayRenderer implements AutoCloseable {
	private static final Minecraft MC = Minecraft.getInstance();
	private final Map<SectionKey, List<Ore>> changes = new LinkedHashMap<>();
	private final Map<SectionKey, Group> groups = new LinkedHashMap<>();
	private final Map<SectionKey, List<Ore>> queued = new LinkedHashMap<>();
	private Bake bake;
	private boolean clearRequested;
	private Object modelGeneration;
	private record Layer(RenderType type, VertexBuffer vertices) implements AutoCloseable {
		@Override public void close() { vertices.close(); }
	}
	private record Group(List<Ore> ores, List<Layer> layers) implements AutoCloseable {
		@Override public void close() { for (Layer layer : layers) layer.close(); }
	}
	private static final class Builder implements AutoCloseable {
		final LegacyByteBufferBuilder storage = new LegacyByteBufferBuilder(32 * 1024);
		final LegacyBufferBuilder buffer;
		Builder(RenderType type) { buffer = new LegacyBufferBuilder(storage, type.mode(), type.format()); }
		@Override public void close() { storage.close(); }
	}
	private static final class Bake implements AutoCloseable {
		final SectionKey key;
		final List<Ore> ores;
		final Map<RenderType, Builder> builders = new LinkedHashMap<>();
		final PoseStack poses = new PoseStack();
		int cursor;
		Bake(SectionKey key, List<Ore> ores) { this.key = key; this.ores = ores; }
		MultiBufferSource source() { return type -> builders.computeIfAbsent(type, Builder::new).buffer; }
		@Override public void close() { for (Builder builder : builders.values()) builder.close(); builders.clear(); }
	}

	public void replace(SectionKey key, List<Ore> ores) { changes.put(key, ores); }
	public void clear() { changes.clear(); clearRequested = true; }
	private void releaseMeshes() {
		if (bake != null) { bake.close(); bake = null; }
		for (Group group : groups.values()) group.close();
		groups.clear(); queued.clear();
	}
	private void applyChanges() {
		if (clearRequested) { releaseMeshes(); clearRequested = false; modelGeneration = null; }
		// Model objects are replaced by a resource-pack reload; cached UVs must then be rebuilt.
		Object generation = MC.getBlockRenderer().getBlockModel(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
		if (modelGeneration != null && generation != modelGeneration) {
			for (var entry : groups.entrySet()) queued.put(entry.getKey(), entry.getValue().ores);
			if (bake != null) { queued.put(bake.key, bake.ores); bake.close(); bake = null; }
			for (Group group : groups.values()) group.close(); groups.clear();
		}
		modelGeneration = generation;
		for (var change : changes.entrySet()) {
			SectionKey key = change.getKey(); List<Ore> ores = change.getValue();
			if (bake != null && bake.key.equals(key)) { bake.close(); bake = null; }
			if (ores.isEmpty()) {
				queued.remove(key); Group old = groups.remove(key); if (old != null) old.close();
			} else {
				// Remove obsolete blocks immediately, then progressively build their replacement.
				Group old = groups.remove(key); if (old != null) old.close(); queued.put(key, ores);
			}
		}
		changes.clear();
	}
	private void bake(Render3D render) {
		long deadline = System.nanoTime() + 1_800_000L; int count = 0;
		while (count < 128 && System.nanoTime() < deadline) {
			if (bake == null) {
				SectionKey key = queued.keySet().stream().min(Comparator.comparingDouble(section -> section.distance(render.camera))).orElse(null);
				if (key == null) return;
				bake = new Bake(key, queued.remove(key));
			}
			Ore ore = bake.ores.get(bake.cursor++); var position = ore.position();
			bake.poses.pushPose();
			try {
				bake.poses.translate(position.getX() - (bake.key.x() << 4), position.getY() - (bake.key.y() << 4), position.getZ() - (bake.key.z() << 4));
				// renderSingleBlock emits every model face, even when neighboring stone would
				// suppress it in a normal chunk mesh. Vanilla atlas, UVs, tint and normals stay intact.
				MC.getBlockRenderer().renderSingleBlock(ore.state(), bake.poses, bake.source(), LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
			} finally { bake.poses.popPose(); }
			count++;
			if (bake.cursor >= bake.ores.size()) {
				List<Layer> layers = new ArrayList<>();
				try {
					for (var entry : bake.builders.entrySet()) {
						RenderedBuffer mesh = entry.getValue().buffer.build();
						if (mesh == null) continue;
						VertexBuffer vertices = new VertexBuffer(Usage.STATIC);
						try { vertices.bind(); vertices.upload(mesh); layers.add(new Layer(entry.getKey(), vertices)); }
						catch (Throwable failure) { vertices.close(); throw failure; }
						finally { VertexBuffer.unbind(); }
					}
					groups.put(bake.key, new Group(bake.ores, List.copyOf(layers)));
				} catch (Throwable failure) { for (Layer layer : layers) layer.close(); throw failure; }
				finally { bake.close(); bake = null; }
			}
		}
	}
	public void draw(Render3D render, float opacity) {
		if (MC.level == null) return;
		SavedState previous = new SavedState();
		try {
			applyChanges(); bake(render);
			if (groups.isEmpty()) return;
			float alpha = Math.max(0, Math.min(1, opacity));
			LegacyRenderSystem.setShaderFog(LegacyFogParameters.NO_FOG); MC.gameRenderer.lightTexture().turnOnLightLayer();
			Matrix4f projection = new Matrix4f(LegacyRenderSystem.getProjectionMatrix());
			// Farther sections draw first, preserving sensible transparent overlaps.
			List<SectionKey> order = new ArrayList<>(groups.keySet());
			order.sort(Comparator.comparingDouble((SectionKey section) -> section.distance(render.camera)).reversed());
			for (SectionKey key : order) {
				if (MC.level.getChunkSource().getChunk(key.x(), key.z(), net.minecraft.world.level.chunk.ChunkStatus.FULL, false) == null) {
					Group removed = groups.remove(key); if (removed != null) removed.close(); queued.remove(key); continue;
				}
				Group group = groups.get(key);
				Matrix4f model = render.viewMatrix().translate((float)((key.x() << 4) - render.camera.x), (float)((key.y() << 4) - render.camera.y), (float)((key.z() << 4) - render.camera.z));
				for (Layer layer : group.layers) {
					layer.type.setupRenderState();
					try {
						// OutputStateShard can bind MAIN_TARGET; retain the caller's world framebuffer.
						previous.bindOutput();
						LegacyRenderSystem.disableDepthTest(); LegacyRenderSystem.depthMask(false); LegacyRenderSystem.enableCull();
						LegacyRenderSystem.colorMask(true, true, true, true); LegacyRenderSystem.enableBlend(); LegacyRenderSystem.defaultBlendFunc();
						LegacyRenderSystem.setShaderColor(1, 1, 1, alpha);
						layer.vertices.bind(); layer.vertices.drawWithShader(model, projection, LegacyRenderSystem.getShader());
					} finally { VertexBuffer.unbind(); layer.type.clearRenderState(); }
				}
			}
		} catch (Throwable failure) {
			releaseMeshes(); changes.clear(); clearRequested = false;
			CheatProgram.LOGGER.warn("Ore model overlay could not be drawn", failure);
		} finally { previous.restore(); }
	}
	@Override public void close() {
		changes.clear(); clearRequested = false; modelGeneration = null;
		if (LegacyRenderSystem.isOnRenderThread()) releaseMeshes(); else LegacyRenderSystem.recordRenderCall(this::releaseMeshes);
	}
	private static final class SavedState {
		final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		final int[] viewport = new int[4], scissor = new int[4], colorMask = new int[4];
		final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), blend = GL11.glIsEnabled(GL11.GL_BLEND);
		final boolean clipped = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
		final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
		final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
		final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
		final float[] color = LegacyRenderSystem.getShaderColor().clone();
		final LegacyFogParameters fog = LegacyRenderSystem.getShaderFog();
		final Matrix4f textureMatrix = new Matrix4f(LegacyRenderSystem.getTextureMatrix());
		final int texture0 = LegacyRenderSystem.getShaderTexture(0), texture1 = LegacyRenderSystem.getShaderTexture(1), texture2 = LegacyRenderSystem.getShaderTexture(2);
		final ShaderInstance shader = LegacyRenderSystem.getShader();
		SavedState() { GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport); GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor); GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorMask); }
		void bindOutput() {
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer); GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
			LegacyRenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
		}
		void restore() {
			bindOutput(); LegacyRenderSystem.setShaderFog(fog); LegacyRenderSystem.setTextureMatrix(textureMatrix);
			LegacyRenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
			LegacyRenderSystem.setShaderTexture(0, texture0); LegacyRenderSystem.setShaderTexture(1, texture1); LegacyRenderSystem.setShaderTexture(2, texture2);
			LegacyRenderSystem.activeTexture(activeTexture);
			if (shader == null) LegacyRenderSystem.clearShader(); else LegacyRenderSystem.setShader(shader);
			if (depth) LegacyRenderSystem.enableDepthTest(); else LegacyRenderSystem.disableDepthTest();
			if (cull) LegacyRenderSystem.enableCull(); else LegacyRenderSystem.disableCull();
			if (blend) LegacyRenderSystem.enableBlend(); else LegacyRenderSystem.disableBlend();
			LegacyRenderSystem.depthFunc(depthFunction); LegacyRenderSystem.depthMask(depthMask); LegacyRenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
			LegacyRenderSystem.colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
			if (clipped) LegacyRenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]); else LegacyRenderSystem.disableScissor();
		}
	}
}
