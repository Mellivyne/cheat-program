package com.cheatprogram.render;

import com.cheatprogram.util.OreVeinInference.Candidate;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.CoreShaders;

import java.util.List;

/** Amber broken outlines deliberately differ from the received ore's actual textured model. */
public final class OreCandidateRenderer {
	private OreCandidateRenderer() { }
	public static void draw(Render3D render, List<Candidate> candidates, float opacity) {
		if (candidates.isEmpty() || opacity <= 0) return;
		render.begin();
		try {
			RenderSystem.setShader(CoreShaders.RENDERTYPE_LINES); RenderSystem.lineWidth(1.4f);
			BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
			for (Candidate candidate : candidates) {
				int alpha = Math.max(0, Math.min(255, Math.round(255 * opacity * (.25f + .55f * candidate.association()))));
				int color = alpha << 24 | 0xFFD286;
				double x = candidate.position().getX() - render.camera.x;
				double y = candidate.position().getY() - render.camera.y;
				double z = candidate.position().getZ() - render.camera.z;
				for (int corner = 0; corner < 8; corner++) {
					double sx = (corner & 1) == 0 ? 1 : -1, sy = (corner & 2) == 0 ? 1 : -1, sz = (corner & 4) == 0 ? 1 : -1;
					double cx = x + ((corner & 1) == 0 ? .025 : .975);
					double cy = y + ((corner & 2) == 0 ? .025 : .975);
					double cz = z + ((corner & 4) == 0 ? .025 : .975);
					segment(buffer, cx, cy, cz, cx + sx * .18, cy, cz, color, (float) sx, 0, 0);
					segment(buffer, cx, cy, cz, cx, cy + sy * .18, cz, color, 0, (float) sy, 0);
					segment(buffer, cx, cy, cz, cx, cy, cz + sz * .18, color, 0, 0, (float) sz);
				}
			}
			BufferUploader.drawWithShader(buffer.buildOrThrow());
		} finally { render.end(); }
	}
	private static void segment(BufferBuilder buffer, double ax, double ay, double az, double bx, double by, double bz,
			int color, float nx, float ny, float nz) {
		buffer.addVertex((float) ax, (float) ay, (float) az).setColor(color).setNormal(nx, ny, nz);
		buffer.addVertex((float) bx, (float) by, (float) bz).setColor(color).setNormal(nx, ny, nz);
	}
}
