package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.hud.HudRenderer;
import com.cheatprogram.mixin.RearMirrorLevelRendererAccessor;
import com.cheatprogram.module.legit.RearViewMirror;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.ResourceDescriptor;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.cheatprogram.compat.gl.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.cheatprogram.compat.gl.CoreShaders;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

/** Renders an actual independent rear camera, never a mirrored copy of the front buffer. */
public final class RearMirrorRenderer {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final RearCamera CAMERA = new RearCamera();
	private static TextureTarget target;
	private static ClientLevel world;
	private static boolean rendering, ready;
	private static long nextCapture;
	private RearMirrorRenderer() { }
	public static boolean isRendering() { return rendering; }
	public static RenderTarget activeTarget() { return rendering ? target : null; }
	public static void checkLifecycle() {
		RearViewMirror module = ModuleManager.get(RearViewMirror.class);
		if (world != MC.level || MC.level == null || module == null || !module.isEnabled()) release();
	}
	public static void release() {
		if (!RenderSystem.isOnRenderThread()) { RenderSystem.recordRenderCall(RearMirrorRenderer::release); return; }
		if (target != null) { target.destroyBuffers(); target = null; com.cheatprogram.compat.gl.Framebuffers.bindWrite(MC.gameRenderer.mainRenderTarget(), true); }
		world = null; ready = false; nextCapture = 0;
	}

	/** Runs before the normal scene, which then fully re-establishes its normal render state. */
	public static void capture(LevelRenderer renderer, GraphicsResourceAllocator allocator, DeltaTracker delta, Camera mainCamera, GameRenderer gameRenderer, com.mojang.blaze3d.buffers.GpuBufferSlice fog, org.joml.Vector4f fogColor, boolean sky) {
		RearViewMirror module = ModuleManager.get(RearViewMirror.class);
		if (rendering || module == null || !module.isEnabled() || MC.level == null || MC.player == null || !mainCamera.isInitialized()) return;
		long now = System.nanoTime();
		if (now < nextCapture) return;
		nextCapture = now + 1_000_000_000L / module.refreshRate();
		RenderTarget main = MC.gameRenderer.mainRenderTarget();
		int[] viewport = new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
		int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
		ProjectionType previousProjectionType = RenderSystem.getProjectionType();
		Matrix4f previousModelView = new Matrix4f(RenderSystem.getModelViewStack());
		Matrix4fStack previousStack = snapshotModelView();
		CaptureResources captureResources = new CaptureResources(allocator);
		var previousFog = RenderSystem.getShaderFog();
		float[] color = RenderSystem.getShaderColor().clone();
		int texture0 = RenderSystem.getShaderTexture(0), texture1 = RenderSystem.getShaderTexture(1);
		var accessor = (RearMirrorLevelRendererAccessor)renderer;
		RenderTarget previousOutline = accessor.cheatprogram$mirrorOutlineTarget();
        var levelState=gameRenderer.gameRenderState().levelRenderState;
        var previousCameraState=levelState.cameraRenderState;
        boolean rearExtracted=false;
		try {
			float scale = HudRenderer.hudScale();
			int width = Math.max(128, Math.min(960, Math.round(module.width() * scale)));
			int height = Math.max(80, Math.min(600, Math.round(module.height() * scale)));
			if (target == null) { target = new TextureTarget("Cheat Program offscreen",width,height,true,com.mojang.blaze3d.GpuFormat.RGBA8_UNORM); com.cheatprogram.compat.gl.Framebuffers.setFilterMode(target, GL11.GL_LINEAR); }
			else if (target.width != width || target.height != height) { target.resize(width, height); com.cheatprogram.compat.gl.Framebuffers.setFilterMode(target, GL11.GL_LINEAR); ready = false; }
			float partial = delta.getGameTimeDeltaPartialTick(true);
			CAMERA.setLevel(MC.level); CAMERA.setEntity(mainCamera.entity()); CAMERA.update(delta);
			CAMERA.rear(mainCamera.position(), mainCamera.yRot() + 180, mainCamera.xRot());
			Matrix4f view = new Matrix4f().rotation(CAMERA.rotation().conjugate(new Quaternionf()));
			Matrix4f projection = com.cheatprogram.compat.NativeProjection262.perspective((float)Math.toRadians(module.fieldOfView()), (float)width / height,
					.05f, Math.max(64, (MC.options.getEffectiveRenderDistance() * 16f) * 4));
			rendering = true; world = MC.level;
			// Vanilla's shared outline framebuffer has the main window's dimensions. Its clear()
			// would reset the mirror viewport before drawing entities; restore it for the main scene.
			accessor.cheatprogram$mirrorOutlineTarget(null);
			RenderSystem.setProjectionMatrix(projection, ProjectionType.PERSPECTIVE);
			com.cheatprogram.compat.gl.Framebuffers.bindWrite(target, true);
			// Calling LevelRenderer directly avoids hands, screens, HUD and module-render recursion.
			var rearState=new net.minecraft.client.renderer.state.level.CameraRenderState();
            CAMERA.extractRenderState(rearState,partial);
            rearState.projectionMatrix.set(projection);
            rearState.viewRotationMatrix.set(view);
            rearState.fogData=previousCameraState.fogData;
            var frustum=new net.minecraft.client.renderer.culling.Frustum(view,projection);
            frustum.prepare(CAMERA.position().x,CAMERA.position().y,CAMERA.position().z);
            CAMERA.getCullFrustum().set(frustum); rearState.cullFrustum.set(frustum);
            levelState.reset(); levelState.cameraRenderState=rearState; rearExtracted=true;
            
            MC.levelExtractor.extract(delta,CAMERA,partial);
            renderer.render(captureResources,delta,false,rearState,view,fog,fogColor,sky);
			ready = true;
		} catch (Throwable error) {
			ready = false;
			// LevelRenderer's normal epilogue is not finally-protected in 1.21.4. Never let
			// failed mirror handles or an extra model-view push contaminate the normal scene.
			accessor.cheatprogram$mirrorTargets().clear();
			restoreModelView(previousStack);
			CheatProgram.LOGGER.error("Rear camera rendering failed; disabling rear-view mirror", error);
			module.setEnabled(false);
		} finally {
			rendering = false;
            // Native rendering consumes its extracted world queues. Re-extract the
            // front view, including fresh terrain buffer slices, before vanilla resumes.
            if(rearExtracted){
                levelState.reset(); levelState.cameraRenderState=previousCameraState;
                
                MC.levelExtractor.extract(delta,mainCamera,delta.getGameTimeDeltaPartialTick(false));
            }
			captureResources.releaseRemaining();
			accessor.cheatprogram$mirrorOutlineTarget(previousOutline);
			RenderSystem.setProjectionMatrix(previousProjection, previousProjectionType);
			RenderSystem.getModelViewStack().set(previousModelView);
			RenderSystem.setShaderFog(previousFog);
			RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
			RenderSystem.setShaderTexture(0, texture0); RenderSystem.setShaderTexture(1, texture1);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
			RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
			// Minecraft's cached draw target remains the real main target outside this scope.
			com.cheatprogram.compat.gl.Framebuffers.bindWrite(main, false);
			RenderSystem.enableDepthTest(); RenderSystem.depthMask(true); RenderSystem.enableCull();
		}
	}

