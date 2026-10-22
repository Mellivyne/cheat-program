package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.module.inventory.InvCleaner;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import java.util.List;

/** A read-only snapshot, deliberately without a destructive "apply" button. */
public final class InventoryPreviewScreen extends GuiBase {
	private static final float W = 650, H = 500;
	private final Screen parent;
	private final InvCleaner cleaner;
	private final Scroller scroll = new Scroller();
	private List<InvCleaner.PreviewEntry> entries;
	public InventoryPreviewScreen(Screen parent, InvCleaner cleaner) {
		super(Lang.t("ui.invpreview.title")); this.parent = parent; this.cleaner = cleaner; entries = cleaner.previewEntries();
	}
	@Override public void render(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		beginFrame();
		if (!preparePanelBackground(g, partialTick)) { endFrame(); return; }
		R2D.begin(g);
		try {
			float scale = Math.min(1.1f, Math.min(R2D.width() / (W + 36), R2D.height() / (H + 36)));
			if (customPanelBackground()) scale = fixedPanelScale(W, H);
			c.set(scale, (R2D.width() - W * scale) / 2, (R2D.height() - H * scale) / 2); updateMouse();
			if (!drawCustomPanelSurface(g, W, H, 24)) {
				Backdrop.capture(Theme.frost()); R2D.applyState();
				c.shadow(0, 6, W, H, 24, 26, Anim.black(.25f)); c.lens(0, 0, W, H, 24, Theme.lift(.08f), Theme.sheetGlass(c.scale));
			}
			c.textC(BOLD, Lang.t("ui.invpreview.title"), 24, 31, 16, Theme.TEXT);
			c.textC(REG, Lang.t("ui.invpreview.hint"), 24, 57, 9.5f, Theme.TEXT_3);
			c.textC(REG, Lang.t("ui.invpreview.count", entries.size(), entries.stream().mapToInt(InvCleaner.PreviewEntry::count).sum()), 24, 83, 11, Theme.TEXT_2);
			button("preview.refresh", Lang.t("ui.chattracker.refresh"), Icon.REFRESH, W - 126, 69, 102, 26, false, () -> { entries = cleaner.previewEntries(); scroll.target = 0; });
			scroll.max = Math.max(0, entries.size() * 49 - 320); scroll.update(dt);
			c.clip(20, 108, W - 40, 320); pushHitClip(20, 108, W - 40, 320); hit(20, 108, W - 40, 320).scroll(scroll::scroll);
			try {
				if (entries.isEmpty()) c.textMid(REG, Lang.t("ui.invpreview.empty"), W / 2, 240, 12, Theme.TEXT_3);
				for (int i = 0; i < entries.size(); i++) {
					float y = 110 + i * 49 - scroll.get(); if (y + 44 < 108 || y > 428) continue;
					var entry = entries.get(i); c.lens(24, y, W - 48, 43, 12, Theme.lift(.055f), Theme.chipGlass(c.scale));
					c.textC(MED, c.ellipsize(MED, entry.name(), 11, 390), 38, y + 14, 11, Theme.TEXT);
					c.textC(REG, entry.id(), 38, y + 31, 8.5f, Theme.TEXT_3);
					c.textRight(REG, Lang.t("ui.invpreview.slot", entry.slot(), entry.count()), W - 38, y + 22, 10, Theme.TEXT_2);
				}
			} finally { popHitClip(); c.unclip(); }
			button("preview.back", Lang.t("ui.back"), Icon.BACK, 24, H - 45, 104, 27, false, this::onClose);
		} finally { R2D.end(); endFrame(); }
	}
	@Override public boolean mouseClicked(double x, double y, int button) { dispatchClick(x, y, button); return true; }
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return dispatchScroll(x, y, vertical); }
	@Override public boolean keyPressed(int key, int scan, int modifiers) { if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; } return false; }
	@Override public void onClose() { mc.gui.setScreen(parent); }
	@Override public boolean isPauseScreen() { return false; }
}
