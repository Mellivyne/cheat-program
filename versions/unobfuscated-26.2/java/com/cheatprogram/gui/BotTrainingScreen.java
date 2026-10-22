package com.cheatprogram.gui;

import com.cheatprogram.ai.BotPlayers;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.module.world.TrainingRobots;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import java.util.List;
import java.util.Locale;

/** Distinct training manager, with an explicit choice between owner practice and robot duels. */
public final class BotTrainingScreen extends GuiBase {
	private static final float W = 720, PREFERRED_H = 452;
	private static final int PAGE_SIZE = 8;
	private final Screen parent;
	private final TrainingRobots module;
	private final TextField count = new TextField(256), prefix = new TextField(64);
	private final Scroller scroll = new Scroller();
	private int tab, page;
	private float panelHeight, offset;
	public BotTrainingScreen(Screen parent, TrainingRobots module) {
		super(Lang.t("module.trainingrobots")); this.parent = parent; this.module = module;
		count.setText(module.count.get()); prefix.setText(module.prefix.get());
		count.onChange = module.count::set; prefix.onChange = module.prefix::set;
	}
	@Override public void render(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		beginFrame();
		if (!preparePanelBackground(g, partialTick)) { endFrame(); return; }
		R2D.begin(g);
		try {
			panelHeight = layoutPanel(W, PREFERRED_H); updateMouse(); drawPanelSurface(g, W, panelHeight, 24);
			c.textC(BOLD, Lang.t("module.trainingrobots"), 24, 29, 16, Theme.TEXT);
			iconButton("training.close", Icon.CLOSE, W - 29, 29, 16, Theme.TEXT_2, this::onClose);
			c.textC(REG, c.ellipsize(REG, Lang.t("ui.robottraining.independentHint"), 9.5f, W - 48), 24, 54, 9.5f, Theme.TEXT_3);
			button("training.tab.overview", Lang.t("ui.robottraining.overview"), Icon.SWORD, 24, 75, 148, 28, tab == 0, () -> changeTab(0));
			button("training.tab.manage", Lang.t("ui.robottraining.manage"), Icon.USER, 184, 75, 148, 28, tab == 1, () -> changeTab(1));
			button("training.tab.settings", Lang.t("ui.robottraining.controls"), Icon.GEAR, 344, 75, 148, 28, tab == 2, () -> changeTab(2));
			c.textRight(REG, Lang.t("ui.robotplayers.active") + " " + BotPlayers.activeCount(BotPlayers.Group.TRAINING)
					+ "  ·  " + Lang.t("ui.robotplayers.pending") + " " + BotPlayers.pendingCount(BotPlayers.Group.TRAINING), W - 24, 89, 9.5f, Theme.TEXT_2);
			float bodyHeight = Math.max(72, panelHeight - 208), contentBottom = tab == 0 ? 432 : tab == 2 ? 628 : 566;
			scroll.max = Math.max(0, contentBottom - 112 - bodyHeight); scroll.update(dt); offset = scroll.get();
			c.clip(16, 112, W - 32, bodyHeight); pushHitClip(16, 112, W - 32, bodyHeight);
			hit(16, 112, W - 32, bodyHeight).scroll(scroll::scroll);
			try { if (tab == 0) overview(); else if (tab == 1) manager(); else controls(); }
			finally { popHitClip(); c.unclip(); }
			panelScrollBar("training", scroll, W - 12, 112, bodyHeight);
			c.textC(REG, c.ellipsize(REG, BotPlayers.status(BotPlayers.Group.TRAINING), 9.5f, W - 48), 24, panelHeight - 75, 9.5f, Theme.TEXT_3);
			button("training.back", Lang.t("ui.back"), Icon.BACK, 24, panelHeight - 45, 96, 27, false, this::onClose);
			button("training.apply", Lang.t("ui.robottraining.apply"), Icon.CHECK, 366, panelHeight - 45, 166, 27, false, module::applyTraining);
			button("training.spawn", Lang.t("ui.robottraining.spawn"), Icon.PLUS, 544, panelHeight - 45, 152, 27, true, module::spawnBatch);
		} finally { R2D.end(); endFrame(); }
	}
	private float y(float value) { return value - offset; }
	private void changeTab(int value) { tab = value; scroll.target = 0; scroll.value.snap(0); focused = null; drag = null; }
	private void overview() {
		boolean owner = module.trainingTarget.is("bot_target_owner");
		boolean all=module.trainingTarget.is("bot_target_all");
		button("training.target.all", Lang.t("ui.robottraining.withAll"), Icon.USER, 24, y(124), 216, 39, all,
				() -> module.chooseTarget("bot_target_all"));
		button("training.target.owner", Lang.t("ui.robottraining.withOwner"), Icon.USER, 252, y(124), 216, 39, owner,
				() -> module.chooseTarget("bot_target_owner"));
		button("training.target.robots", Lang.t("ui.robottraining.withRobots"), Icon.SWORD, 480, y(124), 216, 39, !owner&&!all,
				() -> module.chooseTarget("bot_target_robots"));
		c.textC(REG, Lang.t("ui.robottraining.currentTarget", module.trainingTarget.display()), 24, y(184), 11, Theme.TEXT_2);
		c.lens(24, y(204), W - 48, 74, 14, Theme.lift(.045f), Theme.chipGlass(c.scale));
		float ty = 223;
		for (String line : c.wrap(MED, notice(), 10.5f, W - 80)) { c.textC(MED, line, 40, y(ty), 10.5f, Theme.TEXT); ty += 15; }
		c.textC(REG, c.ellipsize(REG,Lang.t(all?"ui.robottraining.allRule":owner ? "ui.robottraining.ownerRule" : "ui.robottraining.robotRule"),9.5f,W-80), 40, y(261), 9.5f, Theme.TEXT_3);
		bool(module.trainingEnabled, 24, 293, W - 48, this::toggleTraining);
		if (!owner) button("training.addOpponent", Lang.t("ui.robottraining.addOne"), Icon.PLUS, 24, y(336), 238, 29, false, module::spawnOne);
		button("training.openManager", Lang.t("ui.robottraining.manage"), Icon.USER, owner ? 24 : 274, y(336), 190, 29, false, () -> changeTab(1));
		BotPlayers.Stats stats = BotPlayers.trainingStats();
		c.textC(REG, Lang.t("ui.robottraining.attacks", stats.attempts(), stats.hits()) + "  ·  " + Lang.t("ui.robottraining.damage", format(stats.dealt()), format(stats.received())), 24, y(393), 10.5f, Theme.TEXT_2);
		c.textC(REG, Lang.t("ui.robottraining.liveApplyHint"), 24, y(419), 9.5f, Theme.TEXT_3);
	}
	private String notice() {
		if (!BotPlayers.available()) return Lang.t("ui.robotplayers.needWorld");
		if (!module.trainingEnabled.on()) return Lang.t("ui.robottraining.paused");
		List<BotPlayers.Row> rows = BotPlayers.rows(BotPlayers.Group.TRAINING);
		if (rows.isEmpty()) return Lang.t("ui.robottraining.empty");
		if (module.trainingTarget.is("bot_target_robots") && rows.stream().filter(BotPlayers.Row::alive).count() < 2
				&& BotPlayers.pendingCount(BotPlayers.Group.TRAINING).equals("0")) return Lang.t("ui.robottraining.missingOpponent");
		for (BotPlayers.Row row : rows) if (row.activity().equals("bot_status_owner_unavailable") || row.activity().equals("bot_status_pvp_off")
				|| row.activity().equals("bot_status_pvp_blocked") || row.activity().equals("bot_status_target_far")||row.activity().equals("bot_status_follow_no_attack")) return Lang.mode(row.activity());
		return Lang.t("ui.robottraining.runningHint");
	}
	private void manager() {
		c.textC(REG, module.count.name(), 24, y(130), 10.5f, Theme.TEXT_2); textField(count, "2", null, 24, y(143), 280, 28);
		c.textC(REG, module.prefix.name(), 24, y(193), 10.5f, Theme.TEXT_2);
		c.textC(REG, module.randomId.name(), 142, y(193), 9.5f, Theme.TEXT_2); toggle("training.random", 273, y(185), module.randomId.on(), 1);
		hit(138, y(178), 166, 28).left(() -> { module.randomId.toggle(); if (module.randomId.on() && focused == prefix) focused = null; });
		if (module.randomId.on()) {
			c.lens(24, y(208), 280, 28, 14, Theme.lift(.025f), Theme.lensGlass(c.scale));
			c.textC(REG, c.ellipsize(REG, Lang.t("ui.robotplayers.randomIdHint"), 9.5f, 260), 34, y(222), 9.5f, Theme.TEXT_4);
		} else textField(prefix, "Training", Icon.USER, 24, y(208), 280, 28);
		mode(module.mode, 24, 260, 280);
		number(module.spacing, 24, 330, 280); number(module.spawnPerTick, 24, 389, 280);
		bool(module.autoRespawn, 24, 430, 280, module.autoRespawn::toggle);
		bool(module.trainingEnabled, 24, 473, 280, this::toggleTraining);
		actors();
		button("training.stopQueue", Lang.t("ui.robotplayers.stopQueue"), null, 24, y(516), 154, 28, false, () -> BotPlayers.stopQueue(BotPlayers.Group.TRAINING));
		button("training.removeAll", Lang.t("ui.robottraining.removeAll"), Icon.TRASH, 190, y(516), 238, 28, false, () -> BotPlayers.removeAll(BotPlayers.Group.TRAINING));
		button("training.reset", Lang.t("ui.robottraining.reset"), Icon.REFRESH, 440, y(516), 256, 28, false, BotPlayers::resetTrainingStats);
	}
	private void actors() {
		List<BotPlayers.Row> rows = BotPlayers.rows(BotPlayers.Group.TRAINING);
		int pages = Math.max(1, (rows.size() + PAGE_SIZE - 1) / PAGE_SIZE); page = Math.max(0, Math.min(page, pages - 1));
		c.lens(324, y(118), W - 348, 376, 16, Theme.lift(.025f), Theme.lensGlass(c.scale));
		for (int i = 0; i < PAGE_SIZE; i++) {
			int index = page * PAGE_SIZE + i; if (index >= rows.size()) break;
			BotPlayers.Row row = rows.get(index); float rowY = 138 + i * 39;
			c.textC(MED, c.ellipsize(MED, row.name(), 11, 245), 340, y(rowY), 11, Theme.TEXT);
			c.textC(REG, c.ellipsize(REG, Lang.mode(row.activity()), 9.5f, 264), 340, y(rowY + 15), 9.5f, Theme.TEXT_3);
			button("training.remove." + row.id(), Lang.t("ui.robotplayers.remove"), null, W - 100, y(rowY - 8), 64, 25, false, () -> BotPlayers.remove(BotPlayers.Group.TRAINING, row.id()));
		}
		if (rows.isEmpty()) {
			float emptyY = 153;
			for (String line : c.wrap(REG, Lang.t("ui.robottraining.empty"), 10.5f, 330)) { c.textC(REG, line, 340, y(emptyY), 10.5f, Theme.TEXT_3); emptyY += 15; }
		}
		c.textMid(REG, (page + 1) + " / " + pages, 510, y(471), 9.5f, Theme.TEXT_3);
		button("training.prev", "‹", null, 340, y(457), 64, 25, false, () -> page = Math.max(0, page - 1));
		button("training.next", "›", null, W - 100, y(457), 64, 25, false, () -> page = Math.min(pages - 1, page + 1));
	}
	private void controls() {
		mode(module.behavior, 24, 132, 310); mode(module.trainingTarget, 24, 192, 310); mode(module.trainingKit, 24, 252, 310);
		number(module.trainingRadius, 24, 337, 310); number(module.trainingSpeed, 24, 402, 310); number(module.attackInterval, 24, 467, 310);
		bool(module.trainingEnabled, 382, 130, 314, this::toggleTraining);
		bool(module.attackCooldown, 382, 177, 314, module.attackCooldown::toggle);
		bool(module.trainingShield, 382, 224, 314, module.trainingShield::toggle);
		if (module.trainingShield.on()) {
			mode(module.shieldMode, 382, 270, 314);
			if (!module.shieldMode.is("bot_shield_only")) number(module.shieldUptime, 382, 344, 314);
		}
		BotPlayers.Stats stats = BotPlayers.trainingStats();
		c.textC(MED, Lang.t("ui.robottraining.stats"), 382, y(394), 12, Theme.TEXT);
		c.textC(REG, Lang.t("ui.robottraining.attacks", stats.attempts(), stats.hits()), 382, y(424), 10.5f, Theme.TEXT_2);
		c.textC(REG, Lang.t("ui.robottraining.damage", format(stats.dealt()), format(stats.received())), 382, y(454), 10.5f, Theme.TEXT_2);
		c.textC(REG, Lang.t("ui.robottraining.deaths", stats.deaths()), 382, y(484), 10.5f, Theme.TEXT_2);
		float hintY = 527;
		for (String line : c.wrap(REG, Lang.t("ui.robottraining.shieldHint"), 10.5f, W - 48)) { c.textC(REG, line, 24, y(hintY), 10.5f, Theme.TEXT_3); hintY += 15; }
		hintY += 12;
		for (String line : c.wrap(REG, Lang.t("ui.robottraining.liveApplyHint"), 10.5f, W - 48)) { c.textC(REG, line, 24, y(hintY), 10.5f, Theme.TEXT_3); hintY += 15; }
	}
	private void toggleTraining() { module.trainingEnabled.toggle(); module.applyTraining(); }
	private static String format(double value) { return String.format(Locale.ROOT, "%.1f", value); }
	private void mode(ModeSetting setting, float x, float positionY, float width) {
		selectBox(setting, setting.name(), setting.display(), x, y(positionY), width, 30, false,
				() -> { int index = setting.modes.indexOf(setting.get()); setting.set(setting.modes.get((index + 1) % setting.modes.size())); module.applyTraining(); });
	}
	private void number(NumberSetting setting, float x, float positionY, float width) {
		c.textC(REG, setting.name() + "  " + setting.format(), x, y(positionY - 14), 10.5f, Theme.TEXT_2); slider(setting, x + 6, y(positionY + 7), width - 12);
	}
	private void bool(BoolSetting setting, float x, float positionY, float width, Runnable action) {
		c.textC(REG, setting.name(), x, y(positionY + 13), 10.5f, Theme.TEXT_2); toggle(setting, x + width - 31, y(positionY + 5), setting.on(), 1); hit(x, y(positionY), width, 28).left(action);
	}
	@Override public boolean mouseClicked(double x, double y, int button) { if (!dispatchClick(x, y, button)) focused = null; return true; }
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return dispatchScroll(x, y, vertical); }
	@Override public boolean keyPressed(int key, int scan, int modifiers) {
		if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
		if (tab == 1 && key == GLFW.GLFW_KEY_TAB) { focused = module.randomId.on() ? count : focused == count ? prefix : count; return true; }
		if (focused != null) { focused.keyPressed(key, modifiers); return true; } return false;
	}
	@Override public boolean charTyped(char chr, int modifiers) { return focused != null && focused.charTyped(chr); }
	@Override public boolean isPauseScreen() { return false; }
	@Override public void onClose() { mc.gui.setScreen(parent); }
}
