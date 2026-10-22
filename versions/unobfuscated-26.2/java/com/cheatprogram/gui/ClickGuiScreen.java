package com.cheatprogram.gui;



import com.cheatprogram.ai.AiConfig;

import com.cheatprogram.ai.AiController;

import com.cheatprogram.ai.AiModels;

import com.cheatprogram.core.Category;

import com.cheatprogram.core.ClientSettings;

import com.cheatprogram.core.Config;

import com.cheatprogram.core.Friends;

import com.cheatprogram.core.Lang;

import com.cheatprogram.core.Module;

import com.cheatprogram.core.ModuleManager;

import com.cheatprogram.core.Notifications;

import com.cheatprogram.core.setting.BoolSetting;

import com.cheatprogram.core.setting.ModeSetting;

import com.cheatprogram.core.setting.NumberSetting;

import com.cheatprogram.core.setting.RangeSetting;

import com.cheatprogram.core.setting.Setting;

import com.cheatprogram.core.setting.TextSetting;

import com.cheatprogram.render.Backdrop;

import com.cheatprogram.module.visual.ControlPanel;

import com.cheatprogram.module.visual.BlueArchiveHalo;

import com.cheatprogram.module.visual.PanelTransitions;

import com.cheatprogram.module.visual.Personal;

import com.cheatprogram.render.HaloModelPreview;

import com.cheatprogram.render.PanelTransitionRenderer;

import com.cheatprogram.render.ControlPanelBackground;

import com.cheatprogram.render.ControlPanelTextures;

import com.cheatprogram.render.Fonts;

import com.cheatprogram.render.Icon;

import com.cheatprogram.render.R2D;

import com.cheatprogram.render.Shaders;

import com.cheatprogram.util.Anim;

import com.cheatprogram.util.KeyNames;

import net.minecraft.util.Util;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.minecraft.world.entity.player.Player;

import net.minecraft.world.phys.EntityHitResult;

import org.lwjgl.glfw.GLFW;



import java.text.SimpleDateFormat;

import java.util.ArrayList;

import java.util.Date;

import java.util.HashMap;

import java.util.List;

import java.util.Locale;

import java.util.Map;



/** The main click GUI: header with tabs, category sidebar, searchable module list, settings panel and drawer. */

public class ClickGuiScreen extends GuiBase {

	private boolean worldEmbedded;

	public boolean worldEmbedded(){return worldEmbedded;}

	public boolean voiceKeyAllowed(){return tab==Tab.AI&&!closing&&!drawer&&binding==null&&focused==null&&!aiModelPicker;}

	public boolean capturesWorldKeyboard(){return focused!=null||binding!=null;}

	public void suspendWorldInput(){drag=null;binding=null;focused=null;dropdown=null;aiModelPicker=false;aiPlayersPanel.deactivate();aiKey.masked=true;}

	public void setWorldEmbedded(boolean value){worldEmbedded=value;closing=false;open.snap(value?1:0);}

	static final float W = 720;

	static final float H = 452;

	static final float R = 26;

	private static final float HEADER = 82;

	private static final float SIDEBAR_X = 14;

	private static final float SIDEBAR_W = 190;

	private static final float CONTENT_X = 216;

	private static final float PANEL_W = 264;

	private static final float DRAWER_W = 276;

	private static final float ROW_H = 42;

	private static final float ROW_GAP = 6;

	private static final Object BIND_GUI = new Object();



	private enum Tab {MODULES, FRIENDS, PROFILES, AI, AI_PLAYERS}



	private static Tab tab = Tab.MODULES;

	private static Category category = Category.VISUAL;



	private final TextField search = new TextField(40);

	private final TextField friendField = new TextField(16);

	private final TextField profileField = new TextField(32);

	private final TextField aiEndpoint = new TextField(512);

	private final TextField aiModelSearch = new TextField(128);

	private final Scroller aiModelScroll = new Scroller();

	private boolean aiModelPicker;

	private final TextField aiKey = new TextField(512);

	private final TextField aiRequest = new TextField();

	private final AiPlayersPanel aiPlayersPanel = new AiPlayersPanel(this);

	private final Scroller aiScroll = new Scroller();

	private List<AiController.Message> aiWrappedMessages = List.of();

	private final List<List<String>> aiMessageLines = new ArrayList<>();

	private float aiWrapScale = -1;

	private int aiMessageCount;

	@Override protected void onTypographyChanged(){

		aiWrapScale=-1;aiWrappedMessages=List.of();aiMessageLines.clear();

	}

	private final Map<TextSetting, TextField> settingFields = new HashMap<>();

	private final Scroller listScroll = new Scroller();

	private final Scroller panelScroll = new Scroller();

	private final Scroller drawerScroll = new Scroller();

	private final Scroller friendScroll = new Scroller();

	private final Scroller profileScroll = new Scroller();

	private final Scroller dropdownScroll = new Scroller();

	private final Anim open = new Anim(0);

	private final HaloModelPreview haloPreview = new HaloModelPreview();

	private final com.cheatprogram.render.WearableWeaponsPreview wearablePreview = new com.cheatprogram.render.WearableWeaponsPreview();

	private final PanelTransitionRenderer transitions = new PanelTransitionRenderer();

	private boolean customTransition;

	private long haloClickAt;

	private final long openedAt = System.currentTimeMillis();



	private Module selected;

	private Module lastSelected;

	private boolean drawer;

	private String drawerPage = "root";

	private ModeSetting dropdown;

	private ModeSetting lastDropdown;

	private float ddX, ddY, ddW;

	private Object binding;

	private boolean closing;

	private boolean backgroundOpeningPending;

	private boolean backgroundWasActive;

	private long listSwitch = System.currentTimeMillis();

	private Object tooltipKey;

	private long tooltipSince;

	private String tooltipText;

	private boolean tooltipSeen;

	private float tabTargetX = 14;

	private float tabTargetW = 74;

	private List<Config.ProfileInfo> profiles;

	private long profilesLoaded;

	private float devHoverX = Float.NaN;

	private float devHoverY = Float.NaN;



	public ClickGuiScreen() {

		super("Cheat Program");

		search.onChange = s -> {

			listScroll.target = 0;

			listSwitch = System.currentTimeMillis() - 120;

		};

		friendField.onEnter = this::addFriendFromField;

		profileField.onEnter = this::createProfileFromField;

		AiConfig ai = AiController.config();

		aiEndpoint.setText(ai.endpoint());

		aiKey.setText(ai.apiKey());

		aiKey.masked = true;

		aiEndpoint.onChange = value -> { if (AiController.isRunning()) AiController.stop(); ai.endpoint(value); aiKey.setText(ai.apiKey());AiModels.clear(); };

		aiKey.onChange = value -> { if (AiController.isRunning()) AiController.stop(); ai.apiKey(value); AiModels.keyChanged(); };

		aiModelSearch.onChange = value -> { aiModelScroll.target = 0; aiModelScroll.value.snap(0); };

		aiRequest.onEnter = this::submitAiRequest;

	}



	// ================================================================ frame

	@Override

	protected void init() {

		super.init();

		ControlPanel panel=ModuleManager.get(ControlPanel.class);

		boolean active=panel!=null&&panel.isEnabled()&&!panel.imagePath().isBlank();

		ControlPanelTextures.setGuiVisible(active);

		if(active) ControlPanelTextures.warm(panel.imagePath());

		backgroundWasActive=active;

		backgroundOpeningPending=active&&!ControlPanelTextures.settled(panel.imagePath());

	}



	@Override

	public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {

		if(worldEmbedded&&!com.cheatprogram.render.WorldControlPanel.enabled())setWorldEmbedded(false);

		beginFrame();

		ControlPanel background=ModuleManager.get(ControlPanel.class);

		boolean backgroundActive=background!=null&&background.isEnabled()&&!background.imagePath().isBlank();

		ControlPanelTextures.setGuiVisible(backgroundActive);

		if(backgroundActive&&!backgroundWasActive) {

			ControlPanelTextures.warm(background.imagePath());

			backgroundOpeningPending=!ControlPanelTextures.settled(background.imagePath());

		}

		backgroundWasActive=backgroundActive;

		if(backgroundOpeningPending&&!closing&&backgroundActive&&!ControlPanelTextures.settled(background.imagePath())) {

			if(mc.level==null) extractPanorama(graphics,partialTick);

			graphics.centeredText(mc.font,Lang.t("ui.panel.loading"),width/2,height/2,0xFFFFFFFF);

			endFrame();

			return;

		}

		backgroundOpeningPending=false;

		float o = open.to(closing ? 0f : 1f, dt, closing ? 13f : 7.5f);

		PanelTransitions animation=ModuleManager.get(PanelTransitions.class);

		boolean transitionRequested=!worldEmbedded&&animation!=null&&animation.isEnabled();

		if (!transitionRequested && closing && o < 0.03f) {

			mc.gui.setScreen(null);

			return;

		}

		if (mc.level == null) extractPanorama(graphics, partialTick);

		AiController.markAiScreen(tab == Tab.AI && !closing && !drawer ? this : null);



		R2D.begin(graphics);

		int fbW = R2D.width();

		int fbH = R2D.height();

		float auto = Math.min(fbW / 1060f, fbH / 760f);

		float scale = Math.max(0.55f, auto * (float) (ClientSettings.INSTANCE.uiScale.val() / 100.0));

		c.set(scale, Math.round((fbW - W * scale) / 2f), Math.round((fbH - H * scale) / 2f));

		updateMouse();

		if (!System.getProperty("cheatprogram.dev", "").isEmpty() && !Float.isNaN(devHoverX)) {

			mx = devHoverX;

			my = devHoverY;

		}

		tooltipSeen = false;

		customTransition=transitionRequested&&transitions.prepare(fbW,fbH,c.originX,c.originY,W*c.scale,H*c.scale,

				HEADER*c.scale,R*c.scale,animation.opening(),animation.closing(),animation.page(),animation.strength());

		if(customTransition)transitions.update(closing,o>=.95f,dt*Anim.globalSpeed(),animation.openDuration(),animation.closeDuration(),animation.pageDuration());

		else transitions.close();

		if(closing&&(customTransition?transitions.closingFinished():o<.03f)) {

			R2D.end();mc.gui.setScreen(null);return;

		}



		float ease = Anim.easeOutCubic(o);

		int backdrop = Backdrop.capture(Theme.backgroundBlur());

		R2D.applyState();

		Backdrop.drawFullscreen(Shaders.BACKDROP, backdrop,

				0.05f + 0.14f * (1f - Theme.transparency()),

				(float) ClientSettings.INSTANCE.desaturate.fraction(),

				0.35f, ease);

		if(customTransition){com.cheatprogram.compat.GuiBridge.flush(graphics);transitions.captureUnderlay();R2D.applyState();}



		c.push();

		float pop = closing ? 0.96f + 0.04f * ease : 0.93f + 0.07f * Anim.easeOutBack(o);

		if(!customTransition&&!worldEmbedded) {

			c.scaleAround(pop, W / 2f, H / 2f);

			c.translate(0, (1f - ease) * 12f);

		}

		R2D.pushAlpha(customTransition?1f:ease);



		Backdrop.capture(Theme.frost());

		R2D.applyState();

		c.shadow(0, 10, W, H, R, 44, Anim.black(0.30f));

		float tint = Theme.windowTint();

		c.glass(0, 0, W, H, R, Anim.black(tint * 0.92f), Anim.black(Math.min(1f, tint * 1.08f)), Theme.windowGlass(c.scale));

		if (ControlPanelBackground.draw(graphics,c,ModuleManager.get(ControlPanel.class),W,H,R)) Backdrop.capture(0f);

		R2D.applyState();



		float drawerT = anim("drawer", drawer ? 1f : 0f, drawer ? 11f : 15f);

		hoverBlocked = drawer || dropdown != null || aiModelPicker || aiPlayersPanel.overlayOpen();

		R2D.pushAlpha(1f - 0.88f * drawerT);

		header();

		switch (tab) {

			case MODULES -> modulesTab(graphics);

			case FRIENDS -> {}

			case PROFILES -> profilesTab();

			case AI -> aiTab();

			case AI_PLAYERS -> aiPlayersPanel.render();

		}

		R2D.popAlpha();

		hoverBlocked = false;



		drawer(drawerT);

		dropdownOverlay();

		aiModelsOverlay();

		if (tab == Tab.AI_PLAYERS && !drawer && !closing) aiPlayersPanel.renderOverlay();

		tooltip();

		if(worldEmbedded){c.textC(REG,Lang.t("ui.worldpanel.exitHint",com.cheatprogram.util.KeyNames.name(com.cheatprogram.core.ClientSettings.INSTANCE.guiKey)),26,94,8,Theme.TEXT_3);if(mx>=0&&my>=0&&mx<W&&my<H){c.circle(mx,my,2.5f,0xff151515);c.circle(mx,my,1.8f,0xffffffff);}}

		R2D.popAlpha();

		c.pop();

		if(customTransition){com.cheatprogram.compat.GuiBridge.flush(graphics);transitions.composite();}

		R2D.end();

		endFrame();

	}



