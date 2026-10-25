package com.cheatprogram.gui;



import com.cheatprogram.ai.AiConfig;

import com.cheatprogram.ai.CredentialStore;

import com.cheatprogram.ai.AiModels;

import com.cheatprogram.ai.AiPlayerModels;

import com.cheatprogram.ai.AiPlayers;

import com.cheatprogram.core.Lang;

import com.cheatprogram.core.Notifications;

import com.cheatprogram.render.Icon;

import com.cheatprogram.render.R2D;

import com.cheatprogram.util.Anim;

import net.minecraft.resources.Identifier;

import org.lwjgl.glfw.GLFW;

import org.lwjgl.system.MemoryStack;

import org.lwjgl.util.tinyfd.TinyFileDialogs;



import javax.imageio.ImageIO;

import javax.imageio.ImageReader;

import java.awt.image.BufferedImage;

import java.net.URI;

import java.nio.file.Files;

import java.nio.file.Path;

import java.util.List;

import java.util.Locale;

import java.util.UUID;

import java.util.concurrent.ExecutorService;

import java.util.concurrent.Executors;



/** Character management shares the main glass window; game state and network ownership stay in AiPlayers. */

final class AiPlayersPanel {

	private static final List<String> PLAYER_MODES = List.of("survival", "creative", "adventure", "spectator", "hardcore");

	private static final List<String> GENDERS = List.of("none", "female", "male", "trans_female", "trans_male", "custom");

	private static final List<String> LANGUAGES=List.of("auto","zh","en","ja","ko","zh_tw","es","fr","de");

	private static final ExecutorService FILES = Executors.newSingleThreadExecutor(runnable -> {

		Thread thread = new Thread(runnable, "Cheat Program AI skin picker");

		thread.setDaemon(true);

		return thread;

	});

	private final ClickGuiScreen gui;

	private final TextField name = new TextField();
    private final TextField chat=new TextField();
    private final AiImagePicker chatImages=new AiImagePicker();

	private final TextField persona = new TextField();

	private final TextField personality = new TextField();

	private final TextField customGender = new TextField();

	private final TextField endpoint = new TextField();

	private final TextField model = new TextField();

	private final TextField key = new TextField();

	private final TextField skinPath = new TextField();

	private final TextField modelSearch = new TextField();

	private final GuiBase.Scroller listScroll = new GuiBase.Scroller();

	private final GuiBase.Scroller formScroll = new GuiBase.Scroller();

	private final GuiBase.Scroller modelsScroll = new GuiBase.Scroller();

	private UUID selected;

	private boolean useShared = true, loadingSkin, modelsOpen, genderOpen, closed;

	private String gender = "none";

	private boolean languageOpen;

	private float languageAnchorX,languageAnchorY,languageAnchorWidth;

	private float genderAnchorX, genderAnchorY, genderAnchorWidth;

	private long skinRequest;

	private String skinStatus = "";



	AiPlayersPanel(ClickGuiScreen gui) {

		this.gui = gui;

		key.masked = true;

		name.onChange = value -> apply();

		persona.onChange = value -> apply();

		personality.onChange = value -> apply();

		customGender.onChange = value -> apply();

		endpoint.onChange = value -> { applyConnection(); clearModels(); };

		model.onChange = value -> applyConnection();

		key.onChange = value -> {

			if (endpoint.text.isBlank() && (value.startsWith("sk-proj-") || value.startsWith("sk-svcacct-")))

				endpoint.setText("https://api.openai.com/v1");

			applyConnection(); clearModels();

		};

		skinPath.onEnter = this::loadSkinPath;

		modelSearch.onChange = value -> { modelsScroll.target = 0; modelsScroll.value.snap(0); };

	}



	boolean overlayOpen() { return modelsOpen || genderOpen || languageOpen; }



	private AiPlayers.Profile profile() {

		if (selected == null) return null;

		return AiPlayers.profiles().stream().filter(profile -> selected.equals(profile.id())).findFirst().orElse(null);

	}



	private void select(AiPlayers.Profile profile) {

		AiPlayers.save();

		gui.focused = null; modelsOpen = false; genderOpen = false; languageOpen=false; key.masked = true;

		selected = profile == null ? null : profile.id();chat.setText("");chatImages.clear();

		formScroll.target = 0; formScroll.value.snap(0);

		skinRequest++; loadingSkin = false; skinStatus = ""; skinPath.setText("");

		if (profile == null) return;

		name.setText(profile.name()); persona.setText(profile.persona()); personality.setText(profile.personality());

		gender = profile.gender(); customGender.setText(profile.customGender()); useShared = profile.useShared();

		loadConnection(profile);

	}



