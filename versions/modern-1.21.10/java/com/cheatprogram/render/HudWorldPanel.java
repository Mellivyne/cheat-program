package com.cheatprogram.render;

import com.cheatprogram.core.Lang;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.util.Anim;
import com.cheatprogram.compat.gl.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

import java.util.List;

/** A camera-facing glass billboard projected from one known world anchor. */
public final class HudWorldPanel {
	private static final Minecraft MC = Minecraft.getInstance();
	public record Projection(float x, float y, float scale, long time) { }
	private HudWorldPanel() { }

	public static Projection project(Render3D renderer, Vec3 anchor, double maxDistance) {
		double distance = renderer.camera.distanceTo(anchor);
		if (distance < 0.4 || distance > maxDistance) return null;
		Vec3 relative = anchor.subtract(renderer.camera);
		var projection = RenderSystem.getProjectionMatrix();
		Vector4f clip = new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1)
				.mul(renderer.viewMatrix()).mul(projection);
		if (clip.w <= 0.01f) return null;
		float x = clip.x / clip.w, y = clip.y / clip.w, depth = clip.z / clip.w;
		if (depth < -1 || depth > 1 || Math.abs(x) > 1.1f || Math.abs(y) > 1.1f) return null;
		int width = MC.getWindow().getWidth(), height = MC.getWindow().getHeight();
		float focal = Math.abs(projection.m11()) * height / 2f;
		float scale = Math.max(0.45f, Math.min(2.8f, focal * 3.2f / ((float) distance * 190f)));
		return new Projection((x + 1) * width / 2f, (1 - y) * height / 2f, scale, System.nanoTime());
	}

	public static void draw(Projection projection, String title, List<ItemStack> items) {
		if (!R2D.isActive() || System.nanoTime() - projection.time > 250_000_000L || items.isEmpty()) return;
		int count = Math.min(54, items.size()), rows = (count + 8) / 9;
		float width = 190, height = 26 + rows * 20;
		float scale = projection.scale, x = projection.x - width * scale / 2, y = projection.y - height * scale;
		if (x + width * scale < 0 || y + height * scale < 0 || x > R2D.width() || y > R2D.height()) return;
		Canvas canvas = new Canvas(); canvas.set(scale, x, y);
		Backdrop.capture(Theme.frost()); R2D.applyState();
		canvas.shadow(0, 2, width, height, 8, 7, Anim.black(0.18f));
		canvas.lens(0, 0, width, height, 8, Theme.lift(0.07f), Theme.chipGlass(scale));
		canvas.textC(Fonts.Face.REGULAR, canvas.ellipsize(Fonts.Face.REGULAR, title, 10, 170), 9, 13, 10, Theme.TEXT);
		for (int index = 0; index < count; index++) canvas.rrect(7 + index % 9 * 20, 22 + index / 9 * 20, 18, 18, 3, Anim.white(0.045f));
		GuiGraphics graphics = com.cheatprogram.compat.GuiBridge.create(MC, "hud-world-panel");
		var pose = graphics.pose(); pose.pushMatrix();
		try {
			float guiScale = (float) MC.getWindow().getGuiScale();
			pose.translate(x / guiScale, y / guiScale); pose.scale(scale / guiScale, scale / guiScale);
			for (int index = 0; index < count; index++) {
				ItemStack item = items.get(index);
				if (item.isEmpty()) continue;
				int itemX = 8 + index % 9 * 20, itemY = 23 + index / 9 * 20;
				graphics.renderItem(item, itemX, itemY);
				graphics.renderItemDecorations(MC.font, item, itemX, itemY);
			}
			com.cheatprogram.compat.GuiBridge.flush(graphics);
		} finally { pose.popMatrix(); R2D.applyState(); }
		if (items.stream().allMatch(ItemStack::isEmpty)) canvas.textMid(Fonts.Face.REGULAR, Lang.t("cheststealer.empty"), width / 2, 22 + rows * 10, 10, Theme.TEXT_3);
	}
}