	// ================================================================ header



	private void header() {

		float x = 26;

		float base = 37;

		float w1 = c.text(Fonts.Face.BLACK, "Cheat", x, base, 21, Theme.TEXT);

		float w2 = c.text(Fonts.Face.LIGHT, "Program", x + w1 + 5, base, 21, Theme.TEXT_2);

		String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();

		float bx = x + w1 + 5 + w2 + 10;

		float bw = c.textWidth(MED, version, 7.5f) + 11;

		c.lens(bx, base - 14, bw, 14, 7, Theme.lift(0.12f), Theme.lensGlass(c.scale));

		c.textMid(MED, version, bx + bw / 2f, base - 7, 7.5f, Theme.TEXT_2);



		iconButton("hdr.layout", Icon.LAYOUT, W - 70, 29, 15, Theme.TEXT_2, () -> mc.gui.setScreen(new HudEditorScreen(this)));

		if (over(W - 84, 15, 28, 28)) requestTooltip("tip.layout", Lang.t("ui.tip.layout"));

		iconButton("hdr.gear", Icon.GEAR, W - 37, 29, 16, Theme.TEXT_2, () -> {

			aiModelPicker = false;

			aiPlayersPanel.deactivate();

			drawer = !drawer;

			drawerPage = "root";

			drawerScroll.target = 0;

		});



		float tx = 26;

		float cy = 64;

		float pillX = anim("tab.x", tabTargetX, 15f);

		float pillW = anim("tab.w", tabTargetW, 15f);

		c.lens(pillX, cy - 12, pillW, 24, 12, Theme.lift(0.13f), Theme.lensGlass(c.scale));

		for (Tab t : new Tab[]{Tab.MODULES, Tab.PROFILES, Tab.AI, Tab.AI_PLAYERS}) {

			String label = Lang.t(switch (t) {

				case MODULES -> "ui.tab.modules";

				case FRIENDS -> "ui.tab.friends";

				case PROFILES -> "ui.tab.profiles";

				case AI -> "ui.tab.ai";

				case AI_PLAYERS -> "ui.tab.aiPlayers";

			});

			float tw = c.textWidth(MED, label, 11.5f);

			boolean active = tab == t;

			boolean hovered = over(tx - 8, cy - 14, tw + 16, 28);

			float a = anim("tab." + t, active ? 1f : hovered ? 0.45f : 0f, 14f);

			c.textC(MED, label, tx, cy, 11.5f, Anim.lerpColor(Theme.TEXT_3, Theme.TEXT, a));

			if (active) {

				tabTargetX = tx - 12;

				tabTargetW = tw + 24;

			}

			hit(tx - 8, cy - 14, tw + 16, 28).left(() -> switchTab(t));

			tx += tw + 30;

		}

	}



	private void switchTab(Tab t) {
        if(t == Tab.FRIENDS)return;

		if (tab == t) return;

		beginPageTransition();

		tab = t;

		aiModelPicker = false;

		aiPlayersPanel.deactivate();

		focused = null;

		dropdown = null;

		listSwitch = System.currentTimeMillis()-(customTransition?10000:0);

		if (t == Tab.PROFILES) profiles = null;

		if (t == Tab.AI || t == Tab.AI_PLAYERS) { selected = null; lastSelected = null; binding = null; }

		AiController.markAiScreen(t == Tab.AI ? this : null);

	}



	// ================================================================ AI control



	private void aiTab() {

		AiConfig cfg = AiController.config();

		AiController.State state = AiController.state();

		syncAiField(aiEndpoint, cfg.endpoint());

		syncAiField(aiKey, cfg.apiKey());

		float lx = 16, lw = 218, top = 96, bottom = H - 16;

		c.lens(lx, top, lw, bottom - top, 18, Theme.lift(0.045f), Theme.lensGlass(c.scale));

		c.textC(BOLD, Lang.t("ui.ai.connection"), lx + 14, top + 20, 12, Theme.TEXT);

		AiModels.State catalog = AiModels.state();

		button("ai.openai", "OpenAI", null, lx + 12, top + 40, 61, 25, "openai".equals(catalog.provider()), () -> aiPreset("openai"));

		button("ai.deepseek", "DeepSeek", null, lx + 77, top + 40, 67, 25, "deepseek".equals(catalog.provider()), () -> aiPreset("deepseek"));

		button("ai.custom", Lang.t("ui.ai.custom"), null, lx + 148, top + 40, 58, 25, "custom".equals(catalog.provider()), () -> aiPreset("custom"));

		c.textC(REG, Lang.t("ui.ai.endpoint"), lx + 14, top + 83, 10, Theme.TEXT_2);

		textField(aiEndpoint, Lang.t("ui.ai.endpointHint"), null, lx + 12, top + 95, lw - 24, 29);

		c.textC(REG, Lang.t("ui.ai.model"), lx + 14, top + 143, 10, Theme.TEXT_2);

		button("ai.models.refresh", Lang.t("ui.ai.refresh"), null, lx + 153, top + 133, 53, 20, false, AiModels::refresh);

		c.lens(lx + 12, top + 155, lw - 24, 29, 14.5f, Theme.lift(.07f), Theme.lensGlass(c.scale));

		String modelLabel = catalog.models().isEmpty() ? Lang.t("ui.ai.chooseModel") : cfg.model();

		c.textC(REG, c.ellipsize(REG, modelLabel, 10, lw - 53), lx + 22, top + 169.5f, 10, Theme.TEXT_2);

		c.icon(Icon.CHEVRON_DOWN, lx + lw - 24, top + 169.5f, 11, Theme.TEXT_3);

		hit(lx + 12, top + 155, lw - 24, 29).left(() -> { aiModelPicker = true; aiModelSearch.setText(""); aiModelScroll.target = 0; focused = aiModelSearch; });

		c.textC(REG, c.ellipsize(REG, catalog.status(), 8, lw - 28), lx + 14, top + 191, 8, Theme.TEXT_3);

		c.textC(REG, "API Key", lx + 14, top + 203, 10, Theme.TEXT_2);

		button("ai.key.mask", Lang.t(aiKey.masked ? "ui.ai.showKey" : "ui.ai.hideKey"), Icon.EYE,

				lx + 147, top + 194, 59, 20, false, () -> aiKey.masked = !aiKey.masked);

		textField(aiKey, Lang.t("ui.ai.keyHint"), null, lx + 12, top + 215, lw - 24, 29);

		c.textC(REG, c.ellipsize(REG,Lang.t("ui.ai.keyMemory"),8.5f,lw-28), lx + 14, top + 255, 8.5f, Theme.TEXT_3);

		c.textC(REG,c.ellipsize(REG,Lang.t(cfg.credentialStatusKey()),8,lw-28),lx+14,top+268,8,Theme.TEXT_3);

		button("ai.key.clear", Lang.t("ui.ai.clearKey"), null, lx + 12, top + 278, 94, 25, false, () -> {

			AiController.stop(); cfg.deleteApiKey(); aiKey.setText(""); aiKey.masked = true; AiModels.clear();

		});

		button("ai.config.save", Lang.t("ui.ai.save"), null, lx + 112, top + 278, 94, 25, false, cfg::save);

		c.textC(REG, Lang.t("ui.ai.singleplayer"), lx + 14, bottom - 18, 8.5f, Theme.TEXT_3);



		float rx = 246, rw = W - rx - 16;

		c.lens(rx, top, rw, bottom - top, 18, Theme.lift(0.045f), Theme.lensGlass(c.scale));

		c.textC(BOLD, Lang.t("ui.tab.ai"), rx + 14, top + 20, 12, Theme.TEXT);

		if(state.running())com.cheatprogram.hud.AiVoiceHud.orb(c,rx+rw-197,top+21,7,AiController.isWaitingForModel()?"thinking":"executing",0,System.nanoTime()/1_000_000_000.0);

		button("ai.stop", Lang.t("ui.ai.stop"), Icon.POWER, rx + rw - 85, top + 8, 71, 25, state.running(), AiController::stop);

		String status = state.status() + (state.steps() > 0 ? " · " + Lang.t("ui.ai.steps", state.steps()) : "");

		c.textC(REG, c.ellipsize(REG, status, 9, rw - 28), rx + 14, top + 45, 9, Theme.TEXT_2);

		float chatTop = top + 61, chatH = 204;

		List<AiController.Message> messages = AiController.messagesView();

		boolean conversationChanged = !messages.equals(aiWrappedMessages);

		if (conversationChanged || aiWrapScale != c.scale) {

			int prefix = 0;

			if (aiWrapScale == c.scale) {

				int common = Math.min(messages.size(), aiWrappedMessages.size());

				while (prefix < common && messages.get(prefix).equals(aiWrappedMessages.get(prefix))) prefix++;

			}

			while (aiMessageLines.size() > prefix) aiMessageLines.removeLast();

			for (int i = prefix; i < messages.size(); i++) aiMessageLines.add(c.wrap(REG, messages.get(i).text(), 9.5f, rw - 56));

			aiWrappedMessages = List.copyOf(messages);

			aiWrapScale = c.scale;

		}

		float contentH = 0;

		for (List<String> lines : aiMessageLines) contentH += 25 + Math.max(1, lines.size()) * 13 + 7;

		aiScroll.max = Math.max(0, contentH - chatH);

		if (conversationChanged || messages.size() != aiMessageCount) { aiScroll.target = aiScroll.max; aiMessageCount = messages.size(); }

		aiScroll.update(dt);

		hit(rx + 8, chatTop, rw - 16, chatH).scroll(aiScroll::scroll);

		c.clip(rx + 8, chatTop, rw - 16, chatH);

		float rowY = chatTop - aiScroll.get();

		if (messages.isEmpty()) {

			float yy = chatTop + 25;

			for (String line : c.wrap(REG, Lang.t("ui.ai.welcome"), 10.5f, rw - 40)) {

				c.textC(REG, line, rx + 20, yy, 10.5f, Theme.TEXT_3); yy += 19;

			}

		} else for (int i = 0; i < messages.size(); i++) {

			AiController.Message message = messages.get(i);

			List<String> lines = aiMessageLines.get(i);

			float rh = 25 + Math.max(1, lines.size()) * 13;

			if (rowY + rh >= chatTop && rowY < chatTop + chatH) {

				boolean user = message.role().equals("user");

				c.lens(rx + 12, rowY, rw - 24, rh, 12, Theme.lift(user ? 0.1f : 0.055f), Theme.lensGlass(c.scale));

				c.textC(MED, Lang.t(user ? "ui.ai.you" : "ui.ai.assistant"), rx + 23, rowY + 12, 8.5f, Theme.TEXT_3);

				int firstLine = Math.max(0, (int) Math.floor((chatTop - rowY - 35) / 13));

				int endLine = Math.min(lines.size(), (int) Math.ceil((chatTop + chatH - rowY - 22) / 13));

				for (int line = firstLine; line < endLine; line++)

					c.textC(REG, lines.get(line), rx + 23, rowY + 29 + line * 13, 9.5f, Theme.TEXT_2);

			}

			rowY += rh + 7;

		}

		c.unclip();

		String action = state.currentAction().isBlank() ? Lang.t("ui.ai.actionIdle") : state.currentAction();

		c.textC(REG, c.ellipsize(REG, action, 8.5f, rw - 28), rx + 14, top + 281, 8.5f, Theme.TEXT_3);

		textField(aiRequest, Lang.t("ui.ai.requestHint"), null, rx + 12, bottom - 43, rw - 90, 29);

		button("ai.send", Lang.t("ui.ai.send"), Icon.CHEVRON_RIGHT, rx + rw - 69, bottom - 43, 57, 29, true, this::submitAiRequest);

	}