	private void sync(TextField field, String value) {

		if (gui.focused != field && !field.text.equals(value)) field.setText(value);

	}



	private void apply() {

		AiPlayers.Profile current = profile();

		if (current == null) return;

		AiPlayers.updateCharacter(selected, name.text, persona.text, personality.text, gender, customGender.text);

		if (current.useShared() != useShared)

			AiPlayers.update(selected, current.name(), current.persona(), useShared, current.endpoint(), current.model(), current.apiKey());

	}



	private void loadConnection(AiPlayers.Profile current) {

		if (useShared) {

			AiConfig connection = AiPlayers.sharedConfig();

			endpoint.setText(connection.endpoint()); model.setText(connection.model()); key.setText(connection.apiKey());

		} else {

			endpoint.setText(current.endpoint()); model.setText(current.model()); key.setText(current.apiKey());

		}

	}



	private void applyConnection() {

		if (selected == null) return;

		if (useShared) {

			AiConfig connection = AiPlayers.sharedConfig();

			if (!CredentialStore.sameEndpoint(connection.endpoint(), endpoint.text) && key.text.equals(connection.apiKey())) key.setText("");

			boolean changed = !connection.endpoint().equals(endpoint.text) || !connection.model().equals(model.text) || !connection.apiKey().equals(key.text);

			connection.endpoint(endpoint.text); connection.model(model.text); connection.apiKey(key.text);

			if (changed) AiPlayers.sharedConnectionChanged();

		} else {

			AiPlayers.Profile current = profile();

			if (current != null && !CredentialStore.sameEndpoint(current.endpoint(), endpoint.text) && key.text.equals(current.apiKey())) key.setText("");

			if (current != null && (!current.endpoint().equals(endpoint.text) || !current.model().equals(model.text) || !current.apiKey().equals(key.text)))

				AiPlayers.update(selected, name.text, persona.text, false, endpoint.text, model.text, key.text);

		}

	}



	private UUID connectionId() { return useShared ? AiPlayers.SHARED_CONNECTION_ID : selected; }

	private void clearModels() { if (selected != null) AiPlayerModels.clear(connectionId()); }

	private void chooseConnection(boolean shared) {

		if (useShared == shared) return;

		useShared = shared; apply(); key.masked = true; modelsOpen = false; genderOpen = false; gui.focused = null;

		AiPlayers.Profile updated = profile(); if (updated != null) loadConnection(updated);

	}



