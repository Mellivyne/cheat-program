package com.cheatprogram.render;

import com.cheatprogram.util.ProjectilePrediction;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.cheatprogram.compat.gl.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.cheatprogram.compat.gl.CoreShaders;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** One buffered path draw; all coordinates stay world-space even with a detached camera. */
public final class TrajectoryRenderer {
	private TrajectoryRenderer() { }
	public static void draw(Render3D renderer, ProjectilePrediction.Result prediction, int lineColor, int hitColor,
			float lineWidth, boolean throughWalls, boolean hitBox, boolean impactMarker) {
		if (prediction.points().size() < 2) return;
		renderer.begin();
		try {
			if (!throughWalls) RenderSystem.enableDepthTest();
			RenderSystem.setShader(CoreShaders.RENDERTYPE_LINES);
			RenderSystem.lineWidth(lineWidth);
			BufferBuilder buffer = com.cheatprogram.compat.NativeTesselator262.getInstance().begin(com.mojang.blaze3d.PrimitiveTopology.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
			for (int i = 1; i < prediction.points().size(); i++)
				segment(buffer, prediction.points().get(i - 1).subtract(renderer.camera), prediction.points().get(i).subtract(renderer.camera), lineColor);
			BufferUploader.drawWithShader(buffer.buildOrThrow());
			if (hitBox && prediction.hitEntity() instanceof LivingEntity target && !(target instanceof ArmorStand)
					&& target.isAlive() && !target.isRemoved()) {
				Vec3 offset = target.getPosition(renderer.partialTick).subtract(target.position());
				renderer.box(target.getBoundingBox().move(offset).inflate(.035), (hitColor & 0xFFFFFF) | 0x18000000,
						hitColor, lineWidth);
			}
			if (impactMarker && (prediction.hitBlock() != null || prediction.hitEntity() != null)) {
				Vec3 end = prediction.end();
				renderer.box(new AABB(end.x - .055, end.y - .055, end.z - .055, end.x + .055, end.y + .055, end.z + .055),
						0, prediction.hitEntity() == null ? lineColor : hitColor, lineWidth);
			}
		} finally { renderer.end(); }
	}
	private static void segment(BufferBuilder builder, Vec3 start, Vec3 end, int color) {
		Vector3f normal = new Vector3f((float) (end.x - start.x), (float) (end.y - start.y), (float) (end.z - start.z));
		if (normal.lengthSquared() < 1e-12f) normal.set(0, 1, 0); else normal.normalize();
		builder.addVertex((float) start.x, (float) start.y, (float) start.z).setColor(color).setNormal(normal.x, normal.y, normal.z);
		builder.addVertex((float) end.x, (float) end.y, (float) end.z).setColor(color).setNormal(normal.x, normal.y, normal.z);
	}
}
