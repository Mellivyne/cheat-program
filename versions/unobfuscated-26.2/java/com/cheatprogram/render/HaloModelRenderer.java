package com.cheatprogram.render;

import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.cheatprogram.compat.gl.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Original group transforms, multi-face meshes, and separately textured inner/outer ring surfaces. */
public final class HaloModelRenderer {
	private record Surface(Matrix4f matrix, HaloModelLibrary.Primitive primitive, float brightness, double distance) { }
	private static HaloModelLibrary.Model centeredModel;
	private static Vector3f centeredOrigin = new Vector3f();
	private static float centeredRadius = 1;
	private static HaloModelLibrary.Model meshedModel;
	private static String meshedStyle = "";
	private static int meshedResolution;
	private static float meshedThickness;
	private static final Map<String, HaloVolumeMesh.Mesh> silhouettes = new HashMap<>();
	private static final Map<HaloModelLibrary.Primitive, HaloVolumeMesh.Mesh> pixelRings = new HashMap<>();
	private HaloModelRenderer() { }
	public static void releaseMesh() {
		silhouettes.clear(); pixelRings.clear(); meshedModel = null; centeredModel = null;
	}
	public static void draw(Render3D renderer, HaloModelLibrary.Model model, Vec3 head, float yaw, float size, float opacity,
			float brightness, double time, String style, float thickness, int pixelResolution) {
		prepareMeshes(model, style, thickness, pixelResolution);
		var root = new Matrix4f().translation((float)(head.x - renderer.camera.x), (float)(head.y - renderer.camera.y), (float)(head.z - renderer.camera.z))
				.rotateY((float)Math.toRadians(-yaw));
		var offset = model.offset();
		var scale = model.scale();
		// The pack's horizontal positioning offset was authored for another attachment system.
		// Attach the visible geometry's centre directly above our player's head instead.
		root.translate(0, offset.y * size, 0).scale(scale.x * size, scale.y * size, scale.z * size);
		applyAnimation(root, model.animation(), time);
		Vector3f center = neutralCenter(model);
		root.translate(-center.x, -center.y, -center.z);
		renderer.begin();
		try {
			RenderSystem.enableDepthTest(); RenderSystem.depthMask(false);
			paint(root, model, brightness, time, opacity, style, thickness, pixelResolution, renderer.cameraObject.rotation(), false);
		} finally {
			RenderSystem.setShaderColor(1, 1, 1, 1);
			renderer.end();
		}
	}
	/** Draw the same model and mesh into the caller's private framebuffer, without a live-world camera. */
	public static void drawPreview(Matrix4f view, HaloModelLibrary.Model model, float size, float opacity,
			float brightness, double time, String style, float thickness, int pixelResolution, Quaternionfc cameraRotation) {
		prepareMeshes(model, style, thickness, pixelResolution);
		Vector3f center = neutralCenter(model), scale = model.scale();
		Matrix4f root = new Matrix4f(view).scale(scale.x * size, scale.y * size, scale.z * size);
		applyAnimation(root, model.animation(), time);
		root.translate(-center.x, -center.y, -center.z);
		paint(root, model, brightness, time, opacity, style, thickness, pixelResolution, cameraRotation, true);
	}
	/** Radius of the original visible neutral model after its authored scale, cached per character. */
	public static float previewRadius(HaloModelLibrary.Model model) { neutralCenter(model); return centeredRadius; }
	private static void prepareMeshes(HaloModelLibrary.Model model, String style, float thickness, int pixelResolution) {
		if (meshedModel != model || !meshedStyle.equals(style) || meshedResolution != pixelResolution || meshedThickness != thickness) {
			silhouettes.clear(); pixelRings.clear();
			meshedModel = model; meshedStyle = style; meshedResolution = pixelResolution; meshedThickness = thickness;
		}
	}
	private static void paint(Matrix4f root, HaloModelLibrary.Model model, float brightness, double time, float opacity,
			String style, float thickness, int pixelResolution, Quaternionfc cameraRotation, boolean orthographic) {
		List<Surface> surfaces = new ArrayList<>();
		for (var node : model.layers()) collect(node, root, brightness * model.animation().brightness(time), time, surfaces);
		// Transparent source faces are painted back to front while real world depth still occludes them.
		if (orthographic) surfaces.sort(Comparator.comparingDouble(surface -> surface.matrix.m32()));
		else surfaces.sort(Comparator.comparingDouble(Surface::distance).reversed());
		for (var surface : surfaces) {
			RenderSystem.setShaderColor(surface.brightness, surface.brightness, surface.brightness, opacity);
			if (surface.primitive.type().equals("ring")) ring(surface.matrix, surface.primitive, style, thickness, pixelResolution);
			else billboard(cameraRotation, surface.matrix, surface.primitive, style, thickness, pixelResolution);
		}
	}
	private static void collect(HaloModelLibrary.Node node, Matrix4f parent, float brightness, double time, List<Surface> surfaces) {
		Matrix4f matrix = new Matrix4f(parent);
		var position = node.position(); var rotation = node.rotation(); var scale = node.scale(); var animation = node.animation();
		matrix.translate(position.x + HaloModelLibrary.Motion.sum(animation.x(), time), position.y + HaloModelLibrary.Motion.sum(animation.y(), time),
				position.z + HaloModelLibrary.Motion.sum(animation.z(), time));
		// HaloCore stores Euler arrays as [yaw, pitch, roll], in Y -> X -> Z order.
		matrix.rotateY(radians(rotation.x + HaloModelLibrary.Motion.sum(animation.yaw(), time)))
				.rotateX(radians(rotation.y + HaloModelLibrary.Motion.sum(animation.pitch(), time)))
				.rotateZ(radians(rotation.z + HaloModelLibrary.Motion.sum(animation.roll(), time))).scale(scale);
		brightness *= animation.brightness(time);
		Vector3f center = matrix.transformPosition(new Vector3f());
		for (var primitive : node.primitives()) surfaces.add(new Surface(matrix, primitive, brightness, center.lengthSquared()));
		for (var child : node.children()) collect(child, matrix, brightness, time, surfaces);
	}
	private static void applyAnimation(Matrix4f matrix, HaloModelLibrary.Motion motion, double time) {
		matrix.translate(0, HaloModelLibrary.Motion.sum(motion.y(), time), 0)
				.rotateY(radians(HaloModelLibrary.Motion.sum(motion.yaw(), time))).rotateX(radians(HaloModelLibrary.Motion.sum(motion.pitch(), time)))
				.rotateZ(radians(HaloModelLibrary.Motion.sum(motion.roll(), time)));
	}
	private static Vector3f neutralCenter(HaloModelLibrary.Model model) {
		if (centeredModel == model) return centeredOrigin;
		Vector3f minimum = new Vector3f(Float.POSITIVE_INFINITY), maximum = new Vector3f(Float.NEGATIVE_INFINITY);
		for (var node : model.layers()) bounds(node, new Matrix4f(), minimum, maximum);
		centeredOrigin = Float.isFinite(minimum.x) ? new Vector3f(minimum).add(maximum).mul(.5f) : new Vector3f();
		Vector3f extent = Float.isFinite(minimum.x) ? new Vector3f(maximum).sub(minimum).mul(model.scale()) : new Vector3f(2);
		centeredRadius = Math.max(.05f, extent.length() * .5f);
		centeredModel = model;
		return centeredOrigin;
	}
	private static void bounds(HaloModelLibrary.Node node, Matrix4f parent, Vector3f minimum, Vector3f maximum) {
		var p = node.position(); var r = node.rotation(); var s = node.scale(); var a = node.animation();
		Matrix4f transform = new Matrix4f(parent).translate(p.x + HaloModelLibrary.Motion.sum(a.x(), 0), p.y + HaloModelLibrary.Motion.sum(a.y(), 0), p.z + HaloModelLibrary.Motion.sum(a.z(), 0))
				.rotateY(radians(r.x + HaloModelLibrary.Motion.sum(a.yaw(), 0)))
				.rotateX(radians(r.y + HaloModelLibrary.Motion.sum(a.pitch(), 0)))
				.rotateZ(radians(r.z + HaloModelLibrary.Motion.sum(a.roll(), 0))).scale(s);
		for (var primitive : node.primitives()) {
			if (primitive.type().equals("ring")) {
				for (int i = 0; i < 64; i++) {
					double angle = i * Math.PI * 2 / 64;
					for (int sign : new int[]{-1, 1}) include(transform, (float)Math.cos(angle) * primitive.width(), sign * primitive.height() / 2, (float)Math.sin(angle) * primitive.width(), minimum, maximum);
				}
			} else for (int x : new int[]{-1, 1}) for (int z : new int[]{-1, 1})
				include(transform, x * primitive.width() / 2, 0, z * primitive.height() / 2, minimum, maximum);
		}
		for (var child : node.children()) bounds(child, transform, minimum, maximum);
	}
	private static void include(Matrix4f transform, float x, float y, float z, Vector3f minimum, Vector3f maximum) {
		Vector3f point = transform.transformPosition(new Vector3f(x, y, z));
		minimum.min(point); maximum.max(point);
	}
	private static float radians(float degrees) { return (float)Math.toRadians(degrees % 360); }
	private static void billboard(Quaternionfc cameraRotation, Matrix4f transform, HaloModelLibrary.Primitive primitive, String style, float thickness, int resolution) {
		Identifier texture = HaloModelLibrary.texture(primitive.texture());
		if (texture == null) return;
		Matrix4f matrix = transform;
		if (primitive.faceCamera()) {
			Vector3f center = transform.transformPosition(new Vector3f()), scale = transform.getScale(new Vector3f());
			matrix = new Matrix4f().translation(center).rotate(cameraRotation).rotateX((float)Math.PI / 2).scale(scale);
		}
		if (!style.equals("halo_original")) {
			HaloVolumeMesh.Sample sample = HaloModelLibrary.silhouette(primitive.texture());
			if (sample != null) {
				boolean pixel = style.equals("halo_pixel");
				HaloVolumeMesh.Mesh mesh = silhouettes.computeIfAbsent(primitive.texture(), ignored -> HaloVolumeMesh.silhouette(sample, pixel ? resolution : HaloVolumeMesh.SAMPLE_SIZE, pixel));
				float extrusion = Math.min(primitive.width(), primitive.height()) * thickness;
				// Actual alpha-contour side walls, or whole coloured voxel faces for Minecraft styling.
				drawColored(new Matrix4f(matrix).scale(primitive.width(), extrusion, primitive.height()), mesh);
				if (pixel) return;
				texturedCaps(matrix, primitive, texture, extrusion / 2);
				return;
			}
		}
		RenderSystem.disableCull();
		RenderSystem.setShader(com.cheatprogram.compat.gl.CoreShaders.POSITION_TEX_COLOR); RenderSystem.setShaderTexture(0, texture);
		BufferBuilder buffer = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		float w = primitive.width() / 2, h = primitive.height() / 2;
		// Match HaloCore's XZ quad and image orientation, including its -Y front normal.
		vertex(buffer, matrix, -w, 0, -h, 0, 1); vertex(buffer, matrix, w, 0, -h, 1, 1);
		vertex(buffer, matrix, w, 0, h, 1, 0); vertex(buffer, matrix, -w, 0, h, 0, 0);
		BufferUploader.drawWithShader(buffer.buildOrThrow());
	}
	private static void texturedCaps(Matrix4f matrix, HaloModelLibrary.Primitive primitive, Identifier texture, float half) {
		RenderSystem.enableCull();
		RenderSystem.setShader(com.cheatprogram.compat.gl.CoreShaders.POSITION_TEX_COLOR); RenderSystem.setShaderTexture(0,texture);
		BufferBuilder b = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
		float w = primitive.width()/2, h = primitive.height()/2;
		vertex(b,matrix,-w,half,-h,0,1); vertex(b,matrix,-w,half,h,0,0); vertex(b,matrix,w,half,h,1,0); vertex(b,matrix,w,half,-h,1,1);
		vertex(b,matrix,-w,-half,-h,0,1); vertex(b,matrix,w,-half,-h,1,1); vertex(b,matrix,w,-half,h,1,0); vertex(b,matrix,-w,-half,h,0,0);
		BufferUploader.drawWithShader(b.buildOrThrow());
	}
	private static void drawColored(Matrix4f matrix, HaloVolumeMesh.Mesh mesh) {
		if (mesh.empty()) return;
		RenderSystem.enableCull(); RenderSystem.setShader(com.cheatprogram.compat.gl.CoreShaders.POSITION_COLOR);
		BufferBuilder b = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS,DefaultVertexFormat.POSITION_COLOR);
		float[] xyz = mesh.positions(); int[] colors = mesh.colors();
		for (int i = 0; i < colors.length; i++) b.addVertex(matrix,xyz[i*3],xyz[i*3+1],xyz[i*3+2]).setColor(colors[i]);
		BufferUploader.drawWithShader(b.buildOrThrow());
	}
	private static void ring(Matrix4f matrix, HaloModelLibrary.Primitive primitive, String style, float thickness, int resolution) {
		if (style.equals("halo_pixel")) {
			HaloVolumeMesh.Sample outer = HaloModelLibrary.silhouette(primitive.texture()), inner = HaloModelLibrary.silhouette(primitive.innerTexture());
			if (outer != null && inner != null) {
				HaloVolumeMesh.Mesh mesh = pixelRings.computeIfAbsent(primitive, ignored -> HaloVolumeMesh.pixelRing(outer,inner,primitive,thickness,resolution));
				drawColored(matrix,mesh); return;
			}
		}
		float radial = style.equals("halo_original") ? .0001f : primitive.width()*thickness;
		float height = style.equals("halo_original") ? primitive.height() : Math.max(primitive.height(), radial);
		float outerRadius = primitive.width() + radial/2, innerRadius = Math.max(primitive.width()*.05f,primitive.width() - radial/2);
		// Face culling, rather than drawing coincident double-sided textures, separates the inner and outer skins.
		RenderSystem.enableCull();
		ringSurface(matrix, primitive, primitive.texture(), true, outerRadius, height/2);
		ringSurface(matrix, primitive, primitive.innerTexture(), false, innerRadius, height/2);
		if (!style.equals("halo_original")) ringCaps(matrix,primitive,outerRadius,innerRadius,height/2);
	}
	private static void ringCaps(Matrix4f matrix, HaloModelLibrary.Primitive primitive, float outer, float inner, float half) {
		Identifier texture = HaloModelLibrary.texture(primitive.texture());
		if (texture == null) return;
		RenderSystem.setShader(com.cheatprogram.compat.gl.CoreShaders.POSITION_TEX_COLOR); RenderSystem.setShaderTexture(0,texture);
		BufferBuilder b = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
		int count = primitive.segments();
		for (int i = 0; i < count; i++) {
			float u0 = (float)i/count, u1 = (float)(i+1)/count;
			float c0=(float)Math.cos(u0*Math.PI*2), s0=(float)Math.sin(u0*Math.PI*2), c1=(float)Math.cos(u1*Math.PI*2), s1=(float)Math.sin(u1*Math.PI*2);
			vertex(b,matrix,outer*c0,half,outer*s0,u0,0); vertex(b,matrix,inner*c0,half,inner*s0,u0,1);
			vertex(b,matrix,inner*c1,half,inner*s1,u1,1); vertex(b,matrix,outer*c1,half,outer*s1,u1,0);
			vertex(b,matrix,outer*c1,-half,outer*s1,u1,1); vertex(b,matrix,inner*c1,-half,inner*s1,u1,0);
			vertex(b,matrix,inner*c0,-half,inner*s0,u0,0); vertex(b,matrix,outer*c0,-half,outer*s0,u0,1);
		}
		BufferUploader.drawWithShader(b.buildOrThrow());
	}
	private static void ringSurface(Matrix4f matrix, HaloModelLibrary.Primitive primitive, String textureName, boolean outside, float radius, float halfHeight) {
		Identifier texture = HaloModelLibrary.texture(textureName);
		if (texture == null) return;
		RenderSystem.setShader(com.cheatprogram.compat.gl.CoreShaders.POSITION_TEX_COLOR); RenderSystem.setShaderTexture(0, texture);
		BufferBuilder buffer = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
		int segments = primitive.segments();
		for (int i = 0; i < segments; i++) {
			float u0 = (float)i / segments, u1 = (float)(i + 1) / segments;
			double a0 = Math.PI * 2 * u0, a1 = Math.PI * 2 * u1;
			float x0 = (float)Math.cos(a0) * radius, z0 = (float)Math.sin(a0) * radius;
			float x1 = (float)Math.cos(a1) * radius, z1 = (float)Math.sin(a1) * radius;
			if (outside) {
				vertex(buffer, matrix, x0, halfHeight, z0, u0, 0); vertex(buffer, matrix, x1, halfHeight, z1, u1, 0);
				vertex(buffer, matrix, x1, -halfHeight, z1, u1, 1); vertex(buffer, matrix, x0, -halfHeight, z0, u0, 1);
			} else {
				vertex(buffer, matrix, x1, halfHeight, z1, u1, 0); vertex(buffer, matrix, x0, halfHeight, z0, u0, 0);
				vertex(buffer, matrix, x0, -halfHeight, z0, u0, 1); vertex(buffer, matrix, x1, -halfHeight, z1, u1, 1);
			}
		}
		BufferUploader.drawWithShader(buffer.buildOrThrow());
	}
	private static void vertex(BufferBuilder buffer, Matrix4f transform, float x, float y, float z, float u, float v) {
		buffer.addVertex(transform, x, y, z).setUv(u, v).setColor(0xFFFFFFFF);
	}
}