	void render() {

		closed = false;

		List<AiPlayers.Profile> profiles = AiPlayers.profiles();

		AiPlayers.Profile current = profile();

		if (current == null && !profiles.isEmpty()) { select(profiles.getFirst()); current = profile(); }

		float lx = 16, ly = 96, lw = 170, lh = ClickGuiScreen.H - ly - 16;

		gui.c.lens(lx, ly, lw, lh, 18, Theme.lift(.045f), Theme.lensGlass(gui.c.scale));

		gui.c.textC(GuiBase.BOLD, Lang.t("ui.tab.aiPlayers"), lx + 14, ly + 21, 12, Theme.TEXT);

		gui.iconButton("aip.add", Icon.PLUS, lx + lw - 23, ly + 21, 13, Theme.TEXT_2, () -> select(AiPlayers.create(selected)));

		gui.button("aip.all.play", Lang.t("ui.aip.allPlay"), null, lx + 10, ly + 42, 73, 25, false,

				() -> gameAction(() -> AiPlayers.setAllFreePlaying(true)));

		gui.button("aip.all.stop", Lang.t("ui.aip.allStop"), null, lx + 87, ly + 42, 73, 25, false,

				() -> AiPlayers.setAllFreePlaying(false));

		float listY = ly + 79, listH = lh - 118, rowH = 47;

		listScroll.max = Math.max(0, profiles.size() * rowH - listH); listScroll.update(gui.dt);

		gui.hit(lx + 8, listY, lw - 16, listH).scroll(listScroll::scroll);

		gui.c.clip(lx + 8, listY, lw - 16, listH); gui.pushHitClip(lx + 8, listY, lw - 16, listH);

		float yy = listY - listScroll.get();

		for (AiPlayers.Profile entry : profiles) {

			if (yy + rowH > listY && yy < listY + listH) {

				boolean active = entry.id().equals(selected);

				gui.c.lens(lx + 9, yy, lw - 18, rowH - 5, 12, Theme.lift(active ? .14f : .055f), Theme.lensGlass(gui.c.scale));

				head(entry.skinTexture(), lx + 18, yy + 11, 20);

				gui.c.textC(GuiBase.MED, shortText(entry.name(), 9.5f, lw - 94), lx + 46, yy + 14, 9.5f, Theme.TEXT);

				String state = Lang.t(entry.freePlaying() ? "ui.aip.playing" : entry.spawned() ? "ui.aip.present" : "ui.aip.absent");

				gui.c.textC(GuiBase.REG, state, lx + 46, yy + 29, 8, Theme.TEXT_3);

				gui.hit(lx + 9, yy, lw - 18, rowH - 5).left(() -> select(entry));

				gui.toggle("aip.play." + entry.id(), lx + lw - 44, yy + 13, entry.freePlaying(), 1);

				gui.hit(lx + lw - 48, yy + 8, 36, 25).left(() -> {

					if (entry.freePlaying()) AiPlayers.setFreePlaying(entry.id(), false);

					else gameAction(() -> AiPlayers.setFreePlaying(entry.id(), true));

				});

			}

			yy += rowH;

		}

		if (profiles.isEmpty()) gui.c.textC(GuiBase.REG, Lang.t("ui.aip.addHint"), lx + 14, listY + 24, 9, Theme.TEXT_3);

		gui.popHitClip(); gui.c.unclip();

		gui.c.textC(GuiBase.REG, shortText(Lang.t("ui.aip.multiHint"),7.5f,lw-28), lx + 14, ly + lh - 30, 7.5f, Theme.TEXT_3);

		gui.c.textC(GuiBase.REG, shortText(Lang.t("ui.aip.singleplayer"),8,lw-28), lx + 14, ly + lh - 14, 8, Theme.TEXT_3);



		float rx = 198, rw = ClickGuiScreen.W - rx - 16;

		gui.c.lens(rx, ly, rw, lh, 18, Theme.lift(.045f), Theme.lensGlass(gui.c.scale));

		if (current == null) {

			gui.c.icon(Icon.USER, rx + rw / 2, ly + 128, 34, Theme.TEXT_3);

			gui.c.textMid(GuiBase.MED, Lang.t("ui.aip.empty"), rx + rw / 2, ly + 170, 11, Theme.TEXT_3);

			return;

		}

		sync(name, current.name()); sync(persona, current.persona()); sync(personality, current.personality());

		gender = current.gender(); sync(customGender, current.customGender()); useShared = current.useShared();

		if (useShared) {

			AiConfig connection = AiPlayers.sharedConfig();

			sync(endpoint, connection.endpoint()); sync(model, connection.model()); sync(key, connection.apiKey());

		} else {

			sync(endpoint, current.endpoint()); sync(model, current.model()); sync(key, current.apiKey());

		}

		gui.c.textC(GuiBase.BOLD, shortText(current.name(), 12, rw - 224), rx + 14, ly + 21, 12, Theme.TEXT);

		UUID voiceId=current.id();

		gui.button("aip.voice",Lang.t("ui.voice.open"),null,rx+rw-194,ly+8,126,26,current.voice().enabled,

				()->gui.mc.gui.setScreen(new AiVoiceSettingsScreen(gui,voiceId)));

		gui.iconButton("aip.save", Icon.SAVE, rx + rw - 45, ly + 21, 12, Theme.TEXT_2, AiPlayers::save);

		gui.iconButton("aip.remove", Icon.TRASH, rx + rw - 20, ly + 21, 12, Theme.TEXT_3, () -> {

			UUID id = selected; AiPlayerModels.clear(id); AiPlayers.remove(id); select(null);

		});

		UUID id = current.id();

		gui.button("aip.spawn", Lang.t(current.spawned() ? "ui.aip.despawn" : "ui.aip.spawn"), Icon.USER,

				rx + 12, ly + 41, 104, 25, current.spawned(), () -> {

				if (profile() != null && profile().spawned()) AiPlayers.despawn(id);

				else gameAction(() -> AiPlayers.spawn(id));

			});

		gui.button("aip.play", Lang.t(current.freePlaying() ? "ui.aip.stop" : "ui.aip.play"), Icon.POWER,

				rx + 122, ly + 41, 104, 25, current.freePlaying(), () -> {

				if (profile() != null && profile().freePlaying()) AiPlayers.setFreePlaying(id, false);

				else gameAction(() -> AiPlayers.setFreePlaying(id, true));

			});

		gui.c.textC(GuiBase.REG, shortText(current.status(), 8, rw - 251), rx + 241, ly + 53, 8, Theme.TEXT_3);

		renderForm(current, rx, ly + 78, rw, lh - 102);

		gui.c.textC(GuiBase.REG, shortText(Lang.t("ui.aip.chatHint"), 8, rw - 28), rx + 14, ly + lh - 11, 8, Theme.TEXT_3);

	}



