package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.hud.HudRenderer;
import com.cheatprogram.module.visual.Minimap;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Keeps map positioning independent of the original island and HUD editor layout. */
public final class MinimapEditorScreen extends GuiBase {
	private final Screen parent;
	private final Minimap module;
	private final boolean originallyEnabled;
	private float screenW, screenH;
	public MinimapEditorScreen(Screen parent) {
		super(Lang.t("ui.minimap.editor")); this.parent = parent;
		module = ModuleManager.get(Minimap.class);
		originallyEnabled = module != null && module.isEnabled();
		if (module != null && !originallyEnabled) module.setEnabled(true);
	}
	@Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		beginFrame(); R2D.begin(graphics);
		float scale = HudRenderer.hudScale(); c.set(scale, 0, 0); updateMouse();
		screenW = R2D.width() / scale; screenH = R2D.height() / scale;
		c.rect(0, 0, screenW, screenH, Anim.black(.16f));
		if (module != null) {
			module.onRender2D(new HudContext(c, screenW, screenH, partialTick, true));
			float[] b = module.bounds(screenW, screenH); float x = b[0], y = b[1], w = b[2], h = b[3];
			c.rrect4(x - 1, y - 1, w + 2, h + 2, 12, 12, 12, 12, 0, 0, 1, Anim.white(.55f), Anim.white(.25f));
			hit(x, y, w, h).left(() -> {
				float offsetX = mx - x, offsetY = my - y;
				drag = (px, py) -> module.move(px - offsetX, py - offsetY, screenW, screenH);
			}).scroll(module::zoom);
			c.rrect(x + w - 5, y + h - 5, 7, 7, 2, Anim.white(.85f));
			hit(x + w - 9, y + h - 9, 16, 16).left(() -> {
				float startX = mx, startY = my;
				drag = (px, py) -> module.resize(w + Math.max(px - startX, py - startY));
			});
		}
		float barW = Math.min(screenW - 16, 500), barX = (screenW - barW) / 2, barY = screenH - 61;
		Backdrop.capture(Theme.frost()); R2D.applyState();
		c.lens(barX, barY, barW, 49, 20, Theme.lift(.07f), Theme.chipGlass(c.scale));
		c.textC(BOLD, Lang.t("ui.minimap.editor"), barX + 15, barY + 14, 10.5f, Theme.TEXT);
		c.textC(REG, c.ellipsize(REG, Lang.t("ui.minimap.hint"), 8, Math.max(80, barW - 270)), barX + 15, barY + 32, 8, Theme.TEXT_3);
		float bx = barX + barW - 245;
		button("minimap.mode", module == null ? "N" : module.orientationLabel(), null, bx, barY + 12, 78, 25, false, () -> { if (module != null) module.toggleOrientation(); });
		button("minimap.reset", Lang.t("ui.hud.reset"), Icon.REFRESH, bx + 84, barY + 12, 84, 25, false, () -> { if (module != null) module.resetLayout(); });
		button("minimap.done", Lang.t("ui.hud.done"), Icon.CHECK, bx + 174, barY + 12, 62, 25, true, this::onClose);
		R2D.end(); endFrame();
	}
	@Override public boolean mouseClicked(double mouseX, double mouseY, int button) { dispatchClick(mouseX, mouseY, button); return true; }
	@Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) { return dispatchScroll(mouseX, mouseY, vertical); }
	@Override public boolean keyPressed(int key, int scanCode, int modifiers) {
		if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; } return false;
	}
	@Override public void onClose() { mc.setScreen(parent); }
	@Override public void removed() {
        super.removed();
		drag = null;
		if (module != null && !originallyEnabled && module.isEnabled()) module.setEnabled(false);
	}
}