	private void syncAiField(TextField field, String value) {

		if (focused != field && !field.text.equals(value)) field.setText(value);

	}



	private void aiPreset(String preset) {

		AiController.stop();

		AiConfig cfg = AiController.config();

		if (!"custom".equals(preset)) cfg.preset(preset);

		aiKey.setText(cfg.apiKey());

		aiKey.masked = true;

		aiEndpoint.setText(cfg.endpoint());

		AiModels.selectProvider(preset);

		aiModelPicker = false;

		cfg.save();

		focused = aiKey;

	}



	private void aiModelsOverlay() {

		if (!aiModelPicker || tab != Tab.AI || drawer || closing) return;

		float x = 244, y = 103, w = 456, h = 319;

		// Consume outside clicks and scrolling so they cannot edit the connection behind the picker.

		c.rrect(0, HEADER, W, H - HEADER, 0, Anim.black(.16f));

		hit(0, 0, W, H).click(button -> { aiModelPicker = false; focused = null; }).scroll(amount -> { });

		c.shadow(x, y + 5, w, h, 18, 24, Anim.black(.22f));

		c.lens(x, y, w, h, 18, Theme.lift(.12f), Theme.lensGlass(c.scale));

		hit(x, y, w, h).click(button -> { }).scroll(amount -> { });

		c.textC(BOLD, Lang.t("ui.ai.modelsTitle"), x + 16, y + 21, 12, Theme.TEXT);

		iconButton("ai.models.close", Icon.CLOSE, x + w - 20, y + 21, 12, Theme.TEXT_2, () -> { aiModelPicker = false; focused = null; });

		textField(aiModelSearch, Lang.t("ui.ai.modelSearch"), Icon.SEARCH, x + 12, y + 39, w - 24, 28);

		AiModels.State catalog = AiModels.state();

		c.textC(REG, c.ellipsize(REG, catalog.status(), 8.5f, w - 28), x + 14, y + 81, 8.5f, Theme.TEXT_3);

		String query = aiModelSearch.text.strip().toLowerCase(Locale.ROOT);

		List<AiModels.Model> models = catalog.models().stream().filter(model -> model.id().toLowerCase(Locale.ROOT).contains(query)

				|| model.name().toLowerCase(Locale.ROOT).contains(query)).toList();

		float listY = y + 96, listH = h - 108, rowH = 39;

		aiModelScroll.max = Math.max(0, models.size() * rowH - listH);

		aiModelScroll.update(dt);

		hit(x + 10, listY, w - 20, listH).scroll(aiModelScroll::scroll);

		c.clip(x + 10, listY, w - 20, listH);

		pushHitClip(x + 10, listY, w - 20, listH);

		if (models.isEmpty()) c.textC(REG, Lang.t(catalog.loading() ? "ui.ai.modelsLoading" : "ui.ai.modelsEmpty"), x + 20, listY + 22, 10, Theme.TEXT_3);

		float yy = listY - aiModelScroll.get();

		for (AiModels.Model model : models) {

			if (yy + rowH >= listY && yy < listY + listH) {

				boolean selectedModel = model.id().equals(AiController.config().model());

				if (selectedModel || model.usable() && over(x + 12, yy, w - 24, rowH - 3))

					c.lens(x + 12, yy, w - 24, rowH - 3, 11, Theme.lift(selectedModel ? .14f : .07f), Theme.lensGlass(c.scale));

				c.textC(MED, c.ellipsize(MED, model.name(), 10.5f, w - 54), x + 23, yy + 13, 10.5f, model.usable() ? Theme.TEXT : Theme.TEXT_4);

				c.textC(REG, c.ellipsize(REG, model.tier() + (model.name().equals(model.id()) ? "" : " · " + model.id()), 8, w - 54), x + 23, yy + 27, 8, Theme.TEXT_3);

				if (model.usable()) hit(x + 12, yy, w - 24, rowH - 3).left(() -> {

					if (AiController.isRunning()) AiController.stop();

					AiController.config().model(model.id()); AiController.config().save(); aiModelPicker = false; focused = null;

				});

				else hit(x + 12, yy, w - 24, rowH - 3).click(button -> { });

			}

			yy += rowH;

		}

		popHitClip(); c.unclip();

	}



	private void submitAiRequest() {

		AiModels.State catalog = AiModels.state();

		if (catalog.needsProviderChoice() || catalog.loading() || catalog.models().stream().noneMatch(model -> model.usable() && model.id().equals(AiController.config().model()))) {

			Notifications.push(Lang.t("ui.tab.ai"), Lang.t("ui.ai.modelsRequired"), false);

			return;

		}

		String request = aiRequest.text.trim();

		if (!request.isEmpty() && AiController.submit(request)) { aiRequest.setText(""); focused = null; aiScroll.target = Float.MAX_VALUE; }

	}



	// ================================================================ modules tab



	private void modulesTab(GuiGraphicsExtractor graphics) {

		float panelT = anim("panel", selected != null ? 1f : 0f, selected != null ? 10f : 13f);

		float e = Anim.easeOutCubic(panelT);

		sidebar(1f - e, e * 24f);

		float x0 = Anim.lerp(CONTENT_X, 16, e);

		float x1 = Anim.lerp(W - 18, W - PANEL_W - 12, e);

		moduleList(x0, x1, panelT);

		settingsPanel(graphics,panelT);

	}



	private void sidebar(float alpha, float shift) {

		if (alpha < 0.01f) return;

		R2D.pushAlpha(alpha);

		c.push();

		c.translate(-shift, 0);

		c.text(MED, Lang.t("ui.modules.title"), SIDEBAR_X + 12, 113, 15, Theme.TEXT);

		float top = 126;

		float itemH = 31;

		float step = 33;

		boolean searching = !search.text.isEmpty();

		float pillY = anim("cat.pill", top + (category == Category.FAVORITES ? 0 : 1) * step, 15f);

		float pillA = anim("cat.pillA", searching ? 0.35f : 1f, 12f);

		R2D.pushAlpha(pillA);

		c.lens(SIDEBAR_X, pillY, SIDEBAR_W, itemH, itemH / 2f, Theme.lift(0.15f), Theme.lensGlass(c.scale));

		R2D.popAlpha();



		float y = top;

		for (Category cat : new Category[]{Category.FAVORITES, Category.VISUAL}) {

			boolean sel = cat == category && !searching;

			boolean hovered = over(SIDEBAR_X, y, SIDEBAR_W, itemH);

			float ha = hoverAnim("cat." + cat, hovered && !sel);

			if (ha > 0.01f) c.rrect(SIDEBAR_X, y, SIDEBAR_W, itemH, itemH / 2f, Anim.white(0.06f * ha));

			float sa = anim("cat.sel." + cat, sel ? 1f : 0f, 14f);

			int color = Anim.lerpColor(Anim.lerpColor(Theme.TEXT_3, Theme.TEXT_2, ha), Theme.TEXT, sa);

			Icon icon = cat == Category.FAVORITES && sel ? Icon.STAR_FILLED : cat.icon;

			c.icon(icon, SIDEBAR_X + 19, y + itemH / 2f, 14, color);

			c.textC(sa > 0.5f ? MED : REG, (cat == Category.FAVORITES ? cat.displayName() : Lang.t("ui.tab.modules")), SIDEBAR_X + 37, y + itemH / 2f, 11, color);

			float right = SIDEBAR_X + SIDEBAR_W - 26;

			int count = cat == Category.FAVORITES ? ModuleManager.byCategory(cat).size() : ModuleManager.enabledCount(cat);

			if (count > 0) {

				String text = String.valueOf(count);

				float bw = Math.max(16, c.textWidth(MED, text, 8) + 9);

				badge(text, right - bw, y + itemH / 2f, 8, Anim.white(0.07f + 0.08f * sa), Anim.white(0.16f + 0.1f * sa), sel ? Theme.TEXT : Theme.TEXT_3);

			}

			c.icon(Icon.CHEVRON_RIGHT, SIDEBAR_X + SIDEBAR_W - 14, y + itemH / 2f, 10, sel ? Theme.TEXT_2 : Theme.TEXT_4);

			final Category target = cat;

			hit(SIDEBAR_X, y, SIDEBAR_W, itemH).left(() -> selectCategory(target));

			y += step;

		}

		c.pop();

		R2D.popAlpha();

	}



	private void selectCategory(Category cat) {

		if (cat == category && search.text.isEmpty()) return;

		beginPageTransition();

		category = cat;

		search.setText("");

		focused = null;

		listScroll.target = 0;

		listScroll.value.snap(0);

		listSwitch = System.currentTimeMillis()-(customTransition?10000:0);

	}



	private List<Module> visibleModules() {

		String q = search.text.trim().toLowerCase(Locale.ROOT);

		if (q.isEmpty()) return ModuleManager.byCategory(category);

		List<Module> out = new ArrayList<>();

		for (Module m : ModuleManager.all()) {

			if (m.name().toLowerCase(Locale.ROOT).contains(q) || m.id.contains(q)

					|| m.category.displayName().toLowerCase(Locale.ROOT).contains(q)) out.add(m);

		}

		return out;

	}