	private void renderForm(AiPlayers.Profile current, float x, float top, float w, float h) {

		float characterExtraHeight = "custom".equals(gender) ? 210 : 150;

		float contentHeight = (useShared ? 713 : 693) + characterExtraHeight + 220;

		formScroll.max = Math.max(0, contentHeight - h); formScroll.update(gui.dt);

		gui.hit(x + 8, top, w - 16, h).scroll(formScroll::scroll);

		gui.c.clip(x + 8, top, w - 16, h); gui.pushHitClip(x + 8, top, w - 16, h);

		float y = top - formScroll.get(), fx = x + 14, fw = w - 28;
        label("ui.ai.images.message",fx,y+11);
        gui.textField(chat,Lang.t("ui.ai.requestHint"),null,fx,y+23,fw-80,29);
        gui.button("aip.direct.send",Lang.t("ui.ai.send"),Icon.CHEVRON_RIGHT,fx+fw-74,y+23,74,29,true,()->{
            if(AiPlayers.directMessage(selected,chat.text,chatImages.images())){chat.setText("");chatImages.clear();}
            else Notifications.push(Lang.t("ui.tab.aiPlayers"),Lang.t("ui.ai.images.spawnFirst"),false);
        });
        chatImages.draw(gui,fx,y+58,fw);y+=128;


		label("ui.aip.language",fx,y+11);

		languageAnchorX=fx;languageAnchorY=y+23;languageAnchorWidth=fw;

		gui.selectBox("aip.language","",Lang.t("mode.ai_language_"+current.language()),fx,y+23,fw,29,languageOpen,()->{

			modelsOpen=genderOpen=false;languageOpen=true;gui.focused=null;

		});

		gui.c.textC(GuiBase.REG,shortText(Lang.t("ui.aip.languageHint"),8,fw),fx,y+64,8,Theme.TEXT_3);

		y+=80;

		label("ui.aip.name", fx, y + 11);

		gui.textField(name, Lang.t("ui.aip.nameHint"), Icon.USER, fx, y + 23, fw, 29);

		label("ui.aip.persona", fx, y + 70);

		gui.textField(persona, Lang.t("ui.aip.personaHint"), null, fx, y + 83, fw, 37);

		gui.c.textC(GuiBase.REG, shortText(Lang.t("ui.aip.personaLong"),8,fw), fx, y + 134, 8, Theme.TEXT_3);

		label("ui.aip.personality", fx, y + 155);

		gui.textField(personality, Lang.t("ui.aip.personalityHint"), null, fx, y + 168, fw, 37);

		label("ui.aip.gender", fx, y + 228);

		genderAnchorX = fx; genderAnchorY = y + 240; genderAnchorWidth = fw;

		gui.selectBox("aip.gender", "", Lang.t("mode.ai_gender_" + gender), fx, y + 240, fw, 29, genderOpen, () -> {

			modelsOpen = false; languageOpen=false; genderOpen = true; gui.focused = null;

		});

		if ("custom".equals(gender)) {

			label("ui.aip.customGender", fx, y + 288);

			gui.textField(customGender, Lang.t("ui.aip.customGenderHint"), null, fx, y + 301, fw, 29);

		}

		label("ui.aip.mode", fx, y + 155 + characterExtraHeight);

		float modeWidth = (fw - 24) / PLAYER_MODES.size();

		for (int i = 0; i < PLAYER_MODES.size(); i++) {

			String playerMode = PLAYER_MODES.get(i);

			gui.button("aip.mode." + playerMode, Lang.t("mode.ai_" + playerMode), null,

					fx + i * (modeWidth + 6), y + 167 + characterExtraHeight, modeWidth, 27, playerMode.equals(current.mode()),

					() -> AiPlayers.setMode(current.id(), playerMode));

		}

		if ("hardcore".equals(current.mode()))

			gui.c.textC(GuiBase.REG, shortText(Lang.t("ui.aip.hardcoreHint"), 8, fw), fx, y + 211 + characterExtraHeight, 8, Theme.TEXT_3);

		y += 80 + characterExtraHeight;

		label("ui.aip.skin", fx, y + 155);

		gui.textField(skinPath, Lang.t("ui.aip.skinPath"), Icon.FOLDER, fx, y + 167, fw - 119, 29);

		gui.button("aip.skin.pick", Lang.t("ui.aip.browse"), null, fx + fw - 113, y + 167, 54, 29, false, this::chooseSkin);

		gui.button("aip.skin.load", Lang.t("ui.aip.import"), null, fx + fw - 54, y + 167, 54, 29, false, this::loadSkinPath);

		gui.c.textC(GuiBase.REG, shortText(skinStatus.isEmpty() ? Lang.t("ui.aip.skinHint") : skinStatus, 8, fw), fx, y + 211, 8, Theme.TEXT_3);

		label("ui.aip.apiScope", fx, y + 240);

		gui.button("aip.private", Lang.t("ui.aip.apiPrivate"), null, fx + fw - 215, y + 229, 103, 25, !useShared, () -> chooseConnection(false));

		gui.button("aip.shared", Lang.t("ui.aip.apiShared"), null, fx + fw - 106, y + 229, 106, 25, useShared, () -> chooseConnection(true));

		gui.c.textC(GuiBase.MED, Lang.t(useShared ? "ui.aip.sharedConnection" : "ui.aip.ownConnection"), fx, y + 271, 10, Theme.TEXT);

		gui.button("aip.provider.openai", "OpenAI", null, fx, y + 288, 80, 25, atProvider("api.openai.com"), () -> provider("https://api.openai.com/v1"));

		gui.button("aip.provider.deepseek", "DeepSeek", null, fx + 86, y + 288, 83, 25, atProvider("api.deepseek.com"), () -> provider("https://api.deepseek.com"));

		gui.button("aip.provider.custom", Lang.t("ui.ai.custom"), null, fx + 175, y + 288, 80, 25, !atProvider("api.openai.com") && !atProvider("api.deepseek.com"), () -> gui.focused = endpoint);

		label("ui.ai.endpoint", fx, y + 329);

		gui.textField(endpoint, Lang.t("ui.ai.endpointHint"), null, fx, y + 343, fw, 29);

		label("ui.ai.model", fx, y + 391);

		gui.button("aip.models.refresh", Lang.t("ui.ai.refresh"), Icon.REFRESH, fx + fw - 79, y + 380, 79, 24, false, this::refreshModels);

		gui.textField(model, Lang.t("ui.ai.chooseModel"), null, fx, y + 405, fw - 38, 29);

		gui.button("aip.models.open", "", Icon.CHEVRON_DOWN, fx + fw - 32, y + 405, 32, 29, false, () -> {

			genderOpen = false; languageOpen=false; modelsOpen = true; modelSearch.setText(""); modelsScroll.target = 0; gui.focused = modelSearch;

		});

		gui.c.textC(GuiBase.REG, shortText(AiPlayerModels.state(connectionId()).status(), 8, fw), fx, y + 448, 8, Theme.TEXT_3);

		gui.c.textC(GuiBase.REG, shortText(Lang.t(useShared ? "ui.aip.keyShared" : "ui.aip.keyPrivate", current.name()), 10, fw - 90), fx, y + 471, 10, Theme.TEXT_2);

		gui.button("aip.key.mask", Lang.t(key.masked ? "ui.ai.showKey" : "ui.ai.hideKey"), Icon.EYE, fx + fw - 78, y + 459, 78, 24, false, () -> key.masked = !key.masked);

		gui.textField(key, Lang.t("ui.ai.keyHint"), null, fx, y + 485, fw, 29);

		gui.c.textC(GuiBase.REG, shortText(Lang.t("ui.ai.keyMemory"),8,fw), fx, y + 530, 8, Theme.TEXT_3);

		gui.c.textC(GuiBase.REG, shortText(Lang.t(useShared ? AiPlayers.sharedConfig().credentialStatusKey() : AiPlayers.credentialStatusKey(current.id())),8,fw), fx, y + 545, 8, Theme.TEXT_3);

		gui.button("aip.key.clear", Lang.t("ui.ai.clearKey"), Icon.TRASH, fx, y + 567, 116, 25, false, () -> {

			key.setText(""); key.masked = true;

			if (useShared) { AiPlayers.sharedConfig().deleteApiKey(); AiPlayers.sharedConnectionChanged(); }

			else AiPlayers.deleteApiKey(current.id());

			clearModels();

		});

		gui.button("aip.save.form", Lang.t("ui.aip.save"), Icon.SAVE, fx + 122, y + 567, 116, 25, false, AiPlayers::save);

		if (useShared) gui.c.textC(GuiBase.REG, shortText(Lang.t("ui.aip.sharedHint"), 8, fw), fx, y + 613, 8, Theme.TEXT_3);

		gui.popHitClip(); gui.c.unclip();

	}



