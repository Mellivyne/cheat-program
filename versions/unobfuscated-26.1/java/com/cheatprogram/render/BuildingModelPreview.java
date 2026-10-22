package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.util.BuildingFiles.Blueprint;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.cheatprogram.compat.gl.GlStateManager;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.cheatprogram.compat.gl.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.cheatprogram.compat.NativeCachedMesh;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import com.cheatprogram.compat.gl.CoreShaders;
import com.cheatprogram.compat.gl.CompiledShaderProgram;
import com.cheatprogram.compat.gl.FogParameters;


import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Real model geometry at original proportions; all blocks are incrementally cached, never subsampled. */
public final class BuildingModelPreview implements AutoCloseable {
	private static final Minecraft MC = Minecraft.getInstance();
	private Blueprint blueprint;
	private PreviewLevel level;
	private TextureTarget target;
	private final List<Layer> layers = new ArrayList<>();
	private final Map<RenderType, Builder> builders = new LinkedHashMap<>();
	private final RandomSource random = RandomSource.create();
	private int cursor, groupBlocks;
	private boolean failed;
	public float yaw = -32, pitch = 22, zoom = 1, panX, panY;
	private record Layer(RenderType type, NativeCachedMesh vertices) { }
	private static final class Builder implements AutoCloseable {
		final ByteBufferBuilder storage = new ByteBufferBuilder(256 * 1024);
		final BufferBuilder buffer;
		Builder(RenderType type) { buffer = new BufferBuilder(storage, type.mode(), type.format()); }
		@Override public void close() { storage.close(); }
	}
	public void set(Blueprint value) {
		if (blueprint == value) return;
		releaseGeometry(); blueprint = value; cursor = groupBlocks = 0; failed = false; reset();
		if (value == null || MC.level == null) { level = null; return; }
		try {
			List<BlockState> palette = new ArrayList<>();
			var registry = MC.level.registryAccess().lookupOrThrow(Registries.BLOCK);
			for (var state : value.palette()) palette.add(NbtUtils.readBlockState(registry, state));
			level = new PreviewLevel(value, palette);
		} catch (Exception failure) { failed = true; level = null; CheatProgram.LOGGER.warn("Building preview palette could not be opened", failure); }
	}
	public void reset() { yaw = -32; pitch = 22; zoom = 1; panX = panY = 0; }
	public int renderedBlocks() { return cursor; }
	public boolean failed() { return failed; }
	private VertexConsumer buffer(RenderType type) { return builders.computeIfAbsent(type, Builder::new).buffer; }
	private void bake() {
		if (level == null || failed || cursor >= blueprint.count()) return;
		long deadline = System.nanoTime() + 4_000_000L; int work = 0;
		var renderer = com.cheatprogram.compat.NativeBlockModel261.INSTANCE; PoseStack poses = new PoseStack(); MultiBufferSource source = this::buffer;
		try {
			while (cursor < blueprint.count() && work++ < 768 && System.nanoTime() < deadline) {
				int x = cursor % blueprint.width(), y = cursor / (blueprint.width() * blueprint.depth()), z = cursor / blueprint.width() % blueprint.depth();
				BlockPos position = new BlockPos(x, y, z); BlockState state = level.getBlockState(position);
				if (!state.isAir()) {
					poses.pushPose(); poses.translate(x, y, z);
					if (state.getRenderShape() == RenderShape.MODEL) {
						renderer.renderBatched(state, position, level, poses, source);
					}
					BlockEntity entity = level.getBlockEntity(position);
					if (entity != null) {
						var entityRenderer = MC.getBlockEntityRenderDispatcher().getRenderer(entity);
						if (entityRenderer != null) com.cheatprogram.compat.NativeBlockEntityBaker.bake(entityRenderer, entity, poses, source);
						else if (state.getRenderShape() != RenderShape.MODEL && state.getRenderShape() != RenderShape.INVISIBLE)
							renderer.renderSingleBlock(state, poses, source, 15728880, OverlayTexture.NO_OVERLAY);
					} else if (state.getRenderShape() != RenderShape.MODEL && state.getRenderShape() != RenderShape.INVISIBLE)
						renderer.renderSingleBlock(state, poses, source, 15728880, OverlayTexture.NO_OVERLAY);
					poses.popPose();
					FluidState fluid = state.getFluidState();
					if (!fluid.isEmpty()) {
						VertexConsumer consumer = buffer(MC.getModelManager().getFluidStateModelSet().get(fluid).layer().translucent() ? Sheets.translucentBlockSheet() : Sheets.cutoutBlockSheet());
						renderer.renderLiquid(position, level, new OffsetConsumer(consumer, x - (x & 15), y - (y & 15), z - (z & 15)), state, fluid);
					}
				}
				cursor++; groupBlocks++;
				if (groupBlocks >= 4096) { flushBuilders(); groupBlocks = 0; }
			}
			if (cursor == blueprint.count()) flushBuilders();
		} catch (Exception failure) { failed = true; CheatProgram.LOGGER.warn("Building preview model could not be rendered", failure); }
	}
	private void flushBuilders() {
		for (var entry : builders.entrySet()) {
			Builder builder = entry.getValue(); MeshData mesh = builder.buffer.build();
			if (mesh != null) {
				NativeCachedMesh vertices = new NativeCachedMesh();
				vertices.upload(mesh);  layers.add(new Layer(entry.getKey(), vertices));
			}
			builder.close();
		}
		builders.clear();
	}
	/**
	 * Observation-only placement ghost. Geometry stays in blueprint-local block coordinates;
	 * the only transform is the actual placement origin relative to the current camera.
	 * This deliberately ignores the screen preview's orbit, zoom and pan controls.
	 */
	public void drawWorld(Render3D renderer, BlockPos origin, float opacity) {
		if (blueprint == null || level == null || MC.level == null || origin == null || failed) return;
		float alpha = Math.max(0, Math.min(1, opacity));
		if (alpha <= 0) return;
		SavedState previous = new SavedState();
		try {
			bake();
			if (layers.isEmpty() || failed) return;
			Matrix4f model = renderer.viewMatrix().translate(
					(float) (origin.getX() - renderer.camera.x),
					(float) (origin.getY() - renderer.camera.y),
					(float) (origin.getZ() - renderer.camera.z));
			Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
			RenderSystem.setShaderFog(FogParameters.NO_FOG);
			
			// Original opaque/cutout surfaces first, then glass/water. All layers become an
			// alpha blended ghost without writing depth into the player's real scene.
			for (int pass = 0; pass < 2; pass++) for (Layer layer : layers) {
				if (layer.type().sortOnUpload() != (pass == 1)) continue;
				layer.vertices().draw(layer.type(),model,projection,null,alpha,false,false);
			}
		} catch (Exception failure) {
			failed = true; CheatProgram.LOGGER.warn("Building world preview could not be drawn", failure);
		} finally { previous.restore(); }
	}
	public void draw(GuiGraphicsExtractor graphics, float x, float y, float width, float height) {
		if (blueprint == null || level == null || width <= 0 || height <= 0 || failed) return;
		bake(); int pixelsX = Math.max(1, Math.min(2048, Math.round(width))), pixelsY = Math.max(1, Math.min(2048, Math.round(height)));
		// Allocation/resize/clear also changes the framebuffer and viewport, so protect those calls too.
		SavedState previous = new SavedState();
		try {
			try {
				RenderSystem.disableScissor();
				if (target == null) { target = new TextureTarget("Cheat Program offscreen", pixelsX, pixelsY, true); com.cheatprogram.compat.gl.Framebuffers.setClearColor(target, 0, 0, 0, 0); }
				else if (target.width != pixelsX || target.height != pixelsY) target.resize(pixelsX, pixelsY);
				RenderSystem.depthMask(true); RenderSystem.colorMask(true, true, true, true);
				com.cheatprogram.compat.gl.Framebuffers.clear(target); com.cheatprogram.compat.gl.Framebuffers.bindWrite(target, true);
				RenderSystem.enableDepthTest(); RenderSystem.enableCull(); RenderSystem.enableBlend(); RenderSystem.defaultBlendFunc();
				RenderSystem.setShaderFog(FogParameters.NO_FOG); RenderSystem.setShaderColor(1, 1, 1, 1); 
				float aspect = pixelsX / (float) pixelsY;
				Matrix4f projection = new Matrix4f().setOrtho(-aspect, aspect, -1, 1, -10000, 10000);
				RenderSystem.setProjectionMatrix(projection, com.mojang.blaze3d.ProjectionType.ORTHOGRAPHIC);
				float diagonal = (float) Math.sqrt((double) blueprint.width() * blueprint.width() + (double) blueprint.height() * blueprint.height() + (double) blueprint.depth() * blueprint.depth());
				float scale = 1.6f * Math.max(0.05f, Math.min(25, zoom)) / Math.max(1, diagonal);
				Matrix4f model = new Matrix4f().translate(panX, -panY, 0).rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw))
						.scale(scale).translate(-blueprint.width() / 2f, -blueprint.height() / 2f, -blueprint.depth() / 2f);
				RenderSystem.getModelViewStack().identity();
				// Opaque geometry establishes depth before glass, water and other translucent layers.
				for (int pass = 0; pass < 2; pass++) for (Layer layer : layers) {
					if (layer.type().sortOnUpload() != (pass == 1)) continue;
					layer.vertices().draw(layer.type(),model,projection,target,1,true,false);
				}
			} finally { previous.restore(); }
			// The ONLY operation that draws the preview into its caller's framebuffer is this bounded quad.
			R2D.applyState(); R2D.pushClip(x, y, width, height);
			try {
				previous.intersectClip();
				float guiScale = (float) MC.getWindow().getGuiScale(); Matrix4f pose = com.cheatprogram.compat.GuiBridge.pose(graphics);
				BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
				int color = R2D.fade(0xFFFFFFFF);
				quad.addVertex(pose, x / guiScale, y / guiScale, 0).setUv(0, 1).setColor(color);
				quad.addVertex(pose, x / guiScale, (y + height) / guiScale, 0).setUv(0, 0).setColor(color);
				quad.addVertex(pose, (x + width) / guiScale, (y + height) / guiScale, 0).setUv(1, 0).setColor(color);
				quad.addVertex(pose, (x + width) / guiScale, y / guiScale, 0).setUv(1, 1).setColor(color);
				RenderSystem.setShaderColor(1, 1, 1, 1); RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);
				RenderSystem.setShaderTexture(0, com.cheatprogram.compat.gl.Framebuffers.color(target)); BufferUploader.drawWithShader(quad.buildOrThrow());
			} finally { R2D.popClip(); }
		} catch (Exception failure) {
			failed = true; CheatProgram.LOGGER.warn("Building preview could not be drawn", failure);
		} finally { previous.restore(); }
	}
	private static final class SavedState {
		final int[] viewport = new int[4], scissor = new int[4], colorMask = new int[4];
		final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		final boolean clipped = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
		final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
		final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
		final Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix()), textureMatrix = new Matrix4f(RenderSystem.getTextureMatrix());
		final com.mojang.blaze3d.ProjectionType projectionType = RenderSystem.getProjectionType();
		final Matrix4fStack modelView = copyStack(RenderSystem.getModelViewStack());
		final FogParameters fog = RenderSystem.getShaderFog(); final float[] color = RenderSystem.getShaderColor().clone();
		final int texture0 = RenderSystem.getShaderTexture(0), texture1 = RenderSystem.getShaderTexture(1), texture2 = RenderSystem.getShaderTexture(2);
		final CompiledShaderProgram shader = RenderSystem.getShader();
		SavedState() { GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport); GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor); GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, colorMask); }
		void intersectClip() {
			if (!clipped) return;
			int[] current = new int[4]; GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, current);
			int x = Math.max(current[0], scissor[0]), y = Math.max(current[1], scissor[1]);
			int right = Math.min(current[0] + current[2], scissor[0] + scissor[2]), top = Math.min(current[1] + current[3], scissor[1] + scissor[3]);
			RenderSystem.enableScissor(x, y, Math.max(0, right - x), Math.max(0, top - y));
		}
		void restore() {
			// Use Minecraft's cached GL wrapper, rather than raw glBindFramebuffer, so subsequent layer
			// binds cannot be skipped because the cache disagrees with the actual draw/read target.
			bindOutput();
			RenderSystem.setProjectionMatrix(projection, projectionType); restoreStack(modelView); RenderSystem.setTextureMatrix(textureMatrix);
			RenderSystem.setShaderFog(fog); RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
			RenderSystem.setShaderTexture(0, texture0); RenderSystem.setShaderTexture(1, texture1); RenderSystem.setShaderTexture(2, texture2);
			if (shader == null) RenderSystem.clearShader(); else RenderSystem.setShader(shader);
			if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
			if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
			if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
			RenderSystem.depthFunc(depthFunction); RenderSystem.depthMask(depthMask); RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
			RenderSystem.colorMask(colorMask[0] != 0, colorMask[1] != 0, colorMask[2] != 0, colorMask[3] != 0);
			if (clipped) RenderSystem.enableScissor(scissor[0], scissor[1], scissor[2], scissor[3]); else RenderSystem.disableScissor();
		}
		void bindOutput() {
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
			GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
			RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
		}
	}
	private static Matrix4fStack copyStack(Matrix4fStack stack) {
		try { return (Matrix4fStack) stack.clone(); }
		catch (CloneNotSupportedException impossible) { throw new IllegalStateException("Model-view stack cannot be copied", impossible); }
	}
	private static void restoreStack(Matrix4fStack saved) {
		Matrix4fStack copy = copyStack(saved); List<Matrix4f> matrices = new ArrayList<>();
		while (true) { matrices.add(new Matrix4f(copy)); try { copy.popMatrix(); } catch (IllegalStateException bottom) { break; } }
		Matrix4fStack active = RenderSystem.getModelViewStack(); active.clear().set(matrices.get(matrices.size() - 1));
		for (int index = matrices.size() - 2; index >= 0; index--) { active.pushMatrix(); active.set(matrices.get(index)); }
	}
	private void releaseGeometry() {
		for (Layer layer : layers) layer.vertices().close(); layers.clear();
		for (Builder builder : builders.values()) builder.close(); builders.clear();
	}
	@Override public void close() { releaseGeometry(); if (target != null) { target.destroyBuffers(); target = null; } blueprint = null; level = null; }
	private static final class PreviewLevel implements BlockAndTintGetter {
		final Blueprint blueprint; final List<BlockState> states;
		final Map<BlockPos, BlockEntity> entities = new LinkedHashMap<>();
		PreviewLevel(Blueprint blueprint, List<BlockState> states) { this.blueprint = blueprint; this.states = states; }
		@Override public BlockState getBlockState(BlockPos pos) {
			if (pos.getX() < 0 || pos.getY() < 0 || pos.getZ() < 0 || pos.getX() >= blueprint.width() || pos.getY() >= blueprint.height() || pos.getZ() >= blueprint.depth()) return Blocks.AIR.defaultBlockState();
			return states.get(blueprint.blocks()[(pos.getY() * blueprint.depth() + pos.getZ()) * blueprint.width() + pos.getX()]);
		}
		@Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
		@Override public BlockEntity getBlockEntity(BlockPos pos) {
			BlockState state = getBlockState(pos);
			if (!(state.getBlock() instanceof EntityBlock block) || MC.level == null) return null;
			if (entities.containsKey(pos)) return entities.get(pos);
			BlockEntity entity = block.newBlockEntity(pos, state);
			if (entity != null) {
				var data = blueprint.blockEntities().get((pos.getY() * blueprint.depth() + pos.getZ()) * blueprint.width() + pos.getX());
				if (data != null) entity.loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, MC.level.registryAccess(), data.copy()));
				// These private, unticked copies are never inserted into the live world. Renderers only read its context.
				entity.setLevel(MC.level);
			}
			entities.put(pos.immutable(), entity); return entity;
		}
		@Override public int getHeight() { return blueprint.height(); }
		@Override public int getMinY() { return 0; }
		@Override public LevelLightEngine getLightEngine() { return MC.level.getLightEngine(); }
		@Override public int getBrightness(LightLayer layer, BlockPos pos) { return 15; }
		@Override public int getRawBrightness(BlockPos pos, int darkening) { return 15; }
		@Override public net.minecraft.world.level.CardinalLighting cardinalLighting() { return new net.minecraft.world.level.CardinalLighting(.55f,1,.8f,.8f,.65f,.65f); }
		@Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
			return MC.level == null || MC.player == null ? 0xFFFFFF : resolver.getColor(MC.level.getBiome(MC.player.blockPosition()).value(), pos.getX(), pos.getZ());
		}
	}
	private record OffsetConsumer(VertexConsumer wrapped, float x, float y, float z) implements VertexConsumer {
		@Override public VertexConsumer addVertex(float x, float y, float z) { wrapped.addVertex(x + this.x, y + this.y, z + this.z); return this; }
		@Override public VertexConsumer setColor(int r, int g, int b, int a) { wrapped.setColor(r, g, b, a); return this; }
		@Override public VertexConsumer setUv(float u, float v) { wrapped.setUv(u, v); wrapped.setUv1(0, 10); return this; }
		@Override public VertexConsumer setUv1(int u, int v) { wrapped.setUv1(u, v); return this; }
		@Override public VertexConsumer setUv2(int u, int v) { wrapped.setUv2(240, 240); return this; }
		@Override public VertexConsumer setNormal(float x, float y, float z) { wrapped.setNormal(x, y, z); return this; }
        @Override public VertexConsumer setLineWidth(float width) { wrapped.setLineWidth(width); return this; }
        @Override public VertexConsumer setColor(int argb) { return setColor(argb >> 16 & 255, argb >> 8 & 255, argb & 255, argb >>> 24); }
	}
}
