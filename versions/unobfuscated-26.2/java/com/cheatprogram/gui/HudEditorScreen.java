package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.visual.Hud;
import com.cheatprogram.module.visual.TargetHud;
import com.cheatprogram.hud.HudLayout;
import com.cheatprogram.hud.HudRenderer;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Lets the player drag HUD elements around. The elements themselves are drawn by the HUD renderer. */
public class HudEditorScreen extends GuiBase {
	private final Screen parent;
	private final Anim open = new Anim(0);
	private String dragging;
	private float screenW;
	private float screenH;
	private boolean resizingTarget;
	private int resizeX, resizeY;
	private final long[] resizeCursors = new long[4];

	public HudEditorScreen(Screen parent) {
		super(Lang.t("ui.hud.editor"));
		this.parent = parent;
	}

	@Override
	public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		beginFrame();
		float o = Anim.easeOutCubic(open.to(1f, dt, 9f));
		R2D.begin(graphics);
		float s = HudRenderer.hudScale();
		c.set(s, 0, 0);
		updateMouse();
		screenW = R2D.width() / s;
		screenH = R2D.height() / s;

		c.rect(0, 0, screenW, screenH, Anim.black(0.22f * o));
		R2D.pushAlpha(o);
		c.rect(screenW / 2f - 0.5f, 0, 1, screenH, Anim.white(0.07f));
		c.rect(0, screenH / 2f - 0.5f, screenW, 1, Anim.white(0.07f));