	private void label(String id, float x, float y) { gui.c.textC(GuiBase.REG, Lang.t(id), x, y, 10, Theme.TEXT_2); }

	private String shortText(String text, float size, float width) {

		// A very long persona or status is retained in its model; titles render only a visible prefix.

		String visible = text.length() > 512 ? text.substring(0, 512) : text;

		return gui.c.ellipsize(GuiBase.REG, visible, size, width);

	}



	private void head(Identifier skin, float x, float y, float size) {

		if (skin == null) { gui.c.icon(Icon.USER, x + size / 2, y + size / 2, size - 2, Theme.TEXT_3); return; }

		R2D.texture(skin, gui.c.px(x), gui.c.py(y), size * gui.c.scale, size * gui.c.scale, 8 / 64f, 8 / 64f, 16 / 64f, 16 / 64f, 0xFFFFFFFF);

		R2D.texture(skin, gui.c.px(x), gui.c.py(y), size * gui.c.scale, size * gui.c.scale, 40 / 64f, 8 / 64f, 48 / 64f, 16 / 64f, 0xFFFFFFFF);

	}



	private void provider(String address) { endpoint.setText(address); applyConnection(); clearModels(); gui.focused = key; key.masked = true; }

	private boolean atProvider(String host) {

		try { return host.equalsIgnoreCase(URI.create(endpoint.text.strip()).getHost()); }

		catch (RuntimeException invalid) { return false; }

	}

