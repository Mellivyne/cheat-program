package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.hud.HudRenderer;
import com.cheatprogram.module.legit.RearViewMirror;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.render.RearMirrorRenderer;
import com.cheatprogram.util.Anim;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Desktop-style drag/resize handles, with a separate right-drag gesture for 3D tilt. */
public final class MirrorEditorScreen extends GuiBase {
	private final Screen parent;
	private final RearViewMirror module;
	private final boolean originallyEnabled;
	private float screenW, screenH;
	private int resizeX, resizeY;
	private boolean resizing;
	private final long[] cursors = new long[4];

	public MirrorEditorScreen(Screen parent) {
		super(Lang.t("ui.mirror.editor")); this.parent = parent;
		module = ModuleManager.get(RearViewMirror.class);
		originallyEnabled = module != null && module.isEnabled();
		if (module != null && !originallyEnabled) module.setEnabled(true);
	}
	@Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		beginFrame(); R2D.begin(graphics);
		float scale = HudRenderer.hudScale(); c.set(scale, 0, 0); updateMouse();
		screenW = R2D.width() / scale; screenH = R2D.height() / scale;
		c.rect(0, 0, screenW, screenH, Anim.black(.20f));
		if (module != null) {
			RearMirrorRenderer.draw(module, new HudContext(c, screenW, screenH, partialTick, false));
			float[] b = module.bounds(screenW, screenH);
			float x = b[0], y = b[1], w = b[2], h = b[3];
			c.rect(x - 1, y - 1, w + 2, 1, Anim.white(.4f));
			c.rect(x - 1, y + h, w + 2, 1, Anim.white(.4f));
			c.rect(x - 1, y, 1, h, Anim.white(.4f)); c.rect(x + w, y, 1, h, Anim.white(.4f));
			hit(x, y, w, h).click(button -> {
				if (button == 0) startMove(b);
				else if (button == 1 && module.threeDimensional()) startTilt();
			});
			for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
				if (dx == 0 && dy == 0) continue;
				float hx = x + (dx + 1) * w / 2, hy = y + (dy + 1) * h / 2;
				c.rrect(hx - 2, hy - 2, 4, 4, 1, Anim.white(.85f));
				int directionX = dx, directionY = dy;
				if (dx == 0) hit(x + 6, hy - 5, w - 12, 10).left(() -> startResize(b, directionX, directionY));
				else if (dy == 0) hit(hx - 5, y + 6, 10, h - 12).left(() -> startResize(b, directionX, directionY));
				else hit(hx - 6, hy - 6, 12, 12).left(() -> startResize(b, directionX, directionY));
			}
		}
		toolbar(); R2D.end(); endFrame(); updateCursor();
	}
	private void toolbar() {
		float width = Math.min(screenW - 16, 540), x = (screenW - width) / 2, y = screenH - 61;
		Backdrop.capture(Theme.frost()); R2D.applyState();
		c.lens(x, y, width, 49, 20, Theme.lift(.07f), Theme.chipGlass(c.scale));
		c.textC(BOLD, Lang.t("ui.mirror.editor"), x + 15, y + 14, 10.5f, Theme.TEXT);
		c.textC(REG, c.ellipsize(REG, Lang.t("ui.mirror.hint"), 8, Math.max(70, width - 285)), x + 15, y + 31, 8, Theme.TEXT_3);
		float bx = x + width - 252;
		button("mirror.mode", Lang.mode(module != null && module.threeDimensional() ? "mirror_3d" : "mirror_flat"), null, bx, y + 12, 78, 25,
				false, () -> { if (module != null) module.setThreeDimensional(!module.threeDimensional()); });
		button("mirror.reset", Lang.t("ui.hud.reset"), Icon.REFRESH, bx + 84, y + 12, 86, 25, false, () -> { if (module != null) module.resetLayout(); });
		button("mirror.done", Lang.t("ui.hud.done"), Icon.CHECK, bx + 176, y + 12, 64, 25, true, this::onClose);
	}
	private void startMove(float[] bounds) {
		resizing = false; float offsetX = mx - bounds[0], offsetY = my - bounds[1];
		drag = (px, py) -> module.move(px - offsetX, py - offsetY, screenW, screenH);
	}
	private void startTilt() {
		resizing = false; float mouseX = mx, mouseY = my, x = module.tiltX(), y = module.tiltY();
		drag = (px, py) -> module.setTilt(x - (py - mouseY) * .45f, y + (px - mouseX) * .45f);
	}
	private void startResize(float[] bounds, int dx, int dy) {
		resizing = true; resizeX = dx; resizeY = dy;
		float mouseX = mx, mouseY = my, x = bounds[0], y = bounds[1], width = bounds[2], height = bounds[3];
		drag = (px, py) -> {
			module.resize(width + (px - mouseX) * dx, height + (py - mouseY) * dy);
			float[] resized = module.bounds(screenW, screenH);
			module.move(dx < 0 ? x + width - resized[2] : x, dy < 0 ? y + height - resized[3] : y, screenW, screenH);
		};
	}
	private void updateCursor() {
		int dx = resizeX, dy = resizeY;
		if (!resizing) {
			dx = dy = 0;
			if (module != null) {
				float[] b = module.bounds(screenW, screenH);
				if (mx >= b[0] - 6 && mx <= b[0] + b[2] + 6 && my >= b[1] - 6 && my <= b[1] + b[3] + 6) {
					if (Math.abs(mx - b[0]) <= 6) dx = -1; else if (Math.abs(mx - b[0] - b[2]) <= 6) dx = 1;
					if (Math.abs(my - b[1]) <= 6) dy = -1; else if (Math.abs(my - b[1] - b[3]) <= 6) dy = 1;
				}
			}
		}
		long cursor = 0;
		if (dx != 0 || dy != 0) {
			int index = dx == 0 ? 1 : dy == 0 ? 0 : dx == dy ? 2 : 3;
			if (cursors[index] == 0) {
				int shape = index == 0 ? GLFW.GLFW_HRESIZE_CURSOR : index == 1 ? GLFW.GLFW_VRESIZE_CURSOR
						: index == 2 ? GLFW.GLFW_RESIZE_NWSE_CURSOR : GLFW.GLFW_RESIZE_NESW_CURSOR;
				cursors[index] = GLFW.glfwCreateStandardCursor(shape);
				if (cursors[index] == 0 && index >= 2) cursors[index] = GLFW.glfwCreateStandardCursor(GLFW.GLFW_CROSSHAIR_CURSOR);
			}
			cursor = cursors[index];
		}
		GLFW.glfwSetCursor(mc.getWindow().handle(), cursor);
	}
	@Override public boolean mouseClicked(double mouseX, double mouseY, int button) { dispatchClick(mouseX, mouseY, button); return true; }
	@Override public boolean mouseReleased(double mouseX, double mouseY, int button) { resizing = false; return super.mouseReleased(mouseX, mouseY, button); }
	@Override public boolean keyPressed(int key, int scanCode, int modifiers) {
		if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; } return false;
	}
	@Override public void onClose() { mc.gui.setScreen(parent); }
	@Override public void removed() {
		GLFW.glfwSetCursor(mc.getWindow().handle(), 0);
		for (long cursor : cursors) if (cursor != 0) GLFW.glfwDestroyCursor(cursor);
		java.util.Arrays.fill(cursors, 0); drag = null;
		if (module != null && !originallyEnabled && module.isEnabled()) module.setEnabled(false);
	}
}