	private void moduleList(float x0, float x1, float panelT) {

		textField(search, Lang.t("ui.search"), Icon.SEARCH, x0, 96, x1 - x0, 28);

		if (!search.text.isEmpty()) {

			iconButton("search.clear", Icon.CLOSE, x1 - 15, 110, 10, Theme.TEXT_3, () -> search.setText(""));

		}



		float top = 134;

		float bottom = H - 12;

		List<Module> modules = visibleModules();

		float contentH = Math.max(0, modules.size() * (ROW_H + ROW_GAP) - ROW_GAP);

		listScroll.max = Math.max(0, contentH - (bottom - top));

		listScroll.update(dt);

		hit(x0, top, x1 - x0, bottom - top).scroll(listScroll::scroll);



		float elapsed = (System.currentTimeMillis() - listSwitch) / 1000f;

		c.clip(x0 - 3, top - 3, x1 - x0 + 6, bottom - top + 6);

		pushHitClip(x0 - 3, top - 3, x1 - x0 + 6, bottom - top + 6);

		float y = top - listScroll.get();

		for (int i = 0; i < modules.size(); i++) {

			Module m = modules.get(i);

			if (y + ROW_H >= top - 6 && y <= bottom + 6) {

				float appear = Anim.easeOutCubic((elapsed - i * 0.03f) / 0.26f);

				float edge = Anim.clamp01(Math.min((y + ROW_H - top) / 20f + 0.15f, (bottom - y) / 20f + 0.15f));

				float dim = selected != null && selected != m ? Anim.lerp(1f, 0.36f, panelT) : 1f;

				R2D.pushAlpha(appear * edge * dim);

				moduleRow(m, x0, y + (1f - appear) * 12f, x1 - x0);

				R2D.popAlpha();

			}

			y += ROW_H + ROW_GAP;

		}

		popHitClip();

		c.unclip();



		if (listScroll.max > 0) {

			float vh = bottom - top;

			float th = Math.max(26, vh * vh / (contentH + 1));

			float ty = top + (vh - th) * (listScroll.get() / listScroll.max);

			c.rrect(x1 + 5, ty, 2.5f, th, 1.25f, Anim.white(0.20f));

		}



		if (modules.isEmpty()) {

			float cx = (x0 + x1) / 2f;

			float cy = (top + bottom) / 2f - 10;

			boolean fav = search.text.isEmpty() && category == Category.FAVORITES;

			c.icon(fav ? Icon.STAR : Icon.SEARCH, cx, cy - 16, 26, Theme.TEXT_4);

			c.textMid(MED, Lang.t(fav ? "ui.empty.favorites" : "ui.empty.search"), cx, cy + 12, 12, Theme.TEXT_2);

			if (fav) c.textMid(REG, Lang.t("ui.empty.favorites.hint"), cx, cy + 30, 9.5f, Theme.TEXT_3);

		}

	}



	private void moduleRow(Module m, float x, float y, float w) {

		float h = ROW_H;

		boolean hovered = over(x, y, w, h);

		boolean on = m.isEnabled();

		float ha = hoverAnim(m, hovered);

		float ea = anim("en." + m.id, on ? 1f : 0f, 14f);

		float sa = anim("sel." + m.id, selected == m ? 1f : 0f, 14f);



		hit(x, y, w, h).click(b -> {

			if (b == 0) toggleModule(m);

			else if (b == 1) openSettings(m);

			else if (b == 2) startBinding(m);

		});



		// Enabled rows keep the original enabled glass, even while hovered or selected.

		// Fade the extra interaction lift away with the enable animation instead of stacking white tint.

		float interactionLift = (0.035f * ha + 0.05f * sa) * (1f - ea);

		c.lens(x, y, w, h, h / 2f, Theme.lift(0.06f + 0.05f * ea + interactionLift), Theme.lensGlass(c.scale));



		float dotX = x + 14;

		if (ea > 0.01f) c.shadow(dotX - 2.5f, y + h / 2f - 2.5f, 5, 5, 2.5f, 5, Anim.white(0.55f * ea));

		c.circle(dotX, y + h / 2f, 2.5f, Anim.lerpColor(Anim.white(0.22f), Anim.white(1f), ea));



		float nameX = x + 25;

		String name = m.name();

		float nameW = c.textWidth(MED, name, 11.5f);

		c.textC(MED, name, nameX, y + h / 2f, 11.5f, Anim.lerpColor(0xD0FFFFFF, Theme.TEXT, Math.max(ea, ha)));



		float moreCx = x + w - 20;

		iconButton("more." + m.id, Icon.MORE, moreCx, y + h / 2f, 13, Theme.TEXT_3, () -> openSettings(m));

		float tgX = moreCx - 18 - 28;

		if(m instanceof Personal) {

			c.lens(tgX-4,y+h/2-10,36,20,10,Theme.lift(.10f+.06f*ha),Theme.lensGlass(c.scale));

			c.textMid(MED,Lang.t("ui.personal.openShort"),tgX+14,y+h/2,9,Theme.TEXT_2);

		}else toggle("tg." + m.id, tgX, y + h / 2f - 7.5f, on, 1f);

		hit(tgX - 5, y + 6, 38, h - 12).left(() -> toggleModule(m));



		float controlsLeft = tgX - 12;

		if (binding == m || m.getKey() >= 0) {

			String key = binding == m ? "..." : KeyNames.shortName(m.getKey());

			float bw = Math.max(18, c.textWidth(MED, key, 8.5f) + 12);

			float bx = controlsLeft - bw;

			badge(key, bx, y + h / 2f, 8.5f, Anim.white(binding == m ? 0.24f : 0.08f), Anim.white(0.20f),

					binding == m ? Theme.TEXT : Theme.TEXT_2);

			hit(bx, y + h / 2f - 9, bw, 18).left(() -> startBinding(m));

			controlsLeft = bx - 10;

		}



		float summaryX = Math.max(nameX + nameW + 16, x + Math.min(160, w * 0.34f));

		String summary = m.summary();

		if (!summary.isEmpty() && controlsLeft - summaryX > 28) {

			c.textC(REG, c.ellipsize(REG, summary, 9.5f, controlsLeft - summaryX), summaryX, y + h / 2f, 9.5f,

					Anim.lerpColor(Theme.TEXT_4, Theme.TEXT_3, Math.max(ea, ha)));

		}

		if (hovered) requestTooltip(m, m.description());

	}



	private void toggleModule(Module m) {

		m.toggle();

		if (!m.isOneShot()) Notifications.moduleToggled(m);

	}



	private void openSettings(Module m) {

		if (selected == m) {

			closeSettings();

			return;

		}

		selected = m;

		lastSelected = m;

		panelScroll.target = 0;

		panelScroll.value.snap(0);

		dropdown = null;

	}



	private void closeSettings() {

		selected = null;

		dropdown = null;

		if (binding instanceof Module) binding = null;

	}



	private void startBinding(Module m) {

		binding = binding == m ? null : m;

	}



	// ---------------------------------------------------------------- settings panel