	private void refreshModels() { if (selected != null) { applyConnection(); AiPlayerModels.refresh(connectionId(), endpoint.text, key.text); } }

	private void gameAction(Runnable action) {

		if (gui.mc.player == null || !gui.mc.hasSingleplayerServer()) {

			Notifications.push(Lang.t("ui.tab.aiPlayers"), Lang.t("ui.aip.singleplayer"), false); return;

		}

		apply(); AiPlayers.save(); action.run();

	}



	void renderOverlay() {

		AiPlayers.Profile current = profile();

		if(languageOpen&&current!=null){renderLanguageOverlay(current);return;}

		if (genderOpen && current != null) { renderGenderOverlay(); return; }

		if (!modelsOpen || current == null) return;

		float x = 244, y = 103, w = 456, h = 319;

		gui.c.rrect(0, 82, ClickGuiScreen.W, ClickGuiScreen.H - 82, 0, Anim.black(.16f));

		gui.hit(0, 0, ClickGuiScreen.W, ClickGuiScreen.H).click(button -> { modelsOpen = false; gui.focused = null; }).scroll(amount -> { });

		gui.c.shadow(x, y + 5, w, h, 18, 24, Anim.black(.22f));

		gui.c.lens(x, y, w, h, 18, Theme.lift(.12f), Theme.lensGlass(gui.c.scale));

		gui.hit(x, y, w, h).click(button -> { }).scroll(amount -> { });

		gui.c.textC(GuiBase.BOLD, Lang.t("ui.ai.modelsTitle"), x + 16, y + 21, 12, Theme.TEXT);

		gui.iconButton("aip.models.close", Icon.CLOSE, x + w - 20, y + 21, 12, Theme.TEXT_2, () -> { modelsOpen = false; gui.focused = null; });

		gui.textField(modelSearch, Lang.t("ui.ai.modelSearch"), Icon.SEARCH, x + 12, y + 39, w - 24, 28);

		AiModels.State catalog = AiPlayerModels.state(connectionId());

		gui.c.textC(GuiBase.REG, shortText(catalog.status(), 8.5f, w - 28), x + 14, y + 81, 8.5f, Theme.TEXT_3);

		String query = modelSearch.text.strip().toLowerCase(Locale.ROOT);

		List<AiModels.Model> models = catalog.models().stream().filter(entry -> entry.id().toLowerCase(Locale.ROOT).contains(query)

				|| entry.name().toLowerCase(Locale.ROOT).contains(query)).toList();

		float listY = y + 96, listH = h - 108, rowH = 39;

		modelsScroll.max = Math.max(0, models.size() * rowH - listH); modelsScroll.update(gui.dt);

		gui.hit(x + 10, listY, w - 20, listH).scroll(modelsScroll::scroll);

		gui.c.clip(x + 10, listY, w - 20, listH); gui.pushHitClip(x + 10, listY, w - 20, listH);

		if (models.isEmpty()) gui.c.textC(GuiBase.REG, Lang.t(catalog.loading() ? "ui.ai.modelsLoading" : "ui.ai.modelsEmpty"), x + 20, listY + 22, 10, Theme.TEXT_3);

		float rowY = listY - modelsScroll.get();

		for (AiModels.Model entry : models) {

			if (rowY + rowH >= listY && rowY < listY + listH) {

				boolean active = entry.id().equals(model.text);

				if (active || entry.usable() && gui.over(x + 12, rowY, w - 24, rowH - 3))

					gui.c.lens(x + 12, rowY, w - 24, rowH - 3, 11, Theme.lift(active ? .14f : .07f), Theme.lensGlass(gui.c.scale));

				gui.c.textC(GuiBase.MED, shortText(entry.name(), 10.5f, w - 54), x + 23, rowY + 13, 10.5f, entry.usable() ? Theme.TEXT : Theme.TEXT_4);

				gui.c.textC(GuiBase.REG, shortText(entry.tier() + (entry.name().equals(entry.id()) ? "" : " · " + entry.id()), 8, w - 54), x + 23, rowY + 27, 8, Theme.TEXT_3);

				if (entry.usable()) gui.hit(x + 12, rowY, w - 24, rowH - 3).left(() -> {

					model.setText(entry.id()); applyConnection(); AiPlayers.save(); modelsOpen = false; gui.focused = null;

				});

			}

			rowY += rowH;

		}

		gui.popHitClip(); gui.c.unclip();

	}

