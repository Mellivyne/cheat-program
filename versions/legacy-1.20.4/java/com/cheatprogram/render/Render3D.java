package com.cheatprogram.render;

import com.cheatprogram.compat.legacy.LegacyRenderSystem;
import com.cheatprogram.compat.legacy.LegacyBufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.cheatprogram.compat.legacy.LegacyTesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import com.cheatprogram.compat.legacy.LegacyCoreShaders;
import com.cheatprogram.compat.legacy.LegacyFogParameters;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

/** World-space drawing right after the level has been rendered; everything is visible through walls. */
public final class Render3D {
	public final float partialTick;
	public final Vec3 camera;
	public final Camera cameraObject;
	private final Matrix4f view;
	private final Matrix4f projection;
	private LegacyFogParameters savedFog;

	public Render3D(Camera camera, Matrix4f view, float partialTick) {
		this(camera,view,LegacyRenderSystem.getProjectionMatrix(),partialTick);
	}
	public Render3D(Camera camera,Matrix4f view,Matrix4f projection,float partialTick){
		this.cameraObject = camera;
		this.camera = camera.getPosition();
		this.view = view;
		this.projection = new Matrix4f(projection);
		this.partialTick = partialTick;
	}

	/** A copy for projecting observation-only world panels without changing the render view. */
	public Matrix4f viewMatrix() { return new Matrix4f(view); }
	public Matrix4f projectionMatrix(){return new Matrix4f(projection);}

	public void begin() {
		Matrix4fStack stack = LegacyRenderSystem.getModelViewStack();
		stack.pushMatrix();
		stack.set(view);
		savedFog = LegacyRenderSystem.getShaderFog();
		LegacyRenderSystem.setShaderFog(LegacyFogParameters.NO_FOG);
		LegacyRenderSystem.enableBlend();
		LegacyRenderSystem.defaultBlendFunc();
		LegacyRenderSystem.disableDepthTest();
		LegacyRenderSystem.depthMask(false);
		LegacyRenderSystem.disableCull();
	}

	public void end() {
		LegacyRenderSystem.getModelViewStack().popMatrix();
		LegacyRenderSystem.setShaderFog(savedFog);
		LegacyRenderSystem.lineWidth(1f);
		LegacyRenderSystem.depthMask(true);
		LegacyRenderSystem.enableDepthTest();
		LegacyRenderSystem.enableCull();
		LegacyRenderSystem.disableBlend();
	}

	/** Point in front of the camera, used as the start of tracer lines. */
	public Vec3 crosshair() {
		Vector3f look = cameraObject.getLookVector();
		return camera.add(look.x() * 1.5, look.y() * 1.5, look.z() * 1.5);
	}

	public void line(Vec3 a, Vec3 b, int color, float width) {
		LegacyRenderSystem.setShader(LegacyCoreShaders.RENDERTYPE_LINES);
		LegacyRenderSystem.lineWidth(width);
		LegacyBufferBuilder buffer = LegacyTesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
		segment(buffer, a, b, color);
		com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(buffer.buildOrThrow());
	}

	public void box(AABB box, int fill, int outline, float width) {
		double x0 = box.minX - camera.x, y0 = box.minY - camera.y, z0 = box.minZ - camera.z;
		double x1 = box.maxX - camera.x, y1 = box.maxY - camera.y, z1 = box.maxZ - camera.z;
		if ((fill >>> 24) != 0) {
			LegacyRenderSystem.setShader(LegacyCoreShaders.POSITION_COLOR);
			LegacyBufferBuilder q = LegacyTesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
			quad(q, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, fill);
			quad(q, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, fill);
			quad(q, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, fill);
			quad(q, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, fill);
			quad(q, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, fill);
			quad(q, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, fill);
			com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(q.buildOrThrow());
		}
		if ((outline >>> 24) != 0) {
			LegacyRenderSystem.setShader(LegacyCoreShaders.RENDERTYPE_LINES);
			LegacyRenderSystem.lineWidth(width);
			LegacyBufferBuilder l = LegacyTesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
			double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
			int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
			for (int[] e : edges) rawSegment(l, c[e[0]], c[e[1]], outline);
			com.cheatprogram.compat.legacy.LegacyRenderSystem.drawWithShader(l.buildOrThrow());
		}
	}

	private void segment(LegacyBufferBuilder buffer, Vec3 a, Vec3 b, int color) {
		rawSegment(buffer, new double[]{a.x - camera.x, a.y - camera.y, a.z - camera.z}, new double[]{b.x - camera.x, b.y - camera.y, b.z - camera.z}, color);
	}

	private static void rawSegment(LegacyBufferBuilder buffer, double[] a, double[] b, int color) {
		Vector3f n = new Vector3f((float) (b[0] - a[0]), (float) (b[1] - a[1]), (float) (b[2] - a[2]));
		if (n.lengthSquared() < 1e-8f) n.set(0, 1, 0);
		n.normalize();
		buffer.addVertex((float) a[0], (float) a[1], (float) a[2]).setColor(color).setNormal(n.x, n.y, n.z);
		buffer.addVertex((float) b[0], (float) b[1], (float) b[2]).setColor(color).setNormal(n.x, n.y, n.z);
	}

	private static void quad(LegacyBufferBuilder b, double ax, double ay, double az, double bx, double by, double bz,
							 double cx, double cy, double cz, double dx, double dy, double dz, int color) {
		b.addVertex((float) ax, (float) ay, (float) az).setColor(color);
		b.addVertex((float) bx, (float) by, (float) bz).setColor(color);
		b.addVertex((float) cx, (float) cy, (float) cz).setColor(color);
		b.addVertex((float) dx, (float) dy, (float) dz).setColor(color);
	}
}