	private void settingsPanel(GuiGraphicsExtractor graphics,float t) {

		Module m = selected != null ? selected : lastSelected;

		if (m == null || t < 0.01f) return;

		float e = Anim.easeOutCubic(t);

		float px = W - PANEL_W + (1f - e) * 46f;

		R2D.pushAlpha(t);

		c.lens(px + 4, HEADER + 6, W - px - 12, H - HEADER - 14, 18, Theme.lift(0.05f), Theme.lensGlass(c.scale));

		hit(px, HEADER, W - px, H - HEADER).click(b -> {

		});



		float x0 = px + 18;

		float x1 = W - 18;

		float w = x1 - x0;

		float hy = HEADER + 24;

		iconButton("panel.back", Icon.BACK, x0 + 8, hy, 15, Theme.TEXT_2, this::closeSettings);



		boolean on = m.isEnabled();

		float pillW = 38, pillH = 19;

		float pillX = x1 - pillW;

		float ea = anim("panel.on." + m.id, on ? 1f : 0f, 14f);

		boolean pillHover = over(pillX, hy - pillH / 2f, pillW, pillH);

		float pha = hoverAnim("panel.pill", pillHover);

		c.lens(pillX, hy - pillH / 2f, pillW, pillH, pillH / 2f, Theme.lift(0.08f + 0.05f * pha + 0.18f * ea), Theme.lensGlass(c.scale));

		c.textMid(BOLD, Lang.t(m instanceof Personal?"ui.personal.openShort":on ? "ui.on" : "ui.off"), pillX + pillW / 2f, hy, 8.5f, Anim.lerpColor(Theme.TEXT_3, Theme.TEXT, ea));

		hit(pillX, hy - pillH / 2f, pillW, pillH).left(() -> toggleModule(m));

		iconButton("panel.fav", m.isFavorite() ? Icon.STAR_FILLED : Icon.STAR, pillX - 17, hy, 14,

				m.isFavorite() ? Theme.TEXT : Theme.TEXT_3, () -> m.setFavorite(!m.isFavorite()));

		c.textC(BOLD, c.ellipsize(BOLD, m.name(), 12.5f, w - 100), x0 + 24, hy, 12.5f, Theme.TEXT);



		float dy = hy + 22;

		for (String line : c.wrap(REG, m.description(), 9.5f, w)) {

			c.textC(REG, line, x0, dy, 9.5f, Theme.TEXT_3);

			dy += 13;

		}

		dy += 8;



		float top = dy;

		float bottom = H - 10;

		c.clip(px, top, W - px, bottom - top);

		pushHitClip(px, top, W - px, bottom - top);

		hit(px, top, W - px, bottom - top).scroll(a -> {

			dropdown = null;

			panelScroll.scroll(a);

		});

		float y = top + 4 - panelScroll.get();

		float start = y;

		if (m instanceof com.cheatprogram.module.visual.Hud hud) {

			button("panel.hud.worldscreen",Lang.t("ui.worldpanel.place"),Icon.LAYOUT,x0,y,w,28,false,()->{

				com.cheatprogram.render.WorldControlPanel.requestPlacement(this);

			});y+=34;

			if(hud.worldScreenEnabled()){

				button("panel.hud.worldscreen.restore",Lang.t("ui.worldpanel.restore"),Icon.BACK,x0,y,w,28,false,com.cheatprogram.render.WorldControlPanel::restoreOrdinary);y+=34;

				for(String line:c.wrap(REG,Lang.t("ui.worldpanel.controls",com.cheatprogram.util.KeyNames.name(com.cheatprogram.core.ClientSettings.INSTANCE.guiKey)),8,w)){c.textC(REG,line,x0,y,8,Theme.TEXT_3);y+=11;}y+=8;

			}

			button("panel.hud.previewTitle",Lang.t("ui.hud.previewTitle"),Icon.SPARKLE,x0,y,w,28,true,()->{

				if(hud.titleIntroEnabled()){com.cheatprogram.hud.TitleIntro.preview();mc.gui.setScreen(null);}

			});y+=34;

			R2D.pushAlpha(hud.islandEventsEnabled() ? 1 : .45f);

			try {

				button("panel.hud.previewEvents", Lang.t("ui.hud.previewEvents"), Icon.SPARKLE, x0, y, w, 28, true, () -> {

					if (hud.islandEventsEnabled()) { com.cheatprogram.hud.IslandEvents.preview(); mc.gui.setScreen(null); }

				});

			} finally { R2D.popAlpha(); }

			y += 34;

			R2D.pushAlpha(hud.musicPreviewEnabled() ? 1 : .45f);

			try {

				button("panel.hud.previewMusic", Lang.t("ui.hud.previewMusic"), Icon.MUSIC, x0, y, w, 28, false, () -> {

					if (hud.musicPreviewEnabled()) { com.cheatprogram.hud.MusicCapsule.requestPreview(); mc.gui.setScreen(null); }

				});

			} finally { R2D.popAlpha(); }

			y += 40;

		}

		if(m instanceof Personal personal) {

			button("panel.personal.open",Lang.t("ui.personal.open"),Icon.USER,x0,y,w,28,true,()->mc.gui.setScreen(personal.open(this)));

			y+=38;

		}

		if(m instanceof com.cheatprogram.module.visual.AppCast cast){

			button("panel.appcast.open",Lang.t("ui.appcast.open"),Icon.LAYOUT,x0,y,w,28,true,()->mc.gui.setScreen(new AppCastScreen(this,cast)));y+=38;

		}

		if(m instanceof com.cheatprogram.module.visual.SpatialAudio audio){

			button("panel.spatial.open",Lang.t("ui.spatial.open"),Icon.MUSIC,x0,y,w,28,true,()->mc.gui.setScreen(new SpatialAudioScreen(this,audio)));y+=38;

			for(String line:c.wrap(REG,audio.processingStatus(),9,w)){c.textC(REG,line,x0,y+11,9,Theme.TEXT_2);y+=15;}

			if(audio.usesCast())for(String line:c.wrap(REG,audio.isolationStatus(),8.5f,w)){c.textC(REG,line,x0,y+11,8.5f,Theme.TEXT_3);y+=14;}

			for(String line:c.wrap(REG,audio.metrics(),8.5f,w)){c.textC(REG,line,x0,y+11,8.5f,Theme.TEXT_3);y+=14;}y+=8;

		}

		if(m instanceof com.cheatprogram.module.visual.FloatingSubtitles captions){

			button("panel.lyrics.open",Lang.t("ui.lyrics.open"),Icon.MUSIC,x0,y,w,28,true,()->mc.gui.setScreen(new MusicPlayerScreen(this,captions)));y+=38;

			for(String line:c.wrap(REG,captions.primaryStatus(),8.5f,w)){c.textC(REG,line,x0,y+11,8.5f,Theme.TEXT_2);y+=14;}y+=8;

			for(String line:c.wrap(REG,Lang.t("ui.music.captionHint"),8.5f,w)){c.textC(REG,line,x0,y+11,8.5f,Theme.TEXT_3);y+=14;}y+=8;

		}

		if(m instanceof com.cheatprogram.module.visual.ParticleEffects particles){

			button("panel.particles.open",Lang.t("ui.particles.open"),Icon.SPARKLE,x0,y,w,28,true,()->mc.gui.setScreen(new ParticleEffectsScreen(this,particles)));y+=38;

		}

		if(m instanceof com.cheatprogram.module.visual.ModelWear model){

			button("panel.modelwear.open",Lang.t("ui.modelWear.open"),Icon.USER,x0,y,w,28,true,()->mc.gui.setScreen(new ModelWearScreen(this,model)));y+=38;

		}

		if(m instanceof com.cheatprogram.module.visual.WeaponAnimations animations){

			button("panel.weaponactions.open",Lang.t("ui.weaponActions.open"),Icon.LAYOUT,x0,y,w,28,true,()->mc.gui.setScreen(new WeaponAnimationsScreen(this,animations)));y+=38;

		}

		if(m instanceof BlueArchiveHalo halo) {

			if(halo.isPreviewVisible())y=haloPreview(graphics,halo,x0,y,w,top,bottom);

			else haloPreview.close();

		}

		if(m instanceof com.cheatprogram.module.visual.WearableWeapons wear){

			button("panel.wearable.editor",Lang.t("ui.wearable.edit"),Icon.USER,x0,y,w,28,true,()->mc.gui.setScreen(new WearableWeaponsScreen(this,wear)));

			y+=36;

			if(wear.showPreview())y=wearablePreview(graphics,wear,x0,y,w,top,bottom);else wearablePreview.close();

		}

		if(m instanceof com.cheatprogram.module.utility.DeathRecovery recovery){

			button("panel.recovery.open",Lang.t("ui.recovery.open"),Icon.NAVIGATION,x0,y,w,28,true,()->recovery.open(this));y+=38;

		}

		if(m instanceof com.cheatprogram.module.utility.TaskQueue queue){

			button("panel.queue.open",Lang.t("ui.queue.open"),Icon.LAYOUT,x0,y,w,28,true,()->queue.open(this));y+=38;

		}

		if (m.id.equals("customcape")) {

			button("panel.cape.editor", Lang.t("ui.cape.open"), Icon.FOLDER, x0, y, w, 28, true,

					() -> mc.gui.setScreen(new CapeEditorScreen(this)));

			y += 38;

		}

		if (m instanceof ControlPanel panel) {

			button("panel.background.choose",Lang.t("ui.panel.editor.open"),Icon.FOLDER,x0,y,w,28,true,

					() -> mc.gui.setScreen(new PanelBackgroundEditorScreen(this,panel)));

			y+=34;

			button("panel.background.clear",Lang.t("ui.panel.clear"),Icon.CLOSE,x0,y,w,25,false,() -> panel.imagePath(""));

			y+=35;

			c.textC(REG,c.ellipsize(REG,Lang.t(ControlPanelTextures.statusKey(panel.imagePath())),8.5f,w),x0,y,8.5f,Theme.TEXT_3);

			y+=20;

		}

		if (m.id.equals("buildingblueprints")) {

			button("panel.building.editor", Lang.t("ui.building.open"), Icon.FOLDER, x0, y, w, 28, true,

					() -> mc.gui.setScreen(new BuildingBlueprintScreen(this, (com.cheatprogram.module.world.BuildingBlueprints) m)));

			y += 38;

		}

		if (m instanceof com.cheatprogram.module.world.RobotPlayers robots) {

			button("panel.robots.open",Lang.t("ui.robots.open"),Icon.USER,x0,y,w,28,true,

					() -> mc.gui.setScreen(new RobotPlayersScreen(this,robots)));

			y+=38;

		}

		if (m instanceof com.cheatprogram.module.world.TrainingRobots training) {

			button("panel.training.open",Lang.t("ui.robottraining.open"),Icon.USER,x0,y,w,28,true,

					() -> mc.gui.setScreen(new BotTrainingScreen(this,training)));

			y+=38;

		}

		if (m instanceof com.cheatprogram.module.world.ChatTracker tracker) {

			button("panel.chat.history", Lang.t("ui.chattracker.open"), Icon.TERMINAL, x0, y, w, 28, true, () -> tracker.open(this));

			y += 38;

		}

		if (m instanceof com.cheatprogram.module.world.PrivateChat privateChat) {

			button("panel.chat.private", Lang.t("ui.privatechat.open"), Icon.TERMINAL, x0, y, w, 28, true, () -> privateChat.open(this));

			y += 38;

		}

		if (m.id.equals("rearviewmirror")) {

			button("panel.mirror.editor", Lang.t("ui.mirror.edit"), Icon.GEAR, x0, y, w, 28, true,

					() -> mc.gui.setScreen(new MirrorEditorScreen(this)));

			y += 38;

		}

		if (m instanceof com.cheatprogram.module.inventory.InvCleaner cleaner) {

			button("panel.inventory.preview",Lang.t("ui.invpreview.open"),Icon.FOLDER,x0,y,w,28,true,() -> cleaner.preview(this));

			y+=38;

		}

		if (m instanceof com.cheatprogram.module.world.ContainerSearch search) {

			button("panel.containers.search",Lang.t("ui.containersearch.open"),Icon.FOLDER,x0,y,w,28,true,() -> search.open(this));

			y+=38;

		}

		if (m instanceof com.cheatprogram.module.visual.Minimap) {

			button("panel.minimap.editor",Lang.t("ui.minimap.edit"),Icon.GEAR,x0,y,w,28,true,() -> mc.gui.setScreen(new MinimapEditorScreen(this)));

			y+=38;

		}

		if (m instanceof com.cheatprogram.module.visual.OreXray ores) {

			for (String key : ores.helpKeys()) {

				for (String line : c.wrap(REG, Lang.t(key), 8f, w)) {

					c.textC(REG, line, x0, y + 8, 8f, Theme.TEXT_3);

					y += 11;

				}

				y += 4;

			}

			y += 6;

		}

		if(m instanceof com.cheatprogram.module.movement.TeleportAura teleport){

			for(String key:teleport.helpKeys()){

				for(String line:c.wrap(REG,Lang.t(key),8,w)){c.textC(REG,line,x0,y+8,8,Theme.TEXT_3);y+=11;}y+=4;

			}y+=6;

		}

		for (Setting<?> s : m.settings()) {

			if (s.isVisible()) y = settingRow(s, x0, y, w);

		}

		y = bindRows(m, x0, y, w);

		String conflict=com.cheatprogram.core.KeyConflicts.description(m);

		if(!conflict.isEmpty()) for(String line:c.wrap(REG,conflict,8.5f,w)) {

			c.textC(REG,line,x0,y+8,8.5f,0xFFFFD5A0);y+=12;

		}

		y += 6;

		button("panel.reset." + m.id, Lang.t("ui.reset"), Icon.REFRESH, x0, y, w, 26, false, () -> resetModule(m));

		y += 36;

		popHitClip();

		c.unclip();

		panelScroll.max = Math.max(0, (y - start) - (bottom - top));

		panelScroll.update(dt);

		R2D.popAlpha();

	}



	private float wearablePreview(GuiGraphicsExtractor graphics,com.cheatprogram.module.visual.WearableWeapons wear,float x,float y,float w,float top,float bottom) {

		c.textC(MED,Lang.t("ui.wearable.preview"),x,y+10,11,Theme.TEXT);

		iconButton("wearable.preview.reset",Icon.REFRESH,x+w-9,y+10,12,Theme.TEXT_3,wearablePreview::reset);

		float py=y+23,ph=150;

		c.lens(x,py,w,ph,12,Theme.lift(.035f),Theme.lensGlass(c.scale));

		if(py+ph>top&&py<bottom){

			wearablePreview.draw(graphics,c.px(x),c.py(py),w*c.scale,ph*c.scale,wear);R2D.applyState();

			if(!wearablePreview.ready())c.textMid(REG,c.ellipsize(REG,Lang.t(wearablePreview.statusKey()),9,w-16),x+w/2,py+ph/2,9,Theme.TEXT_3);

		}

		hit(x,py,w,ph).scroll(amount->wearablePreview.zoom=Math.max(.3f,Math.min(3,wearablePreview.zoom*(float)Math.pow(1.12,amount)))).click(button->{

			if(button!=0&&button!=1)return;

			float sx=mx,sy=my,yaw=wearablePreview.yaw,pitch=wearablePreview.pitch,panX=wearablePreview.panX,panY=wearablePreview.panY;

			drag=(u,v)->{if(button==0){wearablePreview.yaw=(yaw+(u-sx)*1.1f)%360;wearablePreview.pitch=Math.max(-80,Math.min(80,pitch+(v-sy)*1.1f));}

				else{wearablePreview.panX=Math.max(-2,Math.min(2,panX+(u-sx)*2/ph));wearablePreview.panY=Math.max(-2,Math.min(2,panY+(v-sy)*2/ph));}};

		});

		c.textC(REG,Lang.t("ui.wearable.previewHint"),x,py+ph+12,8,Theme.TEXT_3);return py+ph+25;

	}