	private void renderGenderOverlay() {

		float w = Math.min(320, genderAnchorWidth), rowHeight = 31, h = GENDERS.size() * rowHeight + 12;

		float x = Math.max(12, Math.min(ClickGuiScreen.W - w - 12, genderAnchorX));

		float y = genderAnchorY + 35;

		if (y + h > ClickGuiScreen.H - 12) y = genderAnchorY - h - 6;

		y = Math.max(90, Math.min(ClickGuiScreen.H - h - 12, y));

		gui.c.rrect(0, 82, ClickGuiScreen.W, ClickGuiScreen.H - 82, 0, Anim.black(.13f));

		gui.hit(0, 0, ClickGuiScreen.W, ClickGuiScreen.H).click(button -> { genderOpen = false; gui.focused = null; }).scroll(amount -> { });

		gui.c.shadow(x, y + 5, w, h, 15, 22, Anim.black(.22f));

		gui.c.lens(x, y, w, h, 15, Theme.lift(.12f), Theme.lensGlass(gui.c.scale));

		gui.hit(x, y, w, h).click(button -> { }).scroll(amount -> { });

		for (int index = 0; index < GENDERS.size(); index++) {

			String choice = GENDERS.get(index); float rowY = y + 6 + index * rowHeight;

			boolean active = choice.equals(gender);

			if (active || gui.over(x + 6, rowY, w - 12, rowHeight - 2))

				gui.c.lens(x + 6, rowY, w - 12, rowHeight - 2, 9, Theme.lift(active ? .16f : .08f), Theme.lensGlass(gui.c.scale));

			gui.c.textC(GuiBase.REG, Lang.t("mode.ai_gender_" + choice), x + 17, rowY + 14, 10.5f, Theme.TEXT);

			if (active) gui.c.icon(Icon.CHECK, x + w - 21, rowY + 14, 11, Theme.TEXT_2);

			gui.hit(x + 6, rowY, w - 12, rowHeight - 2).left(() -> {

				gender = choice; genderOpen = false; gui.focused = null; apply();

			});

		}

	}



	boolean escape(int keyCode) {

		if (keyCode != GLFW.GLFW_KEY_ESCAPE || !overlayOpen()) return false;

		modelsOpen = false; genderOpen = false; languageOpen=false; gui.focused = null; return true;

	}



	void deactivate() { modelsOpen = false; genderOpen = false; languageOpen=false; key.masked = true; AiPlayers.save(); }

	private void renderLanguageOverlay(AiPlayers.Profile profile){

		float w=Math.min(320,languageAnchorWidth),row=28,h=LANGUAGES.size()*row+12;

		float x=Math.max(12,Math.min(ClickGuiScreen.W-w-12,languageAnchorX));

		float y=Math.max(90,Math.min(ClickGuiScreen.H-h-12,languageAnchorY+35));

		gui.c.rrect(0,82,ClickGuiScreen.W,ClickGuiScreen.H-82,0,Anim.black(.13f));

		gui.hit(0,0,ClickGuiScreen.W,ClickGuiScreen.H).click(button->{languageOpen=false;gui.focused=null;}).scroll(a->{});

		gui.c.shadow(x,y+5,w,h,15,22,Anim.black(.22f));gui.c.lens(x,y,w,h,15,Theme.lift(.12f),Theme.lensGlass(gui.c.scale));

		gui.hit(x,y,w,h).click(b->{}).scroll(a->{});

		for(int i=0;i<LANGUAGES.size();i++){

			String choice=LANGUAGES.get(i);float ry=y+6+i*row;boolean selected=choice.equals(profile.language());

			if(selected||gui.over(x+6,ry,w-12,row-2))gui.c.lens(x+6,ry,w-12,row-2,9,Theme.lift(selected?.16f:.08f),Theme.lensGlass(gui.c.scale));

			gui.c.textC(GuiBase.REG,Lang.t("mode.ai_language_"+choice),x+17,ry+14,10.5f,Theme.TEXT);

			if(selected)gui.c.icon(Icon.CHECK,x+w-21,ry+14,11,Theme.TEXT_2);

			gui.hit(x+6,ry,w-12,row-2).left(()->{AiPlayers.setLanguage(profile.id(),choice);languageOpen=false;gui.focused=null;});

		}

	}

