package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.module.visual.OreXray.Ore;
import com.cheatprogram.module.visual.OreXray.SectionKey;
import com.cheatprogram.compat.gl.GlStateManager;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.cheatprogram.compat.NativeCachedMesh;
import net.minecraft.client.Minecraft;
import com.cheatprogram.compat.gl.CompiledShaderProgram;
import com.cheatprogram.compat.gl.FogParameters;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
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
	private record Layer(RenderType type, NativeCachedMesh vertices) implements AutoCloseable {
		@Override public void close() { vertices.close(); }
	}
	private record Group(List<Ore> ores, List<Layer> layers) implements AutoCloseable {
		@Override public void close() { for (Layer layer : layers) layer.close(); }
	}
	private static final class Builder implements AutoCloseable {
		final ByteBufferBuilder storage = new ByteBufferBuilder(32 * 1024);
		final BufferBuilder buffer;
		Builder(RenderType type) { buffer = new BufferBuilder(storage, type.mode(), type.format()); }
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
						MeshData mesh = entry.getValue().buffer.build();
						if (mesh == null) continue;
						NativeCachedMesh vertices = new NativeCachedMesh();
						try { vertices.upload(mesh); layers.add(new Layer(entry.getKey(), vertices)); }
						catch (Throwable failure) { vertices.close(); throw failure; }
						finally {  }
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
			RenderSystem.setShaderFog(FogParameters.NO_FOG); 
			Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
			// Farther sections draw first, preserving sensible transparent overlaps.
			List<SectionKey> order = new ArrayList<>(groups.keySet());
			order.sort(Comparator.comparingDouble((SectionKey section) -> section.distance(render.camera)).reversed());
			for (SectionKey key : order) {
				if (MC.level.getChunkSource().getChunk(key.x(), key.z(), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) == null) {
					Group removed = groups.remove(key); if (removed != null) removed.close(); queued.remove(key); continue;
				}
				Group group = groups.get(key);
				Matrix4f model = render.viewMatrix().translate((float)((key.x() << 4) - render.camera.x), (float)((key.y() << 4) - render.camera.y), (float)((key.z() << 4) - render.camera.z));
				for (Layer layer : group.layers) {
					layer.vertices.draw(layer.type,model,projection,null,alpha,false,true);
				}
			}
		} catch (Throwable failure) {
			releaseMeshes(); changes.clear(); clearRequested = false;
			CheatProgram.LOGGER.warn("Ore model overlay could not be drawn", failure);
		} finally { previous.restore(); }
	}
	@Override public void close() {
		changes.clear(); clearRequested = false; modelGeneration = null;
		if (RenderSystem.isOnRenderThread()) releaseMeshes(); else RenderSystem.recordRenderCall(this::releaseMeshes);
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
		final float[] color = RenderSystem.getShaderColor().clone();
		final FogParameters fog = RenderSystem.getShaderFog();
		final Matrix4f textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
		final int texture0 = RenderSystem.getShaderTexture(0), texture1 = RenderSystem.getShaderTexture(1), texture2 = RenderSystem.getShaderTexture(2);
		final CompiledShaderProgram shader = RenderSystem.getShader();
		SavedState() { GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport); GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor); GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorMask); }
		void bindOutput() {
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer); GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
			RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
		}
		void restore() {
			bindOutput(); RenderSystem.setShaderFog(fog); RenderSystem.setTextureMatrix(textureMatrix);
			RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
			RenderSystem.setShaderTexture(0, texture0); RenderSystem.setShaderTexture(1, texture1); RenderSystem.setShaderTexture(2, texture2);
			RenderSystem.activeTexture(activeTexture);
			if (shader == null) RenderSystem.clearShader(); else RenderSystem.setShader(shader);
			if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
			if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
			if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
			RenderSystem.depthFunc(depthFunction); RenderSystem.depthMask(depthMask); RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
			RenderSystem.colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
			if (clipped) RenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]); else RenderSystem.disableScissor();
		}
	}
}
