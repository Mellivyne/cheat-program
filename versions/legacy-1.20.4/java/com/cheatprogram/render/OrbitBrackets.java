package com.cheatprogram.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.cheatprogram.compat.legacy.LegacyRenderSystem;
import com.cheatprogram.compat.legacy.LegacyBufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.cheatprogram.compat.legacy.LegacyTesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.cheatprogram.compat.legacy.LegacyCoreShaders;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

/** White, lightly extruded corner ribbons anchored around the entity's interpolated world centre. */
public final class OrbitBrackets {
	private static final int SEGMENTS = 16;
	private static final double[] PX = new double[SEGMENTS + 1], PY = new double[SEGMENTS + 1];
	private static final double[] NX = new double[SEGMENTS + 1], NY = new double[SEGMENTS + 1];
	static {
		// A rounded > bracket: two straight arms meet along a quadratic rounded bend.
		for (int index = 0; index <= SEGMENTS; index++) {
			if (index <= 4) {
				double t = index / 4.0;
				PX[index] = -0.26 + 0.195 * t; PY[index] = 0.26 - 0.195 * t;
			} else if (index <= 12) {
				double t = (index - 4) / 8.0, u = 1 - t;
				PX[index] = -0.065 * (u * u + t * t);
				PY[index] = 0.065 * (u * u - t * t);
			} else {
				double t = (index - 12) / 4.0;
				PX[index] = -0.065 - 0.195 * t; PY[index] = -0.065 - 0.195 * t;
			}
		}
		for (int index = 0; index <= SEGMENTS; index++) {
			int previous = Math.max(0, index - 1), next = Math.min(SEGMENTS, index + 1);
			double dx = PX[next] - PX[previous], dy = PY[next] - PY[previous], length = Math.hypot(dx, dy);
			NX[index] = -dy / length; NY[index] = dx / length;
		}
	}
	private OrbitBrackets() { }