	void close() { deactivate(); closed = true; skinRequest++;chatImages.close(); }



	private void chooseSkin() {

		if (loadingSkin || selected == null || closed) return;

		UUID id = selected; long token = ++skinRequest; loadingSkin = true; skinStatus = Lang.t("ui.aip.skinLoading");

		FILES.execute(() -> {

			String result = null; String failure = null;

			try (MemoryStack stack = MemoryStack.stackPush()) {

				var filters = stack.mallocPointer(1); filters.put(stack.UTF8("*.png")).flip();

				result = TinyFileDialogs.tinyfd_openFileDialog(Lang.t("ui.aip.skin"), null, filters, "PNG", false);

			} catch (Throwable unavailable) { failure = "ui.aip.pickerUnavailable"; }

			String picked = result, error = failure;

			gui.mc.execute(() -> {

				if (closed || token != skinRequest || !id.equals(selected)) return;

				loadingSkin = false;

				if (error != null) skinStatus = Lang.t(error);

				else if (picked != null) { skinPath.setText(picked); loadSkinPath(); }

				else skinStatus = "";

			});

		});

	}



	private void loadSkinPath() {

		if (loadingSkin || selected == null || closed || skinPath.text.isBlank()) return;

		String value = skinPath.text.strip();

		if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 1) value = value.substring(1, value.length() - 1);

		String path = value; UUID id = selected; long token = ++skinRequest;

		loadingSkin = true; skinStatus = Lang.t("ui.aip.skinLoading");

		FILES.execute(() -> {

			BufferedImage decoded = null; String failure = null;

			try { decoded = readSkin(Path.of(path)); }

			catch (SkinProblem problem) { failure = problem.key; }

			catch (OutOfMemoryError large) { failure = "ui.aip.skinTooLarge"; }

			catch (Exception error) { failure = "ui.aip.skinError"; }

			BufferedImage image = decoded; String error = failure;

			gui.mc.execute(() -> {

				if (closed || token != skinRequest || !id.equals(selected)) { if (image != null) image.flush(); return; }

				if (error != null || image == null) { loadingSkin = false; skinStatus = Lang.t(error == null ? "ui.aip.skinError" : error); return; }

				try {

					AiPlayers.importSkin(id, image).whenComplete((ignored, problem) -> gui.mc.execute(() -> {

						if (closed || token != skinRequest || !id.equals(selected)) return;

						loadingSkin = false; skinStatus = Lang.t(problem == null ? "ui.aip.skinReady" : "ui.aip.skinError");

					}));

				} catch (Exception unavailable) { loadingSkin = false; skinStatus = Lang.t("ui.aip.skinError"); }

				finally { image.flush(); }

			});

		});

	}



	private static BufferedImage readSkin(Path path) throws Exception {

		if (!Files.isRegularFile(path)) throw new SkinProblem("ui.aip.skinError");

		if (Files.size(path) > 32L * 1024 * 1024) throw new SkinProblem("ui.aip.skinTooLarge");

		try (var stream = ImageIO.createImageInputStream(path.toFile())) {

			if (stream == null) throw new SkinProblem("ui.aip.skinError");

			var readers = ImageIO.getImageReaders(stream);

			if (!readers.hasNext()) throw new SkinProblem("ui.aip.skinFormat");

			ImageReader reader = readers.next();

			try {

				if (!reader.getFormatName().equalsIgnoreCase("png")) throw new SkinProblem("ui.aip.skinFormat");

				reader.setInput(stream, true, true);

				int width = reader.getWidth(0), height = reader.getHeight(0);

				if (width < 64 || width % 64 != 0 || height != width && height * 2 != width) throw new SkinProblem("ui.aip.skinSize");

				if (width > 4096 || height > 4096 || (long) width * height > 16_777_216) throw new SkinProblem("ui.aip.skinTooLarge");

				return reader.read(0);

			} finally { reader.dispose(); }

		}

	}



	private static final class SkinProblem extends Exception {

		private final String key;

		private SkinProblem(String key) { this.key = key; }

	}

}
