package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.module.world.ContainerSearch;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.ContainerIndex;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Browses snapshots of containers the local player actually opened, never unopened block entities. */
public final class ContainerSearchScreen extends GuiBase {
	private static final float W = 730, H = 560;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
	private final Screen parent;
	private final ContainerSearch module;
	private final TextField query = new TextField(256);
	private final Scroller scroll = new Scroller();
	private List<ContainerIndex.Entry> results = List.of();
	private long revision = Long.MIN_VALUE;
	private String filter = "";
	public ContainerSearchScreen(Screen parent, ContainerSearch module) {
		super(Lang.t("ui.containersearch.title")); this.parent = parent; this.module = module;
		query.setText(module.query.get()); query.onChange = value -> { module.query.set(value); revision = Long.MIN_VALUE; scroll.target = 0; };
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
			c.textC(BOLD, Lang.t("ui.containersearch.title"), 24, 30, 16, Theme.TEXT);
			c.textC(REG, c.ellipsize(REG, ContainerIndex.label(), 9, W - 52), 24, 52, 9, Theme.TEXT_3);
			textField(query, Lang.t("ui.containersearch.queryHint"), Icon.SEARCH, 24, 70, W - 48, 30);
			c.textC(REG, Lang.t("ui.containersearch.scope"), 24, 117, 9, Theme.TEXT_3);
			if (revision != ContainerIndex.revision() || !filter.equals(query.text)) {
				revision = ContainerIndex.revision(); filter = query.text; results = ContainerIndex.search(filter);
			}
			scroll.max = Math.max(0, results.size() * 85 - 345); scroll.update(dt);
			c.clip(20, 136, W - 40, 345); pushHitClip(20, 136, W - 40, 345); hit(20, 136, W - 40, 345).scroll(scroll::scroll);
			try {
				if (results.isEmpty()) c.textMid(REG, Lang.t(ContainerIndex.loading() ? "ui.chattracker.loading" : "ui.containersearch.empty"), W / 2, 290, 11, Theme.TEXT_3);
				for (int i = 0; i < results.size(); i++) {
					float y = 139 + i * 85 - scroll.get(); if (y + 79 < 136 || y > 481) continue;
					var entry = results.get(i); c.lens(24, y, W - 48, 78, 14, Theme.lift(.055f), Theme.chipGlass(c.scale));
					c.textC(MED, c.ellipsize(MED, entry.title(), 11.5f, 440), 38, y + 16, 11.5f, Theme.TEXT);
					c.textC(REG, "X " + entry.x() + "   Y " + entry.y() + "   Z " + entry.z(), 38, y + 34, 9.5f, Theme.TEXT_2);
					String q = filter.strip().toLowerCase(Locale.ROOT);
					String names = entry.items().stream().sorted(Comparator.comparing(item -> !matches(item, q)))
							.map(item -> item.name() + " ×" + item.count()).collect(Collectors.joining("  ·  "));
					c.textC(REG, c.ellipsize(REG, names.isEmpty() ? Lang.t("ui.containersearch.noItems") : names, 9, W - 78), 38, y + 60, 9, Theme.TEXT_3);
					c.textRight(REG, DATE.format(Instant.ofEpochMilli(entry.timestamp())), W - 38, y + 34, 8, Theme.TEXT_3);
					button("container.locate." + i, Lang.t("ui.containersearch.locate"), Icon.TARGET, W - 140, y + 8, 102, 24, false, () -> module.locate(entry));
				}
			} finally { popHitClip(); c.unclip(); }
			button("container.back", Lang.t("ui.back"), Icon.BACK, 24, H - 48, 104, 27, false, this::onClose);
			button("container.clear", Lang.t("ui.containersearch.clearMarker"), Icon.CLOSE, 140, H - 48, 145, 27, false, module::clearMarker);
			c.textRight(REG, Lang.t("ui.containersearch.count", results.size()), W - 24, H - 34, 9.5f, Theme.TEXT_2);
		} finally { R2D.end(); endFrame(); }
	}
	private static boolean matches(ContainerIndex.Item item, String query) { return query.isEmpty() || item.id().toLowerCase(Locale.ROOT).contains(query) || item.name().toLowerCase(Locale.ROOT).contains(query); }
	@Override public boolean mouseClicked(double x, double y, int button) { if (!dispatchClick(x, y, button)) focused = null; return true; }
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return dispatchScroll(x, y, vertical); }
	@Override public boolean keyPressed(int key, int scan, int modifiers) {
		if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
		if (focused != null) { focused.keyPressed(key, modifiers); return true; } return false;
	}
	@Override public boolean charTyped(char chr, int modifiers) { return focused != null && focused.charTyped(chr); }
	@Override public void onClose() { mc.setScreen(parent); }
	@Override public boolean isPauseScreen() { return false; }
}
