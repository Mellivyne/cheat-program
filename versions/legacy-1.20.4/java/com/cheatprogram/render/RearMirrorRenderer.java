package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.hud.HudRenderer;
import com.cheatprogram.mixin.RearMirrorLevelRendererAccessor;
import com.cheatprogram.module.legit.RearViewMirror;
import com.cheatprogram.compat.legacy.LegacyProjectionType;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;


import com.cheatprogram.compat.legacy.LegacyRenderSystem;
import com.cheatprogram.compat.legacy.LegacyBufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.cheatprogram.compat.legacy.LegacyTesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import com.cheatprogram.compat.legacy.LegacyCoreShaders;
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
		if (!LegacyRenderSystem.isOnRenderThread()) { LegacyRenderSystem.recordRenderCall(RearMirrorRenderer::release); return; }
		if (target != null) { target.destroyBuffers(); target = null; MC.getMainRenderTarget().bindWrite(true); }
		world = null; ready = false; nextCapture = 0;
	}

	/** Runs before the normal scene, which then fully re-establishes its normal render state. */
	public static void capture(LevelRenderer renderer, float partial,long frameTime,Camera mainCamera,GameRenderer gameRenderer,net.minecraft.client.renderer.LightTexture light) {
		RearViewMirror module = ModuleManager.get(RearViewMirror.class);
		if (rendering || module == null || !module.isEnabled() || MC.level == null || MC.player == null || !mainCamera.isInitialized()) return;
		long now = System.nanoTime();
		if (now < nextCapture) return;
		nextCapture = now + 1_000_000_000L / module.refreshRate();
		RenderTarget main = MC.getMainRenderTarget();
		int[] viewport = new int[4]; GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
		int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		Matrix4f previousProjection = new Matrix4f(LegacyRenderSystem.getProjectionMatrix());
		LegacyProjectionType previousProjectionType = LegacyRenderSystem.getProjectionType();
		Matrix4f previousModelView = new Matrix4f(LegacyRenderSystem.getModelViewStack());
		Matrix4fStack previousStack = snapshotModelView();

		var previousFog = LegacyRenderSystem.getShaderFog();
		float[] color = LegacyRenderSystem.getShaderColor().clone();
		int texture0 = LegacyRenderSystem.getShaderTexture(0), texture1 = LegacyRenderSystem.getShaderTexture(1);
		var accessor = (RearMirrorLevelRendererAccessor)renderer;
		var previousFrustum = accessor.cheatprogram$mirrorFrustum();
		RenderTarget previousOutline = accessor.cheatprogram$mirrorOutlineTarget();
		try {
			float scale = HudRenderer.hudScale();
			int width = Math.max(128, Math.min(960, Math.round(module.width() * scale)));
			int height = Math.max(80, Math.min(600, Math.round(module.height() * scale)));
			if (target == null) { target = new TextureTarget(width, height, true, Minecraft.ON_OSX); target.setFilterMode(GL11.GL_LINEAR); }
			else if (target.width != width || target.height != height) { target.resize(width, height,Minecraft.ON_OSX); target.setFilterMode(GL11.GL_LINEAR); ready = false; }

			CAMERA.setup(MC.level, mainCamera.getEntity(), false, false, partial);
			CAMERA.rear(mainCamera.getPosition(), mainCamera.getYRot() + 180, mainCamera.getXRot());
			Matrix4f view = new Matrix4f().rotation(CAMERA.rotation().conjugate(new Quaternionf()));
			Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(module.fieldOfView()), (float)width / height,
					.05f, Math.max(64, gameRenderer.getRenderDistance() * 4));
			rendering = true; world = MC.level;
			// Vanilla's shared outline framebuffer has the main window's dimensions. Its clear()
			// would reset the mirror viewport before drawing entities; restore it for the main scene.
			accessor.cheatprogram$mirrorOutlineTarget(null);
			LegacyRenderSystem.setProjectionMatrix(projection, LegacyProjectionType.PERSPECTIVE);
			com.mojang.blaze3d.vertex.PoseStack cameraPose=new com.mojang.blaze3d.vertex.PoseStack();cameraPose.last().pose().set(view);renderer.prepareCullFrustum(cameraPose,CAMERA.getPosition(),projection);
			target.bindWrite(true);
			// Calling LevelRenderer directly avoids hands, screens, HUD and module-render recursion.
			renderer.renderLevel(cameraPose,partial,frameTime,false,CAMERA,gameRenderer,light,projection);
			ready = true;
		} catch (Throwable error) {
			ready = false;
			// LevelRenderer's normal epilogue is not finally-protected in 1.21.4. Never let
			// failed mirror handles or an extra model-view push contaminate the normal scene.


			restoreModelView(previousStack);
			CheatProgram.LOGGER.error("Rear camera rendering failed; disabling rear-view mirror", error);
			module.setEnabled(false);
		} finally {
			rendering = false;

			accessor.cheatprogram$mirrorOutlineTarget(previousOutline);
			accessor.cheatprogram$mirrorFrustum(previousFrustum);
			LegacyRenderSystem.setProjectionMatrix(previousProjection, previousProjectionType);
			LegacyRenderSystem.getModelViewStack().set(previousModelView);
			LegacyRenderSystem.setShaderFog(previousFog);
			LegacyRenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
			LegacyRenderSystem.setShaderTexture(0, texture0); LegacyRenderSystem.setShaderTexture(1, texture1);
			GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
			GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
			LegacyRenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
			// Minecraft's cached draw target remains the real main target outside this scope.
			main.bindWrite(false);
			LegacyRenderSystem.enableDepthTest(); LegacyRenderSystem.depthMask(true); LegacyRenderSystem.enableCull();
		}
	}

	private static Matrix4fStack snapshotModelView() {
		try { return (Matrix4fStack)LegacyRenderSystem.getModelViewStack().clone(); }
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
		Matrix4fStack active = LegacyRenderSystem.getModelViewStack();
		active.clear().set(matrices.get(matrices.size() - 1));
		for (int i = matrices.size() - 2; i >= 0; i--) { active.pushMatrix(); active.set(matrices.get(i)); }
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
		if (ready && target != null) image(inner, target.getColorTextureId(), module.horizontalFlip());
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
		LegacyRenderSystem.setShader(LegacyCoreShaders.POSITION_COLOR);
		var buffer = LegacyTesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
		float scale = (float)MC.getWindow().getGuiScale();
		for (int index : new int[]{0,3,2,1}) buffer.addVertex(corners[index][0] / scale, corners[index][1] / scale, 0).setColor(R2D.fade(color));
		com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(buffer.buildOrThrow());
	}
	private static void image(float[][] corners, int texture, boolean flip) {
		LegacyRenderSystem.setShader(LegacyCoreShaders.POSITION_TEX_COLOR); LegacyRenderSystem.setShaderTexture(0, texture);
		LegacyRenderSystem.setShaderColor(1, 1, 1, 1);
		// Vanilla sky/fog can leave zero alpha in the world target; copy its RGB opaquely.
		LegacyRenderSystem.disableBlend();
		LegacyBufferBuilder buffer = LegacyTesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		float scale = (float)MC.getWindow().getGuiScale();
		for (int index : new int[]{0,3,2,1}) {
			float u = index == 0 || index == 3 ? 0 : 1;
			if (flip) u = 1 - u;
			float v = index < 2 ? 1 : 0; // GL render-target textures originate at the bottom.
			buffer.addVertex(corners[index][0] / scale, corners[index][1] / scale, 0).setUv(u, v).setColor(R2D.fade(0xFFFFFFFF));
		}
		com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(buffer.buildOrThrow());
		LegacyRenderSystem.enableBlend();
	}
	private static final class RearCamera extends Camera {
		void rear(Vec3 position, float yaw, float pitch) { setPosition(position); setRotation(yaw, pitch); }
	}
}