	public static void draw(Render3D renderer, Vec3 anchor, double radius, double phase, double opacity,
			double thickness, double glowStrength, double glowWidth, boolean throughWalls) {
		if (radius <= 0 || opacity <= 0) return;
		Vector3f right = new Vector3f(1, 0, 0).rotate(renderer.cameraObject.rotation());
		Vector3f up = new Vector3f(0, 1, 0).rotate(renderer.cameraObject.rotation());
		Vector3f towardCamera = new Vector3f(0, 0, 1).rotate(renderer.cameraObject.rotation());
		Basis basis = new Basis(anchor.subtract(renderer.camera), new Vec3(right.x(), right.y(), right.z()),
				new Vec3(up.x(), up.y(), up.z()), new Vec3(towardCamera.x(), towardCamera.y(), towardCamera.z()), radius);
		Corner[] corners = new Corner[4];
		for (int index = 0; index < 4; index++) corners[index] = basis.corner(phase + index * Math.PI / 2);
		State saved = new State();
		renderer.begin();
		try {
			// Every vertex above is camera-relative exactly once. begin supplies the matching world view,
			// independent of the preceding module's shader, colour and model-view transform.
			if (throughWalls) LegacyRenderSystem.disableDepthTest(); else LegacyRenderSystem.enableDepthTest();
			LegacyRenderSystem.depthFunc(GL11.GL_LEQUAL);
			LegacyRenderSystem.depthMask(false);
			LegacyRenderSystem.setShaderColor(1, 1, 1, 1);
			LegacyRenderSystem.setShader(LegacyCoreShaders.POSITION_COLOR);
			double halfWidth = 0.024 * thickness;
			if (glowStrength > 0) {
				LegacyRenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
				LegacyBufferBuilder halo = LegacyTesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
				for (int corner = 0; corner < 4; corner++) {
					Corner ribbon = corners[corner];
					for (int layer = 0; layer < 6; layer++) {
						double width = halfWidth + 0.065 * glowWidth * (1 - layer / 6.0);
						int color = color(opacity * glowStrength * (0.018 + layer * 0.012));
						for (int segment = 0; segment < SEGMENTS; segment++) {
							quad(halo, ribbon.point(segment, width, 0), ribbon.point(segment + 1, width, 0),
									ribbon.point(segment + 1, -width, 0), ribbon.point(segment, -width, 0), color);
						}
					}
				}
				com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(halo.buildOrThrow());
			}
			LegacyRenderSystem.defaultBlendFunc();
			LegacyBufferBuilder solid = LegacyTesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
			for (int corner = 0; corner < 4; corner++) {
				Corner ribbon = corners[corner];
				int face = color(opacity), side = color(opacity * 0.85);
				double depth = 0.0025;
				for (int segment = 0; segment < SEGMENTS; segment++) {
					// A shallow white edge retains depth without a dark bevel or a bulky plastic sidewall.
					quad(solid, ribbon.point(segment, -halfWidth, -depth), ribbon.point(segment + 1, -halfWidth, -depth),
								ribbon.point(segment + 1, halfWidth, -depth), ribbon.point(segment, halfWidth, -depth), side);
					for (int sign = -1; sign <= 1; sign += 2) {
						quad(solid, ribbon.point(segment, sign * halfWidth, depth), ribbon.point(segment + 1, sign * halfWidth, depth),
									ribbon.point(segment + 1, sign * halfWidth, -depth), ribbon.point(segment, sign * halfWidth, -depth), side);
					}
					quad(solid, ribbon.point(segment, halfWidth, depth), ribbon.point(segment + 1, halfWidth, depth),
								ribbon.point(segment + 1, -halfWidth, depth), ribbon.point(segment, -halfWidth, depth), face);
				}
				for (int end = 0; end <= SEGMENTS; end += SEGMENTS) quad(solid,
							ribbon.point(end, -halfWidth, depth), ribbon.point(end, halfWidth, depth),
							ribbon.point(end, halfWidth, -depth), ribbon.point(end, -halfWidth, -depth), side);
			}
			com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(solid.buildOrThrow());
		} finally {
			try { renderer.end(); } finally { saved.restore(); }
		}
	}
	private static int color(double alpha) {
		return Math.min(255, Math.max(0, (int) Math.round(alpha * 255))) << 24 | 0xFFFFFF;
	}
	private record Basis(Vec3 origin, Vec3 right, Vec3 up, Vec3 towardCamera, double radius) {
		Corner corner(double angle) {
			double cosine = Math.cos(angle), sine = Math.sin(angle);
			return new Corner(origin.add(towardCamera.scale(sine * 0.035 * radius)),
					right.scale(cosine * radius).add(up.scale(sine * radius)),
					right.scale(-sine * radius).add(up.scale(cosine * radius)), towardCamera.scale(radius));
		}
	}
	private static final class State {
		final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND);
		final boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		final int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC), program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
		final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
		final float lineWidth = GL11.glGetFloat(GL11.GL_LINE_WIDTH);
		final float[] shaderColor = LegacyRenderSystem.getShaderColor().clone();
		final ShaderInstance shader = LegacyRenderSystem.getShader();
		void restore() {
			LegacyRenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
			if (shader == null) LegacyRenderSystem.clearShader(); else LegacyRenderSystem.setShader(shader);
			LegacyRenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
			LegacyRenderSystem.depthFunc(depthFunction); LegacyRenderSystem.depthMask(depthWrite); LegacyRenderSystem.lineWidth(lineWidth);
			if (blend) LegacyRenderSystem.enableBlend(); else LegacyRenderSystem.disableBlend();
			if (depth) LegacyRenderSystem.enableDepthTest(); else LegacyRenderSystem.disableDepthTest();
			if (cull) LegacyRenderSystem.enableCull(); else LegacyRenderSystem.disableCull();
			GlStateManager._glUseProgram(program);
		}
	}
	private record Corner(Vec3 origin, Vec3 along, Vec3 across, Vec3 depthAxis) {
		Vec3 point(int index, double width, double depth) {
			double x = 1 + PX[index] + NX[index] * width, y = PY[index] + NY[index] * width;
			return new Vec3(origin.x + along.x * x + across.x * y + depthAxis.x * depth,
					origin.y + along.y * x + across.y * y + depthAxis.y * depth,
					origin.z + along.z * x + across.z * y + depthAxis.z * depth);
		}
	}
	private static void quad(LegacyBufferBuilder buffer, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
		vertex(buffer, a, color); vertex(buffer, b, color); vertex(buffer, c, color); vertex(buffer, d, color);
	}
	private static void vertex(LegacyBufferBuilder buffer, Vec3 point, int color) {
		buffer.addVertex((float) point.x, (float) point.y, (float) point.z).setColor(color);
	}
}
