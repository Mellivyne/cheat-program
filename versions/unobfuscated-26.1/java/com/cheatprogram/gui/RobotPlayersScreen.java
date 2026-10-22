package com.cheatprogram.gui;

import com.cheatprogram.ai.BotPlayers;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.module.world.RobotPlayers;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Explicit batch controls: opening this page never creates an actor. */
public final class RobotPlayersScreen extends GuiBase {
	private static final float W = 760, H = 560;
	private static final int PAGE_SIZE = 8;
	private final Screen parent;
	private final RobotPlayers module;
	private final TextField count = new TextField(256), prefix = new TextField(64);
	private int page;
	public RobotPlayersScreen(Screen parent, RobotPlayers module) {
		super(Lang.t("module.robotplayers")); this.parent = parent; this.module = module;
		count.setText(module.count.get()); prefix.setText(module.prefix.get());
		count.onChange = module.count::set; prefix.onChange = module.prefix::set;
	}
	@Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		beginFrame();
		if (!preparePanelBackground(graphics, partialTick)) { endFrame(); return; }
		R2D.begin(graphics);
		try {
			float scale = Math.max(.25f, Math.min(1.35f, Math.min(R2D.width() / (W + 40), R2D.height() / (H + 40))));
			if (customPanelBackground()) scale = fixedPanelScale(W, H);
			c.set(scale, (R2D.width() - W * scale) / 2, (R2D.height() - H * scale) / 2); updateMouse();
			if (!drawCustomPanelSurface(graphics, W, H, 24)) {
				c.rect(-c.originX / scale, -c.originY / scale, R2D.width() / scale, R2D.height() / scale, Anim.black(.18f));
				Backdrop.capture(Theme.frost()); R2D.applyState();
				c.shadow(0, 6, W, H, 24, 30, Anim.black(.25f)); c.lens(0, 0, W, H, 24, Theme.lift(.08f), Theme.sheetGlass(c.scale));
			}
			c.textC(BOLD, Lang.t("module.robotplayers"), 24, 28, 16, Theme.TEXT);
			iconButton("bot.close", Icon.CLOSE, W - 29, 28, 16, Theme.TEXT_2, this::onClose);
			c.textC(REG, Lang.t("ui.robotplayers.localHint"), 24, 50, 9.5f, Theme.TEXT_3);
			button("bot.apply",Lang.t("ui.robotplayers.apply"),Icon.GEAR,574,39,162,26,false,module::applySettings);
			c.textC(REG, Lang.t("setting.robotplayers.count"), 24, 84, 10, Theme.TEXT_2);
			textField(count, "5", null, 24, 96, 190, 28);
			c.textC(REG, Lang.t("setting.robotplayers.prefix"), 24, 148, 10, Theme.TEXT_2);
			c.textC(REG, module.randomId.name(), 142, 148, 8.8f, Theme.TEXT_2);
			toggle("bot.randomId", 273, 140.5f, module.randomId.on(), 1);
			hit(138, 136, 166, 23).left(() -> {
				module.randomId.toggle();
				if (module.randomId.on() && focused == prefix) focused = null;
			});
			if (module.randomId.on()) {
				c.lens(24, 160, 280, 28, 14, Theme.lift(.025f), Theme.lensGlass(c.scale));
				c.textC(REG, c.ellipsize(REG, Lang.t("ui.robotplayers.randomIdHint"), 8.5f, 260), 34, 174, 8.5f, Theme.TEXT_4);
			} else textField(prefix, "Robot", Icon.USER, 24, 160, 280, 28);
			mode(module.mode, 24, 210); mode(module.behavior, 24, 248);
			number(module.spacing, 24, 306); number(module.spawnPerTick, 24, 357); number(module.followDistance, 24, 408);
			c.textC(REG, module.autoRespawn.name(), 24, 452, 10, Theme.TEXT_2);
			toggle("bot.respawn", 273, 444, module.autoRespawn.on(), 1);
			hit(24, 438, 280, 30).left(module.autoRespawn::toggle);
			c.textC(REG, Lang.t("ui.robotplayers.active") + " " + BotPlayers.activeCount()
					+ "  ·  " + Lang.t("ui.robotplayers.pending") + " " + BotPlayers.pendingCount(), 336, 84, 10.5f, Theme.TEXT_2);
			actors();
			c.textC(REG, c.ellipsize(REG, BotPlayers.status(), 9.5f, W - 48), 24, 493, 9.5f, Theme.TEXT_2);
			button("bot.back", Lang.t("ui.back"), Icon.BACK, 24, 516, 94, 27, false, this::onClose);
			button("bot.stop", Lang.t("ui.robotplayers.stopQueue"), null, 330, 516, 126, 27, false, BotPlayers::stopQueue);
			button("bot.removeAll", Lang.t("ui.robotplayers.removeAll"), null, 464, 516, 126, 27, false, BotPlayers::removeAll);
			button("bot.spawn", Lang.t("ui.robotplayers.spawn"), null, 598, 516, 138, 27, true, module::spawnBatch);
		} finally { R2D.end(); endFrame(); }
	}
	private void actors() {
		List<BotPlayers.Row> rows = BotPlayers.rows();
		int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE); page = Math.max(0, Math.min(pages - 1, page));
		c.lens(324, 98, 412, 364, 14, Theme.lift(.025f), Theme.lensGlass(c.scale));
		for (int i = 0; i < PAGE_SIZE; i++) {
			int index = page * PAGE_SIZE + i; if (index >= rows.size()) break;
			BotPlayers.Row row = rows.get(index); float y = 116 + i * 37;
			c.textC(MED, c.ellipsize(MED, row.name(), 10.5f, 230), 340, y, 10.5f, Theme.TEXT);
			c.textC(REG, c.ellipsize(REG, Lang.mode(row.mode()) + " · " + Lang.mode(row.behavior()) + " · " + Lang.mode(row.activity()), 8.5f, 302), 340, y + 14, 8.5f, Theme.TEXT_3);
			button("bot.remove." + row.id(), Lang.t("ui.robotplayers.remove"), null, 656, y - 8, 64, 25, false, () -> BotPlayers.remove(row.id()));
		}
		if (rows.isEmpty()) c.textC(REG, Lang.t("ui.robotplayers.empty"), 340, 128, 10, Theme.TEXT_3);
		c.textC(REG, (page + 1) + " / " + pages, 500, 438, 9, Theme.TEXT_3);
		button("bot.prev", "‹", null, 340, 425, 64, 25, false, () -> page = Math.max(0, page - 1));
		button("bot.next", "›", null, 656, 425, 64, 25, false, () -> page = Math.min(pages - 1, page + 1));
	}
	private void mode(ModeSetting setting, float x, float y) {
		selectBox(setting, setting.name(), setting.display(), x, y, 280, 28, false, () -> {
			int index = setting.modes.indexOf(setting.get()); setting.set(setting.modes.get((index + 1) % setting.modes.size()));
		});
	}
	private void number(NumberSetting setting, float x, float y) {
		c.textC(REG, setting.name() + "  " + setting.format(), x, y - 14, 10, Theme.TEXT_2); slider(setting, x + 6, y + 7, 268);
	}
	@Override public boolean isPauseScreen() { return false; }
	@Override public boolean mouseClicked(double x,double y,int button) {
		if(!dispatchClick(x,y,button)) focused=null;
		return true;
	}
	@Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) { return dispatchScroll(x,y,vertical); }
	@Override public boolean keyPressed(int key,int scanCode,int modifiers) {
		if(key==GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
		if(key==GLFW.GLFW_KEY_TAB) { focused=module.randomId.on()?count:focused==count?prefix:count; return true; }
		if(focused!=null) { focused.keyPressed(key,modifiers); return true; }
		return false;
	}
	@Override public boolean charTyped(char chr,int modifiers) { return focused!=null&&focused.charTyped(chr); }
	@Override public void onClose() { mc.setScreen(parent); }
}