	private float haloPreview(GuiGraphicsExtractor graphics,BlueArchiveHalo halo,float x,float y,float w,float top,float bottom) {

		c.textC(MED,Lang.t("ui.halo.preview"),x,y+10,11,Theme.TEXT);

		iconButton("halo.preview.reset",Icon.REFRESH,x+w-9,y+10,12,Theme.TEXT_3,haloPreview::reset);

		float py=y+23,ph=112;

		c.lens(x,py,w,ph,12,Theme.lift(.035f),Theme.lensGlass(c.scale));

		if(py+ph>top&&py<bottom) {

			haloPreview.draw(graphics,c.px(x),c.py(py),w*c.scale,ph*c.scale,halo);

			R2D.applyState();

			if(!haloPreview.ready())c.textMid(REG,c.ellipsize(REG,Lang.t(haloPreview.statusKey()),9,w-16),x+w/2,py+ph/2,9,Theme.TEXT_3);

		}

		hit(x,py,w,ph).scroll(amount->haloPreview.zoom=Math.max(.1f,Math.min(8,haloPreview.zoom*(float)Math.pow(1.12,amount)))).click(button->{

			if(button!=0&&button!=1)return;

			long now=System.currentTimeMillis();

			if(button==0&&now-haloClickAt<260){haloPreview.reset();haloClickAt=0;return;}

			if(button==0)haloClickAt=now;

			float sx=mx,sy=my,yaw=haloPreview.yaw,pitch=haloPreview.pitch,panX=haloPreview.panX,panY=haloPreview.panY;

			drag=(u,v)->{

				if(button==0){haloPreview.yaw=(yaw+(u-sx)*1.1f)%360;haloPreview.pitch=Math.max(-89,Math.min(89,pitch+(v-sy)*1.1f));}

				else{haloPreview.panX=Math.max(-2,Math.min(2,panX+(u-sx)*2/ph));haloPreview.panY=Math.max(-2,Math.min(2,panY+(v-sy)*2/ph));}

			};

		});

		c.textC(REG,Lang.t("ui.halo.previewHint"),x,py+ph+12,8,Theme.TEXT_3);

		return py+ph+25;

	}



	private boolean transitionBusy(){return customTransition&&transitions.busy();}

	private void beginPageTransition(){

		if(!customTransition)return;

		transitions.pageChanged();drag=null;focused=null;dropdown=null;binding=null;

	}



	private void resetModule(Module m) {

		for (Setting<?> s : m.settings()) s.reset();

		settingFields.clear();

	}



	/** Draws one setting row and returns the y below it. */

	private float settingRow(Setting<?> s, float x, float y, float w) {

		if (s instanceof BoolSetting b) {

			return toggleRow(b, b.name(), b.on(), b::toggle, x, y, w);

		}

		if (s instanceof NumberSetting n) {

			boolean hovered = over(x - 6, y, w + 12, 40);

			float ha = hoverAnim(n.id + "#row" + System.identityHashCode(n), hovered);

			c.textC(REG, n.name(), x, y + 12, 10.5f, Anim.lerpColor(Theme.TEXT_2, Theme.TEXT, ha));

			c.textRight(MED, n.format(), x + w, y + 12, 10.5f, Theme.TEXT);

			slider(n, x + 5, y + 29, w - 10);

			return y + 42;

		}

		if (s instanceof RangeSetting r) {

			boolean hovered = over(x - 6, y, w + 12, 40);

			float ha = hoverAnim(r.id + "#row" + System.identityHashCode(r), hovered);

			c.textC(REG, r.name(), x, y + 12, 10.5f, Anim.lerpColor(Theme.TEXT_2, Theme.TEXT, ha));

			String value = NumberSetting.formatNumber(r.low(), r.step) + "  ↔  " + NumberSetting.formatNumber(r.high(), r.step) + Lang.unit(r.unit);

			c.textRight(MED, value, x + w, y + 12, 10.5f, Theme.TEXT);

			rangeSlider(r, x + 6, y + 29, w - 12);

			return y + 42;

		}

		if (s instanceof ModeSetting md) {

			boolean isOpen = dropdown == md;

			final float by = y + 4;

			selectBox(md, md.name(), md.display(), x, by, w, 26, isOpen, () -> openDropdown(md, x, by + 30, w));

			return y + 36;

		}

		if (s instanceof TextSetting ts) {

			c.textC(REG, ts.name(), x, y + 11, 10.5f, Theme.TEXT_2);

			TextField field = settingFields.computeIfAbsent(ts, k -> {

				TextField f = new TextField(ts.maxLength);

				f.setText(ts.get());

				f.onChange = ts::set;

				return f;

			});

			if (focused != field && !field.text.equals(ts.get())) field.setText(ts.get());

			String hintKey = ts.owner == null ? "ui." + ts.id + ".hint" : "setting." + ts.owner.id + "." + ts.id + ".hint";

			textField(field, Lang.has(hintKey) ? Lang.t(hintKey) : "", null, x, y + 22, w, 26);

			return y + 54;

		}

		return y;

	}



	private float toggleRow(Object key, String label, boolean value, Runnable action, float x, float y, float w) {

		float h = 28;

		boolean hovered = over(x - 6, y, w + 12, h);

		float ha = hoverAnim(key + "#row", hovered);

		if (ha > 0.01f) c.rrect(x - 6, y + 1, w + 12, h - 2, 8, Anim.white(0.045f * ha));

		c.textC(REG, c.ellipsize(REG, label, 10.5f, w - 40), x, y + h / 2f, 10.5f, Anim.lerpColor(Theme.TEXT_2, Theme.TEXT, ha));

		toggle(key, x + w - 28, y + h / 2f - 7.5f, value, 1f);

		hit(x - 6, y, w + 12, h).left(action);

		return y + h;

	}



	private float bindRows(Module m, float x, float y, float w) {

		y += 8;

		float h = 30;

		c.icon(Icon.KEYBOARD, x + 7, y + h / 2f, 13, Theme.TEXT_3);

		c.textC(REG, Lang.t("ui.keybind"), x + 20, y + h / 2f, 10.5f, Theme.TEXT_2);

		boolean waiting = binding == m;

		String key = waiting ? Lang.t("ui.keybind.waiting") : KeyNames.name(m.getKey());

		float bw = Math.max(46, c.textWidth(MED, key, 9.5f) + 18);

		float bx = x + w - bw;

		boolean hovered = over(bx, y + 5, bw, 20);

		float ha = hoverAnim("bind." + m.id, hovered || waiting);

		float pulse = waiting ? 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 160.0) : 0f;

		c.lens(bx, y + 5, bw, 20, 10, Theme.lift(0.08f + 0.07f * ha + 0.06f * pulse), Theme.lensGlass(c.scale));

		c.textMid(MED, key, bx + bw / 2f, y + h / 2f, 9.5f, waiting ? Theme.TEXT : Theme.TEXT_2);

		hit(bx, y + 5, bw, 20).left(() -> startBinding(m));

		y += h;

		if (waiting) {

			c.textC(REG, Lang.t("ui.keybind.hint"), x, y + 6, 8.5f, Theme.TEXT_3);

			y += 16;

		}

		y = toggleRow("hold." + m.id, Lang.t("ui.holdmode"), m.isHoldBind(), () -> m.setHoldBind(!m.isHoldBind()), x, y, w);

		y = toggleRow("drawn." + m.id, Lang.t("ui.drawn"), m.isDrawn(), () -> m.setDrawn(!m.isDrawn()), x, y, w);