	private static Matrix4fStack snapshotModelView() {
		try { return (Matrix4fStack)RenderSystem.getModelViewStack().clone(); }
		catch (CloneNotSupportedException impossible) { throw new IllegalStateException("Model-view stack cannot be copied", impossible); }
	}
	private static void restoreModelView(Matrix4fStack snapshot) {
		// JOML 1.10.8 clone copies both the saved matrices and current depth. Its public
		// pop/push API lets us restore the depth without replacing RenderSystem's stack.
		var matrices = new ArrayList<Matrix4f>();
		while (true) {
			matrices.add(new Matrix4f(snapshot));
			try { snapshot.popMatrix(); } catch (IllegalStateException bottom) { break; }
		}
		Matrix4fStack active = RenderSystem.getModelViewStack();
		active.clear().set(matrices.get(matrices.size() - 1));
		for (int i = matrices.size() - 2; i >= 0; i--) { active.pushMatrix(); active.set(matrices.get(i)); }
	}

	/** FrameGraphBuilder also skips resource releases when a render pass throws. */
	private static final class CaptureResources implements GraphicsResourceAllocator {
		private final GraphicsResourceAllocator delegate;
		private final Map<Object, ResourceDescriptor<?>> outstanding = new IdentityHashMap<>();
		CaptureResources(GraphicsResourceAllocator delegate) { this.delegate = delegate; }
		@Override public <T> T acquire(ResourceDescriptor<T> descriptor) {
			T value = delegate.acquire(descriptor); outstanding.put(value, descriptor); return value;
		}
		@Override public <T> void release(ResourceDescriptor<T> descriptor, T value) {
			outstanding.remove(value); delegate.release(descriptor, value);
		}
		@SuppressWarnings({"rawtypes", "unchecked"})
		void releaseRemaining() {
			for (var entry : outstanding.entrySet()) {
				try { delegate.release((ResourceDescriptor)entry.getValue(), entry.getKey()); }
				catch (Throwable failure) { CheatProgram.LOGGER.error("Failed to release rear-camera frame resource", failure); }
			}
			outstanding.clear();
		}
	}