		for (String id : HudLayout.IDS) {
			float[] b = HudLayout.bounds(id);
			if (b == null) continue;
			boolean active = id.equals(dragging);
			boolean hovered = over(b[0] - 3, b[1] - 3, b[2] + 6, b[3] + 6);
			float ha = anim("hud." + id, active ? 1f : hovered ? 0.6f : 0f, 16f);
			c.rrect(b[0] - 4, b[1] - 4, b[2] + 8, b[3] + 8, 10, Anim.white(0.07f + 0.10f * ha), Anim.white(0.04f + 0.08f * ha));
			float labelY = b[1] - 9 < 4 ? b[1] + b[3] + 11 : b[1] - 9;
			c.icon(Icon.GRIP, b[0] + 3, labelY, 8, Theme.TEXT_3);
			c.textC(MED, Lang.t("hud." + id), b[0] + 10, labelY, 8, Anim.lerpColor(Theme.TEXT_3, Theme.TEXT, ha));
			hit(b[0] - 3, b[1] - 3, b[2] + 6, b[3] + 6).left(() -> startDrag(id, b));
			if (id.equals("targethud")) targetResizeHandles(b, ha);
		}
		toolbar();
		R2D.popAlpha();
		R2D.end();
		endFrame();
		updateResizeCursor();
	}

	private void toolbar() {
		String title = Lang.t("ui.hud.editor");
		String hint = Lang.t("ui.hud.hint");
		float tw = c.textWidth(BOLD, title, 11);
		float hw = c.textWidth(REG, hint, 8.5f);
		float w = 18 + Math.max(tw, hw) + 16 + 88 + 8 + 64 + 12;
		float h = 40;
		float x = (screenW - w) / 2f;
		// Keep the controls away from the default top-centre island so it can be grabbed.
		float y = Math.max(12, screenH - h - 12);
		Backdrop.capture(Theme.frost());
		R2D.applyState();
		c.shadow(x, y + 5, w, h, h / 2f, 20, Anim.black(0.20f));
		c.lens(x, y, w, h, h / 2f, Theme.lift(0.06f), Theme.chipGlass(c.scale));
		c.textC(BOLD, title, x + 18, y + 14, 11, Theme.TEXT);
		c.textC(REG, hint, x + 18, y + 28, 8.5f, Theme.TEXT_3);
		float bx = x + w - 12 - 64 - 8 - 88;
		button("hud.reset", Lang.t("ui.hud.reset"), Icon.REFRESH, bx, y + 8, 88, 24, false, () -> {
			HudLayout.reset();
			TargetHud target = ModuleManager.get(TargetHud.class);
			if (target != null) target.setWorldPanelOffset(0, 0);
		});
		button("hud.done", Lang.t("ui.hud.done"), Icon.CHECK, x + w - 12 - 64, y + 8, 64, 24, true, this::onClose);
	}

	private void startDrag(String id, float[] bounds) {
		dragging = id;
		resizingTarget = false;
		float offX = mx - bounds[0];
		float offY = my - bounds[1];
		float w = bounds[2];
		float h = bounds[3];
		TargetHud target = id.equals("targethud") ? ModuleManager.get(TargetHud.class) : null;
		boolean worldPanel = target != null && target.worldMode();
		float originalOffsetX = worldPanel ? target.worldOffsetX() : 0;
		float originalOffsetY = worldPanel ? target.worldOffsetY() : 0;
		drag = (px, py) -> {
			float nx = Math.max(2, Math.min(screenW - w - 2, px - offX));
			float ny = Math.max(2, Math.min(screenH - h - 2, py - offY));
			if (worldPanel) {
				target.setWorldPanelOffset(originalOffsetX + nx - bounds[0], originalOffsetY + ny - bounds[1]);
				nx = bounds[0] + target.worldOffsetX() - originalOffsetX;
				ny = bounds[1] + target.worldOffsetY() - originalOffsetY;
			}
			placeElement(id, nx, ny, w, h, !worldPanel);
		};
	}

	private void placeElement(String id, float nx, float ny, float w, float h, boolean snap) {
			float center = (nx + w / 2f) / screenW;
			boolean bottom = ny + h / 2f > screenH / 2f;
			float fx;
			if (id.equals("arraylist")) {
				Hud hud = ModuleManager.get(Hud.class);
				boolean right = hud == null || hud.arraylistRight();
				fx = right ? Math.max(.5f, (nx + w) / screenW) : Math.min(.5f, nx / screenW);
			} else if (Math.abs(center - 0.5f) < HudLayout.CENTER_BAND * 0.9f) {
				// Near the middle the element is centred (and snaps onto the exact centre line).
				fx = snap && Math.abs(center - 0.5f) < 0.012f ? 0.5f : center;
			} else {
				fx = nx + w / 2f > screenW / 2f ? (nx + w) / screenW : nx / screenW;
			}
			float fy = bottom ? (ny + h) / screenH : ny / screenH;
			HudLayout.set(id, fx, fy);
	}

	/** Later hit regions take priority over the body drag, just like the edges of a desktop window. */
	private void targetResizeHandles(float[] b, float hover) {
		TargetHud target = ModuleManager.get(TargetHud.class);
		if (target == null) return;
		float x = b[0], y = b[1], w = b[2], h = b[3];
		for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
			if (dx == 0 && dy == 0) continue;
			float cx = x + (dx + 1) * w / 2, cy = y + (dy + 1) * h / 2;
			if (hover > .01f) c.rrect(cx - 2, cy - 2, 4, 4, 1.1f, Anim.white(.45f + .45f * hover));
			int directionX = dx, directionY = dy;
			if (dx == 0) hit(x + 5, cy - 4, Math.max(0, w - 10), 8).left(() -> startTargetResize(target, b, directionX, directionY));
			else if (dy == 0) hit(cx - 4, y + 5, 8, Math.max(0, h - 10)).left(() -> startTargetResize(target, b, directionX, directionY));
			else hit(cx - 5, cy - 5, 10, 10).left(() -> startTargetResize(target, b, directionX, directionY));
		}
	}

	private void startTargetResize(TargetHud target, float[] bounds, int dx, int dy) {
		dragging = "targethud"; resizingTarget = true; resizeX = dx; resizeY = dy;
		float startMouseX = mx, startMouseY = my;
		float x = bounds[0], y = bounds[1], width = bounds[2], height = bounds[3];
		float offsetX = target.worldOffsetX(), offsetY = target.worldOffsetY();
		boolean world = target.worldMode();
		drag = (px, py) -> {
			target.resizePanel(width + (px - startMouseX) * dx, height + (py - startMouseY) * dy);
			float w = target.panelWidth(), h = target.panelHeight();
			float nx = dx < 0 ? x + width - w : x, ny = dy < 0 ? y + height - h : y;
			if (world) {
				// World plates are centred horizontally and grow upward from their entity anchor.
				target.setWorldPanelOffset(offsetX + nx - x + (w - width) / 2, offsetY + ny - y + h - height);
				nx = x + target.worldOffsetX() - offsetX - (w - width) / 2;
				ny = y + target.worldOffsetY() - offsetY - h + height;
			}
			nx = Math.max(2, Math.min(screenW - w - 2, nx));
			ny = Math.max(2, Math.min(screenH - h - 2, ny));
			placeElement("targethud", nx, ny, w, h, false);
		};
	}

	private void updateResizeCursor() {
		int dx = resizeX, dy = resizeY;
		if (!resizingTarget) {
			dx = dy = 0;
			float[] b = HudLayout.bounds("targethud");
			if (b != null && mx >= b[0] - 5 && mx <= b[0] + b[2] + 5 && my >= b[1] - 5 && my <= b[1] + b[3] + 5) {
				if (Math.abs(mx - b[0]) <= 5) dx = -1;
				else if (Math.abs(mx - b[0] - b[2]) <= 5) dx = 1;
				if (Math.abs(my - b[1]) <= 5) dy = -1;
				else if (Math.abs(my - b[1] - b[3]) <= 5) dy = 1;
			}
		}
		long cursor = 0;
		if (dx != 0 || dy != 0) {
			int index = dx == 0 ? 1 : dy == 0 ? 0 : dx == dy ? 2 : 3;
			if (resizeCursors[index] == 0) {
				int shape = index == 0 ? GLFW.GLFW_HRESIZE_CURSOR : index == 1 ? GLFW.GLFW_VRESIZE_CURSOR
						: index == 2 ? GLFW.GLFW_RESIZE_NWSE_CURSOR : GLFW.GLFW_RESIZE_NESW_CURSOR;
				resizeCursors[index] = GLFW.glfwCreateStandardCursor(shape);
				if (resizeCursors[index] == 0 && index >= 2) resizeCursors[index] = GLFW.glfwCreateStandardCursor(GLFW.GLFW_CROSSHAIR_CURSOR);
			}
			cursor = resizeCursors[index];
		}
		GLFW.glfwSetCursor(mc.getWindow().handle(), cursor);
	}

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		dispatchClick(mouseX, mouseY, button);
		return true;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		dragging = null;
		resizingTarget = false;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean keyPressed(int key, int scanCode, int modifiers) {
		if (key == GLFW.GLFW_KEY_ESCAPE) {
			onClose();
			return true;
		}
		return false;
	}

	@Override
	public void onClose() {
		mc.gui.setScreen(parent);
	}

	@Override
	public void removed() {
		GLFW.glfwSetCursor(mc.getWindow().handle(), 0);
		for (long cursor : resizeCursors) if (cursor != 0) GLFW.glfwDestroyCursor(cursor);
		java.util.Arrays.fill(resizeCursors, 0);
		drag = null; dragging = null; resizingTarget = false;
	}
}