		return y;

	}



	private void openDropdown(ModeSetting setting, float x, float y, float w) {

		if (dropdown == setting) {

			dropdown = null;

			return;

		}

		dropdown = setting;

		lastDropdown = setting;

		dropdownScroll.target = 0;

		dropdownScroll.value.snap(0);

		ddX = x;

		ddY = y;

		ddW = w;

	}



	// ================================================================ drawer



	private void drawer(float t) {

		if (t < 0.01f) return;

		float e = Anim.easeOutCubic(t);

		c.rrect(0, 0, W, H, R, Anim.black(0.18f * t));

		if (drawer) hit(0, 0, W, H).left(() -> drawer = false);



		float dw = DRAWER_W;

		float dx = W - dw - 10 + (1f - e) * (dw * 0.55f);

		float dy = 10;

		float dh = H - 20;

		R2D.pushAlpha(t);

		Backdrop.capture(Theme.frost());

		R2D.applyState();

		c.shadow(dx, dy + 8, dw, dh, 22, 30, Anim.black(0.22f));

		c.lens(dx, dy, dw, dh, 22, Theme.lift(0.06f), Theme.sheetGlass(c.scale));

		hit(dx, dy, dw, dh).click(b -> {

		});



		float x0 = dx + 18;

		float x1 = dx + dw - 18;

		float w = x1 - x0;

		float hy = dy + 25;

		boolean root = drawerPage.equals("root");

		if (root) {

			c.icon(Icon.GEAR, x0 + 7, hy, 14, Theme.TEXT_2);

			c.textC(BOLD, Lang.t("ui.settings"), x0 + 21, hy, 12.5f, Theme.TEXT);

		} else {

			iconButton("drawer.back", Icon.BACK, x0 + 8, hy, 15, Theme.TEXT_2, () -> {

				drawerPage = "root";

				drawerScroll.target = 0;

			});

			c.textC(BOLD, Lang.t("ui." + drawerPage), x0 + 24, hy, 12.5f, Theme.TEXT);

		}

		iconButton("drawer.close", Icon.CLOSE, x1 - 6, hy, 12, Theme.TEXT_3, () -> drawer = false);



		float top = hy + 26;

		float bottom = dy + dh - 8;

		c.clip(dx, top, dw, bottom - top);

		pushHitClip(dx, top, dw, bottom - top);

		hit(dx, top, dw, bottom - top).scroll(a -> {

			dropdown = null;

			drawerScroll.scroll(a);

		});

		float pageT = anim("drawer.page." + drawerPage, 1f, 12f);

		R2D.pushAlpha(Anim.easeOutCubic(pageT));

		c.push();

		c.translate((1f - Anim.easeOutCubic(pageT)) * 16f, 0);

		float y = top + 4 - drawerScroll.get();

		float start = y;

		ClientSettings s = ClientSettings.INSTANCE;

		switch (drawerPage) {

			case "general" -> {

				y = settingRow(s.keybinds, x0, y, w);

				y = settingRow(s.autoSave, x0, y, w);

				y = settingRow(s.notifications, x0, y, w);

				y = settingRow(s.tooltips, x0, y, w);

				y += 4;

				y = settingRow(s.language, x0, y, w);

				y = settingRow(s.fontMode, x0, y, w);

				button("drawer.font",Lang.t("ui.font.title"),Icon.LANGUAGE,x0,y,w,28,false,()->mc.gui.setScreen(new FontSettingsScreen(this)));y+=36;

			}

			case "targets" -> {

				y = settingRow(s.targetPlayers, x0, y, w);

				y = settingRow(s.targetHostiles, x0, y, w);

				y = settingRow(s.targetPassives, x0, y, w);

				y = settingRow(s.targetInvisible, x0, y, w);

				y = settingRow(s.ignoreFriends, x0, y, w);

			}

			case "interface" -> {

				y = settingRow(s.uiScale, x0, y, w);

				y = settingRow(s.animSpeed, x0, y, w);

				y += 6;

				button("drawer.resetLook", Lang.t("ui.resetInterface"), Icon.REFRESH, x0, y, w, 26, false, s::resetAppearance);

				y += 34;

			}

			default -> {

				y = navRow(Icon.INFO, Lang.t("ui.general"), "general", x0, y, w);

				y = navRow(Icon.TARGET, Lang.t("ui.targets"), "targets", x0, y, w);

				y = navRow(Icon.LAYOUT, Lang.t("ui.interface"), "interface", x0, y, w);

				y += 8;

				c.icon(Icon.SPARKLE, x0 + 6, y + 8, 12, Theme.TEXT_2);

				c.textC(MED, Lang.t("ui.glass"), x0 + 18, y + 8, 10.5f, Theme.TEXT);

				y += 20;

				y = settingRow(s.transparency, x0, y, w);

				y = settingRow(s.blur, x0, y, w);

				y = settingRow(s.frost, x0, y, w);

				y = settingRow(s.refraction, x0, y, w);

				y = settingRow(s.highlight, x0, y, w);

				y = settingRow(s.desaturate, x0, y, w);

				y += 8;

				y = guiBindRow(x0, y, w);

			}

		}

		y += 6;

		c.pop();

		R2D.popAlpha();

		popHitClip();

		c.unclip();

		drawerScroll.max = Math.max(0, (y - start) - (bottom - top));

		drawerScroll.update(dt);

		R2D.popAlpha();

	}



	private float navRow(Icon icon, String label, String page, float x, float y, float w) {

		float h = 32;

		boolean hovered = over(x - 6, y, w + 12, h);

		float ha = hoverAnim("nav." + page, hovered);

		c.lens(x - 6, y + 1, w + 12, h - 2, (h - 2) / 2f, Theme.lift(0.06f + 0.05f * ha), Theme.lensGlass(c.scale));

		c.icon(icon, x + 7, y + h / 2f, 13, Anim.lerpColor(Theme.TEXT_2, Theme.TEXT, ha));

		c.textC(REG, label, x + 21, y + h / 2f, 11, Theme.TEXT);

		c.icon(Icon.CHEVRON_RIGHT, x + w - 4, y + h / 2f, 10, Theme.TEXT_3);

		hit(x - 6, y, w + 12, h).left(() -> {

			drawerPage = page;

			drawerScroll.target = 0;

			drawerScroll.value.snap(0);

		});

		return y + h + 4;

	}



	private float guiBindRow(float x, float y, float w) {

		float h = 30;

		c.textC(REG, Lang.t("ui.rebind"), x, y + h / 2f, 10.5f, Theme.TEXT_2);

		boolean waiting = binding == BIND_GUI;

		String key = waiting ? Lang.t("ui.keybind.waiting") : KeyNames.name(ClientSettings.INSTANCE.guiKey);

		float bw = Math.max(60, c.textWidth(MED, key, 9.5f) + 18);

		float bx = x + w - bw;

		boolean hovered = over(bx, y + 5, bw, 20);

		float ha = hoverAnim("bind.gui", hovered || waiting);

		c.lens(bx, y + 5, bw, 20, 10, Theme.lift(0.08f + 0.07f * ha), Theme.lensGlass(c.scale));

		c.textMid(MED, key, bx + bw / 2f, y + h / 2f, 9.5f, waiting ? Theme.TEXT : Theme.TEXT_2);

		hit(bx, y + 5, bw, 20).left(() -> binding = binding == BIND_GUI ? null : BIND_GUI);

		float bottom=y+h;

		String conflict=com.cheatprogram.core.KeyConflicts.guiDescription();

		if(!conflict.isEmpty())for(String line:c.wrap(REG,conflict,8.5f,w)){

			c.textC(REG,line,x,bottom+8,8.5f,0xFFFFD5A0);bottom+=12;

		}

		return bottom;

	}



	// ================================================================ dropdown & tooltip overlays



	private void dropdownOverlay() {

		float t = anim("dropdown", dropdown != null ? 1f : 0f, dropdown != null ? 20f : 24f);

		ModeSetting md = dropdown != null ? dropdown : lastDropdown;

		if (md == null || t < 0.01f) return;

		float rowH = 24;

		float fullHeight = md.modes.size() * rowH + 8;

		float h = Math.min(fullHeight, Math.min(248, H - 24));

		dropdownScroll.max = Math.max(0, fullHeight - h);

		dropdownScroll.update(dt);

		float x = ddX;

		float y = ddY;

		if (y + h > H - 6) y = ddY - 30 - 4 - h;

		y = Math.max(8, Math.min(H - h - 8, y));

		if (dropdown != null) hit(0, 0, W, H).left(() -> dropdown = null);

		R2D.pushAlpha(t);

		Backdrop.capture(Theme.overlayFrost());

		R2D.applyState();

		float shown = h * (0.82f + 0.18f * Anim.easeOutCubic(t));

		c.shadow(x, y + 5, ddW, shown, 13, 18, Anim.black(0.22f));

		c.glass(x, y, ddW, shown, 13, Anim.black(0.28f), Anim.black(0.32f), Theme.chipGlass(c.scale));

		hit(x, y, ddW, shown).scroll(dropdownScroll::scroll).click(b -> {

		});

		c.clip(x, y, ddW, shown);

		pushHitClip(x, y, ddW, shown);

		for (int i = 0; i < md.modes.size(); i++) {

			String mode = md.modes.get(i);

			float ry = y + 4 + i * rowH - dropdownScroll.get();

			if (ry + rowH < y || ry > y + shown) continue;

			boolean hovered = dropdown != null && inside(x + 4, ry, ddW - 8, rowH);

			float ha = hoverAnim("dd." + mode + System.identityHashCode(md), hovered);

			if (ha > 0.01f) c.rrect(x + 4, ry, ddW - 8, rowH, rowH / 2f, Anim.white(0.10f * ha));

			boolean sel = md.is(mode);

			c.textC(sel ? MED : REG, c.ellipsize(sel ? MED : REG, Lang.mode(mode), 10.5f, ddW - 42), x + 14, ry + rowH / 2f, 10.5f, sel ? Theme.TEXT : Theme.TEXT_2);

			if (sel) c.icon(Icon.CHECK, x + ddW - 18, ry + rowH / 2f, 12, Theme.TEXT);

			if (dropdown != null) hit(x + 4, ry, ddW - 8, rowH).left(() -> {

				md.set(mode);

				dropdown = null;

			});

		}

		popHitClip();

		c.unclip();

		if (dropdownScroll.max > 0) {

			float thumb = Math.max(22, (h - 12) * h / fullHeight);

			float thumbY = y + 6 + (h - 12 - thumb) * Math.max(0, Math.min(1, dropdownScroll.get() / dropdownScroll.max));

			c.rrect(x + ddW - 4, thumbY, 2, thumb, 1, Anim.white(.35f));

		}

		R2D.popAlpha();

	}



	private void requestTooltip(Object key, String text) {

		if (!ClientSettings.INSTANCE.tooltips.on() || text == null || text.isEmpty() || drag != null) return;

		tooltipSeen = true;

		if (tooltipKey != key) {

			tooltipKey = key;

			tooltipSince = System.currentTimeMillis();

		}

		tooltipText = text;

	}



	private void tooltip() {

		if (!tooltipSeen) tooltipKey = null;

		boolean show = tooltipKey != null && System.currentTimeMillis() - tooltipSince > 520 && !drawer && dropdown == null;

		float t = anim("tooltip", show ? 1f : 0f, show ? 16f : 26f);

		if (t < 0.01f || tooltipText == null) return;

		List<String> lines = c.wrap(REG, tooltipText, 9.5f, 210);

		float w = 0;

		for (String l : lines) w = Math.max(w, c.textWidth(REG, l, 9.5f));

		w += 18;

		float h = lines.size() * 13 + 11;

		float x = Math.min(mx + 12, W - w - 6);

		float y = my + 16;

		if (y + h > H - 6) y = my - h - 8;

		R2D.pushAlpha(t);

		Backdrop.capture(Theme.overlayFrost());

		R2D.applyState();

		c.shadow(x, y + 4, w, h, 11, 14, Anim.black(0.22f));

		c.glass(x, y + (1f - t) * 4f, w, h, 11, Anim.black(0.30f), Anim.black(0.34f), Theme.chipGlass(c.scale));

		float ty = y + (1f - t) * 4f + 12;

		for (String l : lines) {

			c.textC(REG, l, x + 9, ty, 9.5f, Theme.TEXT);

			ty += 13;

		}

		R2D.popAlpha();

	}



	// ================================================================ friends tab



	private void friendsTab() {

		float x0 = 26;

		float x1 = W - 26;

		float appear = Anim.easeOutCubic((System.currentTimeMillis() - listSwitch) / 260f);

		R2D.pushAlpha(appear);

		c.text(BOLD, Lang.t("ui.friends.title"), x0, 116, 16, Theme.TEXT);

		c.text(REG, Lang.t("ui.friends.desc"), x0, 134, 9.5f, Theme.TEXT_3);

		String count = Lang.t("ui.friends.count", Friends.all().size());

		c.textRight(MED, count, x1, 110, 10, Theme.TEXT_3);



		float fy = 148;

		float addW = 74;

		float lookW = 178;

		float fieldW = x1 - x0 - addW - lookW - 16;

		textField(friendField, Lang.t("ui.friends.input"), Icon.USER, x0, fy, fieldW, 30);

		button("friend.add", Lang.t("ui.friends.add"), Icon.PLUS, x0 + fieldW + 8, fy, addW, 30, true, this::addFriendFromField);

		button("friend.look", Lang.t("ui.friends.addLooking"), Icon.TARGET, x1 - lookW, fy, lookW, 30, false, this::addLookedAtFriend);



		float top = fy + 42;

		float bottom = H - 14;

		List<String> names = new ArrayList<>(Friends.all());

		float rowH = 40;

		float contentH = names.size() * (rowH + 6);

		friendScroll.max = Math.max(0, contentH - (bottom - top));

		friendScroll.update(dt);

		hit(x0, top, x1 - x0, bottom - top).scroll(friendScroll::scroll);

		c.clip(x0 - 3, top - 3, x1 - x0 + 6, bottom - top + 6);

		pushHitClip(x0 - 3, top - 3, x1 - x0 + 6, bottom - top + 6);

		float y = top - friendScroll.get();

		for (String name : names) {

			boolean hovered = over(x0, y, x1 - x0, rowH);

			float ha = hoverAnim("friend." + name, hovered);

			c.lens(x0, y, x1 - x0, rowH, rowH / 2f, Theme.lift(0.06f + 0.035f * ha), Theme.lensGlass(c.scale));

			c.circle(x0 + 22, y + rowH / 2f, 11, Anim.white(0.14f));

			c.textMid(BOLD, name.substring(0, 1).toUpperCase(Locale.ROOT), x0 + 22, y + rowH / 2f, 10, Theme.TEXT);

			c.textC(MED, name, x0 + 42, y + rowH / 2f, 11.5f, Theme.TEXT);

			final String n = name;

			iconButton("friend.rm." + name, Icon.TRASH, x1 - 20, y + rowH / 2f, 13, Theme.TEXT_3, () -> Friends.remove(n));

			y += rowH + 6;

		}

		popHitClip();

		c.unclip();

		if (names.isEmpty()) {

			float cx = (x0 + x1) / 2f;

			float cy = (top + bottom) / 2f - 8;

			c.icon(Icon.USER, cx, cy - 14, 26, Theme.TEXT_4);

			c.textMid(MED, Lang.t("ui.friends.empty"), cx, cy + 14, 12, Theme.TEXT_2);

		}

		R2D.popAlpha();

	}



	private void addFriendFromField() {

		String name = friendField.text.trim();

		if (!name.isEmpty() && Friends.add(name)) {

			Notifications.push(Lang.t("ui.notify.friendAdded"), name, true);

			friendField.setText("");

		}

	}



	private void addLookedAtFriend() {

		if (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() instanceof Player p && p != mc.player) {

			String name = p.getGameProfile().name();

			if (Friends.add(name)) Notifications.push(Lang.t("ui.notify.friendAdded"), name, true);

		}

	}



	// ================================================================ profiles tab



	private void profilesTab() {

		if (profiles == null || System.currentTimeMillis() - profilesLoaded > 3000) {

			profiles = Config.listProfiles();

			profilesLoaded = System.currentTimeMillis();

		}

		float x0 = 26;

		float x1 = W - 26;

		float appear = Anim.easeOutCubic((System.currentTimeMillis() - listSwitch) / 260f);

		R2D.pushAlpha(appear);

		c.text(BOLD, Lang.t("ui.profiles.title"), x0, 116, 16, Theme.TEXT);

		c.text(REG, Lang.t("ui.profiles.desc"), x0, 134, 9.5f, Theme.TEXT_3);



		float fy = 148;

		float createW = 78;

		float folderW = 120;

		float fieldW = x1 - x0 - createW - folderW - 16;

		textField(profileField, Lang.t("ui.profiles.input"), Icon.FOLDER, x0, fy, fieldW, 30);

		button("profile.create", Lang.t("ui.profiles.create"), Icon.PLUS, x0 + fieldW + 8, fy, createW, 30, true, this::createProfileFromField);

		button("profile.folder", Lang.t("ui.profiles.folder"), Icon.FOLDER, x1 - folderW, fy, folderW, 30, false,

				() -> Util.getPlatform().openPath(Config.profileDirectory()));

		button("profile.importCode", Lang.t("ui.profileCode.import"), Icon.DOWNLOAD, x0, fy + 41, 154, 29, true, () -> {

			profiles = null; mc.gui.setScreen(new ProfileShareScreen(this, null));

		});

		c.textC(REG, c.ellipsize(REG, Lang.t("ui.profileCode.offlineHint"), 9, x1 - x0 - 292), x0 + 168, fy + 56, 9, Theme.TEXT_3);

		button("profile.initialize",Lang.t("ui.reset.open"),Icon.REFRESH,x1-112,fy+41,112,29,false,()->mc.gui.setScreen(new ConfigResetScreen(this)));



		float top = fy + 83;

		float bottom = H - 14;

		float rowH = 52;

		float contentH = profiles.size() * (rowH + 6);

		profileScroll.max = Math.max(0, contentH - (bottom - top));

		profileScroll.update(dt);

		hit(x0, top, x1 - x0, bottom - top).scroll(profileScroll::scroll);

		c.clip(x0 - 3, top - 3, x1 - x0 + 6, bottom - top + 6);

		pushHitClip(x0 - 3, top - 3, x1 - x0 + 6, bottom - top + 6);

		float y = top - profileScroll.get();

		SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT);

		for (Config.ProfileInfo p : profiles) {

			boolean active = p.name().equals(ClientSettings.INSTANCE.activeProfile);

			boolean hovered = over(x0, y, x1 - x0, rowH);

			float ha = hoverAnim("profile." + p.name(), hovered);

			c.lens(x0, y, x1 - x0, rowH, 18, Theme.lift(0.06f + 0.035f * ha + (active ? 0.06f : 0)), Theme.lensGlass(c.scale));

			String displayName = c.ellipsize(MED, p.name(), 12, x1 - x0 - 340);

			c.textC(MED, displayName, x0 + 20, y + 17, 12, Theme.TEXT);

			float nameW = c.textWidth(MED, displayName, 12);

			if (active) {

				badge(Lang.t("ui.profiles.active"), x0 + 28 + nameW, y + 17, 8, Anim.white(0.22f), 0, Theme.TEXT);

			}

			String sub = Lang.t("ui.profiles.modules", p.enabledCount()) + "  ·  " + fmt.format(new Date(p.modified()));

			c.textC(REG, c.ellipsize(REG, sub, 9, x1 - x0 - 286), x0 + 20, y + 35, 9, Theme.TEXT_3);

			float bx = x1 - 14;

			final String name = p.name();

			iconButton("profile.del." + name, Icon.TRASH, bx - 6, y + rowH / 2f, 13, Theme.TEXT_3, () -> {

				profiles = null; mc.gui.setScreen(new ProfileShareScreen(this, name, true));

			});

			bx -= 30;

			iconButton("profile.save." + name, Icon.SAVE, bx - 6, y + rowH / 2f, 13, Theme.TEXT_3, () -> {

				boolean saved = Config.saveProfileAs(name);

				Notifications.push(Lang.t(saved ? "ui.notify.profileSaved" : "ui.profileCode.saveFailed"), name, saved);

				profiles = null;

			});

			bx -= 30;

			button("profile.code." + name, Lang.t("ui.profileCode.share"), Icon.LINK, bx - 102, y + rowH / 2f - 14, 102, 28, false, () -> {

				profiles = null; mc.gui.setScreen(new ProfileShareScreen(this, name));

			});

			bx -= 112;

			if (!active) {

				button("profile.load." + name, Lang.t("ui.profiles.load"), Icon.DOWNLOAD, bx - 74, y + rowH / 2f - 13, 74, 26, true, () -> {

					if (Config.loadProfile(name)) Notifications.push(Lang.t("ui.notify.profileLoaded"), name, true);

					profiles = null;

				});

			}

			y += rowH + 6;

		}

		popHitClip();

		c.unclip();

		R2D.popAlpha();

	}



	private void createProfileFromField() {

		String name = profileField.text.trim();

		if (!name.isEmpty() && Config.createProfile(name)) {

			profileField.setText("");

			profiles = null;

			Notifications.push(Lang.t("ui.notify.profileSaved"), Config.sanitize(name), true);

		}

	}



	// ================================================================ input



	@Override

	public boolean mouseClicked(double mouseX, double mouseY, int button) {

		if (closing||transitionBusy()) return true;

		if (binding != null) {

			if (button >= 2) {

				applyBinding(KeyNames.MOUSE_OFFSET + button);

				return true;

			}

			binding = null;

		}

		focused = null;

		dispatchClick(mouseX, mouseY, button);

		return true;

	}



	@Override

	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {

		if(closing||transitionBusy())return true;

		if (dropdown != null) { dropdownScroll.scroll(scrollY); return true; }

		dispatchScroll(mouseX, mouseY, scrollY);

		return true;

	}



	@Override

	public boolean keyPressed(int key, int scanCode, int modifiers) {

		if (closing) return true;

		if(transitionBusy()) {if(key==GLFW.GLFW_KEY_ESCAPE)onClose();return true;}

		if (aiModelPicker && key == GLFW.GLFW_KEY_ESCAPE) { aiModelPicker = false; focused = null; return true; }

		if (tab == Tab.AI_PLAYERS && aiPlayersPanel.escape(key)) return true;

		if (binding != null) {

			if (key == GLFW.GLFW_KEY_ESCAPE) binding = null;

			else applyBinding(key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE ? -1 : key);

			return true;

		}

		if (focused != null) {

			if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_TAB) {

				focused = null;

				return true;

			}

			focused.keyPressed(key, modifiers);

			return true;

		}

		if (key == GLFW.GLFW_KEY_ESCAPE) {

			if (dropdown != null) dropdown = null;

			else if (drawer) {

				if (!drawerPage.equals("root")) drawerPage = "root";

				else drawer = false;

			} else if (tab == Tab.MODULES && selected != null) closeSettings();

			else onClose();

			return true;

		}

		// The key press that opened the GUI is delivered to it as well; ignore it.

		if (key == ClientSettings.INSTANCE.guiKey && System.currentTimeMillis() - openedAt > 250) {

			onClose();

			return true;

		}

		if (tab == Tab.MODULES && key == GLFW.GLFW_KEY_F && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {

			focused = search;

			search.allSelected = true;

			return true;

		}

		return false;

	}



	@Override

	public boolean charTyped(char chr, int modifiers) {

		if (closing || transitionBusy() || binding != null) return true;

		if (focused != null) return focused.charTyped(chr);

		if (tab == Tab.AI && !drawer && chr > ' ') {

			if (aiModelPicker) { focused = aiModelSearch; return aiModelSearch.charTyped(chr); }

			focused = aiRequest;

			aiRequest.cursor = aiRequest.text.length();

			return aiRequest.charTyped(chr);

		}

		if (tab == Tab.MODULES && !drawer && chr > ' ') {

			focused = search;

			search.cursor = search.text.length();

			return search.charTyped(chr);

		}

		return false;

	}



	private void applyBinding(int code) {

		if (binding == BIND_GUI) {

			if (code >= 0 && code != GLFW.GLFW_KEY_ESCAPE) {

				ClientSettings.INSTANCE.guiKey = code;

				Config.markDirty();

			}

		} else if (binding instanceof Module m) {

			m.setKey(code);

		}

		binding = null;

	}



	@Override public boolean mouseDragged(double mouseX,double mouseY,int button,double dx,double dy) {

		return closing||transitionBusy()||super.mouseDragged(mouseX,mouseY,button,dx,dy);

	}



	@Override

	public void onClose() {

		if(worldEmbedded&&com.cheatprogram.render.WorldControlPanel.enabled()){suspendWorldInput();com.cheatprogram.render.WorldControlPanel.leave();return;}

		closing = true;

		drag=null;

		aiModelPicker = false;

		aiPlayersPanel.deactivate();

		aiKey.masked = true;

		focused = null;

		dropdown = null;

	}



	// ================================================================ hooks for the dev screenshot automation



	public void devOpenSettings(Module m) {

		openSettings(m);

	}



	public void devCloseSettings() {

		closeSettings();

	}



	public void devDrawer(boolean open) {

		drawer = open;

		drawerPage = "root";

	}



	public void devDrawerPage(String page) {

		drawerPage = page;

	}



	public void devTab(int index) {

		switchTab(Tab.values()[index]);

	}



	public void devCategory(Category cat) {

		selectCategory(cat);

	}



	/** A virtual pointer for development screenshots, without changing the operating-system mouse. */

	public void devHover(float u, float v) {

		if (System.getProperty("cheatprogram.dev", "").isEmpty()) return;

		devHoverX = u;

		devHoverY = v;

	}



	/** Simulates a click at a point given in window units, going through the normal input path. */

	public void devClick(float u, float v, int button) {

		double[] g = devToGui(u, v);

		mouseClicked(g[0], g[1], button);

		mouseReleased(g[0], g[1], button);

	}



	public void devDrag(float u0, float v0, float u1, float v1) {

		double[] a = devToGui(u0, v0);

		double[] b = devToGui(u1, v1);

		mouseClicked(a[0], a[1], 0);

		mouseDragged(b[0], b[1], 0, b[0] - a[0], b[1] - a[1]);

		mouseReleased(b[0], b[1], 0);

	}



	private double[] devToGui(float u, float v) {

		var window = mc.getWindow();

		return new double[]{c.px(u) * window.getGuiScaledWidth() / (double) window.getWidth(),

				c.py(v) * window.getGuiScaledHeight() / (double) window.getHeight()};

	}



	public Category devCurrentCategory() {

		return category;

	}



	public Module devSelected() {

		return selected;

	}



	public boolean devDrawerOpen() {

		return drawer;

	}



	public void devSearch(String text) {

		search.setText(text);

		listSwitch = System.currentTimeMillis();

	}



	@Override

	public void removed() {

		transitions.close();customTransition=false;haloPreview.close();wearablePreview.close();

		aiPlayersPanel.close();

		aiKey.masked = true;

		AiController.markAiScreen(null);

		AiController.config().save();

		Config.saveAll();

	}

}

