package com.cheatprogram.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.client.renderer.FogParameters;
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
	private FogParameters savedFog;

	public Render3D(Camera camera, Matrix4f view, float partialTick) {
		this(camera,view,RenderSystem.getProjectionMatrix(),partialTick);
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
		Matrix4fStack stack = RenderSystem.getModelViewStack();
		stack.pushMatrix();
		stack.set(view);
		savedFog = RenderSystem.getShaderFog();
		RenderSystem.setShaderFog(FogParameters.NO_FOG);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.disableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.disableCull();
	}

	public void end() {
		RenderSystem.getModelViewStack().popMatrix();
		RenderSystem.setShaderFog(savedFog);
		RenderSystem.lineWidth(1f);
		RenderSystem.depthMask(true);
		RenderSystem.enableDepthTest();
		RenderSystem.enableCull();
		RenderSystem.disableBlend();
	}

	/** Point in front of the camera, used as the start of tracer lines. */
	public Vec3 crosshair() {
		Vector3f look = cameraObject.getLookVector();
		return camera.add(look.x() * 1.5, look.y() * 1.5, look.z() * 1.5);
	}

	public void line(Vec3 a, Vec3 b, int color, float width) {
		RenderSystem.setShader(CoreShaders.RENDERTYPE_LINES);
		RenderSystem.lineWidth(width);
		BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
		segment(buffer, a, b, color);
		BufferUploader.drawWithShader(buffer.buildOrThrow());
	}

	public void box(AABB box, int fill, int outline, float width) {
		double x0 = box.minX - camera.x, y0 = box.minY - camera.y, z0 = box.minZ - camera.z;
		double x1 = box.maxX - camera.x, y1 = box.maxY - camera.y, z1 = box.maxZ - camera.z;
		if ((fill >>> 24) != 0) {
			RenderSystem.setShader(CoreShaders.POSITION_COLOR);
			BufferBuilder q = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
			quad(q, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, fill);
			quad(q, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, fill);
			quad(q, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, fill);
			quad(q, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, fill);
			quad(q, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, fill);
			quad(q, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, fill);
			BufferUploader.drawWithShader(q.buildOrThrow());
		}
		if ((outline >>> 24) != 0) {
			RenderSystem.setShader(CoreShaders.RENDERTYPE_LINES);
			RenderSystem.lineWidth(width);
			BufferBuilder l = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
			double[][] c = {{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}, {x0, y1, z0}, {x1, y1, z0}, {x1, y1, z1}, {x0, y1, z1}};
			int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
			for (int[] e : edges) rawSegment(l, c[e[0]], c[e[1]], outline);
			BufferUploader.drawWithShader(l.buildOrThrow());
		}
	}

	private void segment(BufferBuilder buffer, Vec3 a, Vec3 b, int color) {
		rawSegment(buffer, new double[]{a.x - camera.x, a.y - camera.y, a.z - camera.z}, new double[]{b.x - camera.x, b.y - camera.y, b.z - camera.z}, color);
	}

	private static void rawSegment(BufferBuilder buffer, double[] a, double[] b, int color) {
		Vector3f n = new Vector3f((float) (b[0] - a[0]), (float) (b[1] - a[1]), (float) (b[2] - a[2]));
		if (n.lengthSquared() < 1e-8f) n.set(0, 1, 0);
		n.normalize();
		buffer.addVertex((float) a[0], (float) a[1], (float) a[2]).setColor(color).setNormal(n.x, n.y, n.z);
		buffer.addVertex((float) b[0], (float) b[1], (float) b[2]).setColor(color).setNormal(n.x, n.y, n.z);
	}

	private static void quad(BufferBuilder b, double ax, double ay, double az, double bx, double by, double bz,
							 double cx, double cy, double cz, double dx, double dy, double dz, int color) {
		b.addVertex((float) ax, (float) ay, (float) az).setColor(color);
		b.addVertex((float) bx, (float) by, (float) bz).setColor(color);
		b.addVertex((float) cx, (float) cy, (float) cz).setColor(color);
		b.addVertex((float) dx, (float) dy, (float) dz).setColor(color);
	}
}