	public static void draw(RearViewMirror module, HudContext ctx) {
		if (world != MC.level || MC.player == null) return;
		float[] b = module.bounds(ctx.width, ctx.height);
		float x = b[0], y = b[1], w = b[2], h = b[3];
		if (!module.threeDimensional()) ctx.panel(x, y, w, h, 10);
		float[][] outer = plane(module, ctx.c, x, y, w, h, 0);
		float[][] inner = plane(module, ctx.c, x + 4, y + 4, w - 8, h - 8, 0);
		if (module.threeDimensional()) {
			float[][] back = plane(module, ctx.c, x, y, w, h, 5);
			for (int i = 0; i < 4; i++) solid(new float[][]{outer[i], outer[(i+1)%4], back[(i+1)%4], back[i]}, 0x50FFFFFF);
			solid(outer, 0x88FFFFFF);
		}
		if (ready && target != null) image(inner, com.cheatprogram.compat.gl.Framebuffers.color(target), module.horizontalFlip());
		else solid(inner, 0x80101820);
		for (int i = 0; i < 4; i++) solid(new float[][]{outer[i], outer[(i+1)%4], inner[(i+1)%4], inner[i]}, i < 2 ? 0x66FFFFFF : 0x30FFFFFF);
		// A translucent highlight belongs to the mirror surface and moves with its tilt.
		float[][] shine = plane(module, ctx.c, x + 4, y + 4, w - 8, (h - 8) * .12f, 0);
		solid(shine, 0x14FFFFFF);
		R2D.applyState();
	}

	/** Screen-space projection of a genuinely rotated plate, including its shallow glass edge. */
	private static float[][] plane(RearViewMirror module, Canvas c, float x, float y, float w, float h, float depth) {
		float[] signsX = {-1, 1, 1, -1}, signsY = {-1, -1, 1, 1};
		float[][] out = new float[4][2];
		float[] plate = module.bounds(R2D.width() / c.scale, R2D.height() / c.scale);
		float centerX = plate[0] + plate[2] / 2, centerY = plate[1] + plate[3] / 2;
		float rx = module.threeDimensional() ? (float)Math.toRadians(module.tiltX()) : 0;
		float ry = module.threeDimensional() ? (float)Math.toRadians(module.tiltY()) : 0;
		float distance = Math.max(plate[2], plate[3]) * 3.5f;
		for (int i = 0; i < 4; i++) {
			float px = x + (signsX[i] + 1) * w / 2 - centerX, py = y + (signsY[i] + 1) * h / 2 - centerY;
			float rotatedY = py * (float)Math.cos(rx) - depth * (float)Math.sin(rx);
			float z = py * (float)Math.sin(rx) + depth * (float)Math.cos(rx);
			float rotatedX = px * (float)Math.cos(ry) + z * (float)Math.sin(ry);
			z = -px * (float)Math.sin(ry) + z * (float)Math.cos(ry);
			float perspective = distance / (distance + z);
			out[i][0] = c.px(centerX + rotatedX * perspective);
			out[i][1] = c.py(centerY + rotatedY * perspective);
		}
		return out;
	}
	private static void solid(float[][] corners, int color) {
		RenderSystem.setShader(CoreShaders.POSITION_COLOR);
		var buffer = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_COLOR);
		float scale = (float)MC.getWindow().getGuiScale();
		for (int index : new int[]{0,3,2,1}) buffer.addVertex(corners[index][0] / scale, corners[index][1] / scale, 0).setColor(R2D.fade(color));
		BufferUploader.drawWithShader(buffer.buildOrThrow());
	}
	private static void image(float[][] corners, int texture, boolean flip) {
		RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR); RenderSystem.setShaderTexture(0, texture);
		RenderSystem.setShaderColor(1, 1, 1, 1);
		// Vanilla sky/fog can leave zero alpha in the world target; copy its RGB opaquely.
		RenderSystem.disableBlend();
		BufferBuilder buffer = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		float scale = (float)MC.getWindow().getGuiScale();
		for (int index : new int[]{0,3,2,1}) {
			float u = index == 0 || index == 3 ? 0 : 1;
			if (flip) u = 1 - u;
			float v = index < 2 ? 1 : 0; // GL render-target textures originate at the bottom.
			buffer.addVertex(corners[index][0] / scale, corners[index][1] / scale, 0).setUv(u, v).setColor(R2D.fade(0xFFFFFFFF));
		}
		BufferUploader.drawWithShader(buffer.buildOrThrow());
		RenderSystem.enableBlend();
	}
	private static final class RearCamera extends Camera {
		void rear(Vec3 position, float yaw, float pitch) { setPosition(position); setRotation(yaw, pitch); }
	}
}
