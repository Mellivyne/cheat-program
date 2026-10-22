package com.cheatprogram.dev;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Config;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.core.setting.Setting;
import com.cheatprogram.gui.HudEditorScreen;
import com.cheatprogram.hud.HudLayout;
import com.cheatprogram.hud.HudRenderer;
import com.cheatprogram.hud.MusicCapsule;
import com.cheatprogram.media.MusicService;
import com.cheatprogram.util.GradientSweep;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.ClickGuiScreen;
import com.cheatprogram.module.combat.Reach;
import com.cheatprogram.module.legit.InfoPanel;
import com.cheatprogram.module.legit.Keystrokes;
import com.cheatprogram.module.movement.Sprint;
import com.cheatprogram.module.visual.Hud;
import com.cheatprogram.module.world.Scaffold;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.google.gson.JsonElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Development-only automation that walks through the GUI and saves screenshots so the look can be
 * reviewed without clicking. The focused island suite uses -Dcheatprogram.dev=island-shots;
 * the original GUI suite uses -Dcheatprogram.dev=shots. Both are inactive in normal installs.
 */
public final class DevTools {
	private static final String MODE = System.getProperty("cheatprogram.dev", "");
	private static final String WORLD = "cp_dev";
	private static int stage;
	private static int wait;
	private static ClickGuiScreen gui;
	private static volatile boolean capturingScreenshot;
	private static int islandFailures;
	private static int islandScreenshots;
	private static long islandStarted;
	private static HudEditorScreen editor;
	private static float[] draggedIsland;
	private static final Map<Setting<?>, JsonElement> savedSettings = new LinkedHashMap<>();
	private static final Map<Module, ModuleState> savedModules = new LinkedHashMap<>();
	private static JsonElement savedLayout;
	private static String savedProfile;
	private static TutorialSteps savedTutorial;
	private static boolean savedPause;
	private static boolean savedHideGui;
	private static ItemStack[] savedHotbar;
	private static ItemStack savedOffhand;
	private static volatile boolean styleWorldPrepared;
	private static long styleDaytime;
	private static boolean styleDaylight;
	private static float musicIdleWidth;
	private static MusicService.Track musicRapidFinal;
	private static int musicMotionFrames;
	private static long musicMotionStarted;
	private static int musicReadyStage = -1;
	private static long musicReadyDeadline;
	private static int musicReadyWidth;
	private static int musicReadyHeight;
	private static int musicReadyReports;
	private static float[] musicReadyBounds;
	private static boolean musicWaitLogged;
	private static long musicCompatPreparedAt;

	private record ModuleState(boolean enabled, int key, boolean hold, boolean favorite) {
	}

	private DevTools() {
	}

	public static void tick() {
		if (MODE.equals("logic") || MODE.equals("logic-scaffold-smoke")) {
			LogicDevTools.tick();
			return;
		}
		if (!MODE.contains("shots")) return;
		Minecraft mc = Minecraft.getInstance();
		if ((MODE.equals("island-shots") || MODE.equals("style-shots") || MODE.equals("music-shots")) && islandStarted != 0 && stage != 99
				&& System.currentTimeMillis() - islandStarted > 180_000) {
			CheatProgram.LOGGER.error("DEV ISLAND timed out at stage {}", stage);
			islandFailures++;
			finishIsland(mc);
			return;
		}
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			if (MODE.equals("island-shots")) stepIsland(mc);
			else if (MODE.equals("style-shots")) stepStyle(mc);
			else if (MODE.equals("music-shots")) stepMusic(mc);
			else step(mc);
		} catch (Throwable t) {
			CheatProgram.LOGGER.error("Dev automation failed at stage {}", stage, t);
			if (MODE.equals("island-shots") || MODE.equals("style-shots") || MODE.equals("music-shots")) {
				islandFailures++;
				finishIsland(mc);
			}
			else stage = 99;
		}
	}

	/** The screenshot mixin uses this to keep automation captures out of the notification queue. */
	public static boolean isCapturingScreenshot() {
		return capturingScreenshot;
	}

	private static void stepMusic(Minecraft mc) {
		switch (stage) {
			case 0 -> {
				if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
				islandStarted = System.currentTimeMillis();
				savedTutorial = mc.options.tutorialStep;
				savedPause = mc.options.pauseOnLostFocus;
				savedHideGui = mc.options.hideGui;
				snapshotIslandState(mc);
				mc.options.tutorialStep = TutorialSteps.NONE;
				mc.options.pauseOnLostFocus = false;
				mc.options.hideGui = false;
				for (Module module : ModuleManager.all()) module.setEnabled(false);
				ClientSettings.INSTANCE.activeProfile = "music_dev";
				ClientSettings.INSTANCE.language.set("zh");
				ClientSettings.INSTANCE.autoSave.set(false);
				ClientSettings.INSTANCE.notifications.set(true);
				MusicService.devClear();
				MusicCapsule.devElapsed(Float.NaN);
				CheatProgram.LOGGER.info("DEV MUSIC starting mocked metadata screenshots in {}", WORLD);
				next(20);
			}
			case 1 -> {
				if (mc.getLevelSource().levelExists(WORLD)) {
					mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
				} else {
					LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
							new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
					mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261011L, true, false),
							WorldPresets::createNormalWorldDimensions, new TitleScreen());
				}
				next(0);
			}
			case 2 -> {
				if (mc.level != null && mc.player != null && mc.screen == null) next(40);
			}
			case 3 -> {
				var server = mc.getSingleplayerServer();
				if (server == null || !WORLD.equals(server.getWorldData().getLevelName())) {
					throw new IllegalStateException("Music screenshots require the cp_dev singleplayer world");
				}
				savedHotbar = new ItemStack[9];
				for (int slot = 0; slot < 9; slot++) savedHotbar[slot] = mc.player.getInventory().getItem(slot).copy();
				savedOffhand = mc.player.getOffhandItem().copy();
				mc.player.setYRot(-35);
				mc.player.setXRot(12);
				Hud hud = ModuleManager.get(Hud.class);
				for (Setting<?> setting : hud.settings()) {
					if (setting.id.equals("island") || setting.id.equals("notifications") || setting.id.equals("glass")) {
						setting.load(new com.google.gson.JsonPrimitive(true));
					} else if (setting.id.equals("arraylist")) setting.load(new com.google.gson.JsonPrimitive(false));
				}
				hud.setEnabled(true);
				HudLayout.reset();
				Notifications.entries().clear();
				server.execute(() -> {
					styleDaytime = server.overworld().getDayTime();
					styleDaylight = server.overworld().getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
					server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
					server.overworld().setDayTime(1000);
					styleWorldPrepared = true;
				});
				next(35);
			}
			case 4 -> {
				if (!styleWorldPrepared) return;
				musicPhase("none", "");
				musicIslandBounds(mc, "baseline");
				float[] bounds = HudLayout.bounds("island");
				musicIdleWidth = bounds == null ? 0 : bounds[2];
				musicShot("01_no_session");
				MusicService.devTrack(new MusicService.Track("dev:music:short", "网易云音乐", "少女が見た日本の原風景", "ばんばんしー", true));
				MusicCapsule.devElapsed(0.2f);
				next(15);
			}
			case 5 -> {
				musicPhase("pop", "少女が見た日本の原風景");
				musicShot("02_pop");
				MusicCapsule.devElapsed(1.2f);
				next(15);
			}
			case 6 -> {
				musicPhase("hold", "少女が見た日本の原風景");
				var state = MusicCapsule.devState();
				musicCheck("held capsule sits at the top left", state != null && state.x() >= 2
						&& state.x() < islandScreenWidth(mc) * 0.25f && state.y() < 45);
				float[] islandBounds = HudLayout.bounds("island");
				musicCheck("held music capsule aligns vertically with the main capsule", state != null && islandBounds != null
						&& Math.abs(state.y() + state.h() / 2 - islandBounds[1] - islandBounds[3] / 2) < 1);
				musicShot("03_hold");
				MusicCapsule.devElapsed(2.95f);
				next(15);
			}
			case 7 -> {
				musicPhase("absorb", "少女が見た日本の原風景");
				musicShot("04_absorb");
				MusicCapsule.devElapsed(3.6f);
				next(30);
			}
			case 8 -> {
				musicPhase("docked", "少女が見た日本の原風景");
				musicIslandBounds(mc, "short title docked");
				musicShot("05_docked");
				MusicService.devTrack(new MusicService.Track("dev:music:long", "QQ音乐",
						"当风吹过幻想乡的夜空——这一段很长很长的中文歌名用于检查灵动岛宽度变化与居中显示", "测试歌手", true));
				MusicCapsule.devElapsed(3.6f);
				next(30);
			}
			case 9 -> {
				musicPhase("docked", "当风吹过幻想乡的夜空——这一段很长很长的中文歌名用于检查灵动岛宽度变化与居中显示");
				musicIslandBounds(mc, "long Chinese title docked");
				musicShot("06_long_chinese_title");
				MusicService.devTrack(new MusicService.Track("dev:music:rapidA", "QQ音乐", "切歌测试 A", "测试歌手", true));
				MusicCapsule.devElapsed(2.95f);
				next(2);
			}
			case 10 -> {
				musicPhase("absorb", "切歌测试 A");
				MusicService.devTrack(new MusicService.Track("dev:music:rapidB", "Spotify", "切歌测试 B", "测试歌手", true));
				MusicCapsule.devElapsed(0.2f);
				next(2);
			}
			case 11 -> {
				musicPhase("pop", "切歌测试 B");
				musicCheck("switching to B cancels A's uncommitted flight", MusicCapsule.devState() != null
						&& "当风吹过幻想乡的夜空——这一段很长很长的中文歌名用于检查灵动岛宽度变化与居中显示".equals(MusicCapsule.devState().slotTitle()));
				musicRapidFinal = new MusicService.Track("dev:music:rapidC", "网易云音乐", "最后一首：夜空中的幻想乡", "测试歌手", true);
				MusicService.devTrack(musicRapidFinal);
				MusicCapsule.devElapsed(1.2f);
				next(15);
			}
			case 12 -> {
				musicPhase("hold", musicRapidFinal.title());
				musicCheck("rapid switches leave the latest metadata", MusicService.current() != null
						&& MusicService.current().id().equals(musicRapidFinal.id()));
				musicCheck("C's announcement preserves the last committed title rather than A", MusicCapsule.devState() != null
						&& "当风吹过幻想乡的夜空——这一段很长很长的中文歌名用于检查灵动岛宽度变化与居中显示".equals(MusicCapsule.devState().slotTitle()));
				musicShot("07_rapid_switch_latest");
				MusicCapsule.devElapsed(Float.NaN);
				next(80); // Let the real animation age dock before testing pause with the real clock.
			}
			case 13 -> {
				musicPhase("docked", musicRapidFinal.title());
				MusicService.devTrack(new MusicService.Track(musicRapidFinal.id(), musicRapidFinal.source(),
						musicRapidFinal.title(), musicRapidFinal.artist(), false));
				next(15);
			}
			case 14 -> {
				musicPhase("docked", musicRapidFinal.title());
				musicCheck("pausing the same song does not replay the popup", MusicService.current() != null
						&& !MusicService.current().playing() && MusicCapsule.devState() != null && MusicCapsule.devState().docked());
				musicCompatibilityNotices();
				setIslandBlocks(mc, 64);
				ModuleManager.get(Scaffold.class).setEnabled(true);
				musicCompatPreparedAt = System.currentTimeMillis();
				next(15);
			}
			case 15 -> {
				if (!musicCaptureReady(mc)) return;
				if (System.currentTimeMillis() - musicCompatPreparedAt > 1800) {
					CheatProgram.LOGGER.warn("DEV MUSIC renewing timed notices after waiting {}ms for a rendered framebuffer", System.currentTimeMillis() - musicCompatPreparedAt);
					musicCompatibilityNotices();
					musicCompatPreparedAt = System.currentTimeMillis();
					wait = 12;
					return;
				}
				musicPhase("docked", musicRapidFinal.title());
				musicCheck("three notices and Scaffold data coexist with music", Notifications.entries().size() == 3
						&& ModuleManager.get(Scaffold.class).blocksLeft() == 64);
				musicIslandBounds(mc, "music with notifications and Scaffold");
				musicShot("08_paused_notifications_scaffold");
				ModuleManager.get(Scaffold.class).setEnabled(false);
				Notifications.entries().clear();
				MusicService.devClear();
				MusicCapsule.devElapsed(Float.NaN);
				restoreIslandInventory(mc);
				next(40);
			}
			case 16 -> {
				musicPhase("none", "");
				musicCheck("clearing sessions removes the media metadata", MusicService.current() == null);
				musicIslandBounds(mc, "cleared session");
				float[] bounds = HudLayout.bounds("island");
				musicCheck("cleared island returns to the baseline width", bounds != null && musicIdleWidth > 0 && Math.abs(bounds[2] - musicIdleWidth) < 30);
				musicShot("09_session_cleared");
				next(20);
			}
			case 17 -> {
				MusicService.devTrack(new MusicService.Track("dev:music:motion", "网易云音乐", "夜空中的幻想乡", "测试歌手", true));
				MusicCapsule.devElapsed(Float.NaN);
				musicMotionFrames = 0;
				musicMotionStarted = System.currentTimeMillis();
				next(0);
			}
			case 18 -> {
				// One skipped tick between captures gives the real 20 TPS animation a 10 FPS sequence.
				shot(String.format(java.util.Locale.ROOT, "music_motion_%03d", musicMotionFrames));
				var state = MusicCapsule.devState();
				CheatProgram.LOGGER.info("DEV MUSIC MOTION frame={} elapsed={}ms phase={}", musicMotionFrames,
						System.currentTimeMillis() - musicMotionStarted, state == null ? "<none>" : state.phase());
				musicMotionFrames++;
				if (musicMotionFrames < 42) wait = 1;
				else {
					musicCheck("real-time motion finishes docked", state != null && state.docked());
					MusicService.devClear();
					next(40);
				}
			}
			case 19 -> {
				musicPhase("none", "");
				CheatProgram.LOGGER.info("DEV MUSIC MOTION finished: {} separate animation frames (music_motion_000.png through music_motion_041.png)", musicMotionFrames);
				devSetting("hud", "arraylist", new com.google.gson.JsonPrimitive(true));
				devSetting("hud", "arrayGradient", new com.google.gson.JsonPrimitive(true));
				for (String id : java.util.List.of("sprint", "esp", "tracers", "storageesp", "fullbright", "nohurtcam", "targethud", "infopanel", "keystrokes"))
					ModuleManager.byId(id).setEnabled(true);
				GradientSweep.freezePhase(0.4f);
				Notifications.entries().clear();
				next(30);
			}
			case 20 -> {
				if (!musicCaptureReady(mc)) return;
				musicCheck("gradient list stays visible", HudLayout.bounds("arraylist") != null);
				musicShot("13_gradient_lower");
				GradientSweep.freezePhase(0.85f);
				next(15);
			}
			case 21 -> {
				musicShot("14_gradient_upper");
				devSetting("hud", "arrayGradient", new com.google.gson.JsonPrimitive(false));
				GradientSweep.releasePhase();
				next(20);
			}
			case 22 -> {
				musicShot("15_gradient_disabled");
				devSetting("hud", "arrayGradient", new com.google.gson.JsonPrimitive(true));
				gui = new ClickGuiScreen();
				mc.setScreen(gui);
				gui.devTab(0);
				gui.devCategory(Category.VISUAL);
				gui.devOpenSettings(ModuleManager.get(Hud.class));
				next(30);
			}
			case 23 -> {
				musicShot("16_hud_settings");
				Module lines = ModuleManager.byId("entitylines");
				musicCheck("new line module is registered", lines != null);
				if (lines != null) {
					devSetting("entitylines", "targets", new com.google.gson.JsonPrimitive("line_named"));
					devSetting("entitylines", "players", new com.google.gson.JsonPrimitive("DevPlayerOne，DevPlayerTwo"));
					devSetting("entitylines", "mode", new com.google.gson.JsonPrimitive("shortest"));
					gui.devOpenSettings(lines);
				}
				next(30);
			}
			case 24 -> {
				musicShot("17_entity_lines_settings");
				finishIsland(mc);
			}
			default -> { }
		}
	}

	private static void devSetting(String moduleId, String id, JsonElement value) {
		Module module = ModuleManager.byId(moduleId);
		for (Setting<?> setting : module.settings()) if (setting.id.equals(id)) { setting.load(value); return; }
		throw new IllegalArgumentException("Missing dev setting: " + moduleId + "." + id);
	}

	private static void musicPhase(String phase, String title) {
		var state = MusicCapsule.devState();
		musicCheck("phase is " + phase, state != null && phase.equals(state.phase()));
		if (!title.isEmpty()) musicCheck("current capsule shows the expected title", state != null && title.equals(state.title()));
		if (state != null) CheatProgram.LOGGER.info("DEV MUSIC STATE phase={} title={} bounds={},{},{},{} docked={} slotTitle={}",
				state.phase(), state.title(), state.x(), state.y(), state.w(), state.h(), state.docked(), state.slotTitle());
	}

	private static void musicCompatibilityNotices() {
		Notifications.entries().clear();
		Notifications.moduleToggled(ModuleManager.get(Hud.class));
		Notifications.push(Icon.SAVE, "配置已保存", "音乐与通知兼容测试");
		Notifications.push(Icon.LAYOUT, "HUD 居中", "长曲名仍保持居中");
	}

	/** A resized RenderTarget is empty until a real HUD frame has been rendered into it. */
	private static boolean musicCaptureReady(Minecraft mc) {
		long now = System.currentTimeMillis();
		var target = mc.getMainRenderTarget();
		if (musicReadyStage != stage) {
			musicReadyStage = stage;
			musicReadyDeadline = now + 5000;
			musicReadyWidth = target.width;
			musicReadyHeight = target.height;
			musicReadyReports = 0;
			musicReadyBounds = null;
			musicWaitLogged = false;
		}
		if (musicReadyWidth != target.width || musicReadyHeight != target.height) {
			musicReadyWidth = target.width;
			musicReadyHeight = target.height;
			musicReadyReports = 0;
			musicReadyBounds = null;
			musicWaitLogged = false;
		}
		float[] bounds = HudLayout.bounds("island");
		boolean valid = mc.player != null && mc.level != null && mc.screen == null && mc.getOverlay() == null
				&& !mc.isPaused() && !mc.options.hideGui && ModuleManager.on(Hud.class)
				&& target.width > 0 && target.height > 0
				&& target.width == mc.getWindow().getWidth() && target.height == mc.getWindow().getHeight()
				&& R2D.width() == target.width && R2D.height() == target.height
				&& bounds != null && bounds[2] > 0 && bounds[3] > 0;
		if (valid && bounds != musicReadyBounds) {
			musicReadyBounds = bounds;
			musicReadyReports++;
		} else if (!valid) {
			musicReadyBounds = null;
			musicReadyReports = 0;
		}
		if (valid && musicReadyReports >= 2 && now <= musicReadyDeadline) {
			CheatProgram.LOGGER.info("DEV MUSIC capture ready: stage={} framebuffer={}x{} freshHudReports={}", stage, target.width, target.height, musicReadyReports);
			return true;
		}
		String state = "stage=" + stage + " window=" + mc.getWindow().getWidth() + "x" + mc.getWindow().getHeight()
				+ " framebuffer=" + target.width + "x" + target.height + " rendered=" + R2D.width() + "x" + R2D.height()
				+ " bounds=" + (bounds == null ? "<missing>" : java.util.Arrays.toString(bounds))
				+ " freshHudReports=" + musicReadyReports + " paused=" + mc.isPaused() + " hideGui=" + mc.options.hideGui
				+ " screen=" + (mc.screen == null ? "<none>" : mc.screen.getClass().getName())
				+ " overlay=" + (mc.getOverlay() == null ? "<none>" : mc.getOverlay().getClass().getName());
		if (!musicWaitLogged) {
			CheatProgram.LOGGER.warn("DEV MUSIC waiting for a freshly rendered capture: {}", state);
			musicWaitLogged = true;
		}
		if (now > musicReadyDeadline) throw new IllegalStateException("Music compatibility capture could not obtain a rendered HUD within 5 seconds: " + state);
		return false;
	}

	private static void musicIslandBounds(Minecraft mc, String label) {
		float[] bounds = HudLayout.bounds("island");
		musicCheck(label + " island is visible", bounds != null);
		if (bounds == null) return;
		musicCheck(label + " stays centred after changing width", Math.abs(bounds[0] + bounds[2] / 2 - islandScreenWidth(mc) / 2) < 2);
		musicCheck(label + " fits the upper screen", bounds[0] >= 2 && bounds[0] + bounds[2] <= islandScreenWidth(mc) - 1
				&& bounds[1] >= 2 && bounds[1] + bounds[3] < islandScreenHeight(mc) / 2);
	}

	private static void musicCheck(String what, boolean ok) {
		check("MUSIC " + what, ok);
		if (!ok) islandFailures++;
	}

	private static void musicShot(String name) {
		shot("music_" + name);
		islandScreenshots++;
	}

	private static void stepStyle(Minecraft mc) {
		switch (stage) {
			case 0 -> {
				if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
				islandStarted = System.currentTimeMillis();
				savedTutorial = mc.options.tutorialStep;
				savedPause = mc.options.pauseOnLostFocus;
				savedHideGui = mc.options.hideGui;
				snapshotIslandState(mc);
				mc.options.tutorialStep = TutorialSteps.NONE;
				mc.options.pauseOnLostFocus = false;
				mc.options.hideGui = false;
				for (Module module : ModuleManager.all()) {
					module.setEnabled(false);
					module.setFavorite(false);
				}
				ClientSettings.INSTANCE.activeProfile = "style_dev";
				ClientSettings.INSTANCE.language.set("zh");
				CheatProgram.LOGGER.info("DEV STYLE starting glass comparison screenshots in {}", WORLD);
				next(20);
			}
			case 1 -> {
				if (mc.getLevelSource().levelExists(WORLD)) {
					mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
				} else {
					LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
							new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
					mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261011L, true, false),
							WorldPresets::createNormalWorldDimensions, new TitleScreen());
				}
				next(0);
			}
			case 2 -> {
				if (mc.level != null && mc.player != null && mc.screen == null) next(40);
			}
			case 3 -> {
				var server = mc.getSingleplayerServer();
				if (server == null || !WORLD.equals(server.getWorldData().getLevelName())) {
					throw new IllegalStateException("Style screenshots require the cp_dev singleplayer world");
				}
				Module hundred = ModuleManager.byId("hundredmeteraura");
				if (hundred == null) throw new IllegalStateException("HundredMeterAura is not registered");
				mc.player.setYRot(-35);
				mc.player.setXRot(12);
				gui = new ClickGuiScreen();
				mc.setScreen(gui); // Keep the screen open before either combat module is enabled.
				gui.devTab(0);
				gui.devCategory(Category.FAVORITES);
				gui.devHover(-1000, -1000);
				hundred.setFavorite(true);
				ModuleManager.get(Reach.class).setFavorite(true);
				hundred.setEnabled(true);
				ModuleManager.get(Reach.class).setEnabled(true);
				Notifications.entries().clear();
				server.execute(() -> {
					styleDaytime = server.overworld().getDayTime();
					styleDaylight = server.overworld().getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);
					server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
					server.overworld().setDayTime(1000);
					styleWorldPrepared = true;
				});
				next(40);
			}
			case 4 -> {
				if (!styleWorldPrepared) return;
				check("STYLE both comparison modules are enabled with the GUI open", mc.screen == gui
						&& ModuleManager.byId("hundredmeteraura").isEnabled() && ModuleManager.on(Reach.class));
				styleShot("01_on_normal");
				gui.devHover(400, 155);
				next(25);
			}
			case 5 -> {
				styleShot("02_on_hover_hundred");
				gui.devHover(-1000, -1000);
				gui.devOpenSettings(ModuleManager.byId("hundredmeteraura"));
				next(30);
			}
			case 6 -> {
				styleShot("03_on_selected_hundred");
				gui.devCloseSettings();
				next(35);
			}
			case 7 -> {
				styleShot("04_on_normal_restored");
				ModuleManager.byId("hundredmeteraura").setEnabled(false);
				gui.devHover(400, 155);
				next(25);
			}
			case 8 -> {
				styleShot("05_off_hover_hundred");
				next(20);
			}
			case 9 -> finishIsland(mc);
			default -> { }
		}
	}

	private static void styleShot(String name) {
		shot("style_" + name);
		islandScreenshots++;
	}

	private static void stepIsland(Minecraft mc) {
		switch (stage) {
			case 0 -> {
				if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
				islandStarted = System.currentTimeMillis();
				savedTutorial = mc.options.tutorialStep;
				savedPause = mc.options.pauseOnLostFocus;
				savedHideGui = mc.options.hideGui;
				mc.options.tutorialStep = TutorialSteps.NONE;
				mc.options.pauseOnLostFocus = false;
				mc.options.hideGui = false;
				CheatProgram.LOGGER.info("DEV ISLAND starting focused screenshots in {}", WORLD);
				next(20);
			}
			case 1 -> {
				if (mc.getLevelSource().levelExists(WORLD)) {
					mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
				} else {
					LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
							new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
					mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261011L, true, false),
							WorldPresets::createNormalWorldDimensions, new TitleScreen());
				}
				next(0);
			}
			case 2 -> {
				if (mc.level != null && mc.player != null && mc.screen == null) next(80);
			}
			case 3 -> {
				if (mc.getSingleplayerServer() == null
						|| !WORLD.equals(mc.getSingleplayerServer().getWorldData().getLevelName())) {
					throw new IllegalStateException("Island development checks require the cp_dev singleplayer world");
				}
				snapshotIslandState(mc);
				ClientSettings.INSTANCE.activeProfile = "island_dev";
				ClientSettings.INSTANCE.language.set("zh");
				ClientSettings.INSTANCE.notifications.set(true);
				ClientSettings.INSTANCE.keybinds.set(true);
				for (Module module : ModuleManager.all()) module.setEnabled(module instanceof Hud);
				Hud hud = ModuleManager.get(Hud.class);
				for (Setting<?> setting : hud.settings()) {
					if (setting.id.equals("island") || setting.id.equals("notifications") || setting.id.equals("glass")) {
						setting.load(new com.google.gson.JsonPrimitive(true));
					}
				}
				// Looking forwards keeps the legit Scaffold from placing any development blocks.
				for (Setting<?> setting : ModuleManager.get(Scaffold.class).settings()) {
					if (setting.id.equals("mode")) setting.load(new com.google.gson.JsonPrimitive("legit"));
				}
				HudLayout.reset();
				Notifications.entries().clear();
				mc.player.setYRot(-35f);
				mc.player.setXRot(12f);
				mc.getSingleplayerServer().execute(() -> mc.getSingleplayerServer().overworld().setDayTime(1000));
				next(40);
			}
			case 4 -> {
				islandBoundsCheck(mc, "idle pill", 22);
				islandShot("01_idle_zh");
				gui = new ClickGuiScreen();
				mc.setScreen(gui);
				gui.devTab(0);
				gui.devCategory(Category.MOVEMENT);
				next(35);
			}
			case 5 -> {
				Notifications.entries().clear();
				gui.devClick(650, 155, 0);
				next(12);
			}
			case 6 -> {
				islandCheck("GUI click enables Sprint", ModuleManager.on(Sprint.class));
				islandCheck("GUI click produces one module notification", Notifications.entries().size() == 1
						&& Notifications.entries().get(0).kind == Notifications.Kind.MODULE);
				islandShot("02_gui_toggle");
				mc.setScreen(null);
				Notifications.entries().clear();
				Sprint sprint = ModuleManager.get(Sprint.class);
				for (Module module : ModuleManager.all()) {
					if (module != sprint && module.getKey() == GLFW.GLFW_KEY_F8) module.setKey(-1);
				}
				sprint.setKey(GLFW.GLFW_KEY_F8);
				sprint.setHoldBind(false);
				ModuleManager.onInput(GLFW.GLFW_KEY_F8, GLFW.GLFW_PRESS);
				next(12);
			}
			case 7 -> {
				islandCheck("keyboard input disables Sprint", !ModuleManager.on(Sprint.class));
				islandCheck("keyboard toggle produces one notification", Notifications.entries().size() == 1);
				islandBoundsCheck(mc, "keyboard notification", 41);
				islandShot("03_keyboard_toggle");
				Notifications.entries().clear();
				Notifications.moduleToggled(ModuleManager.get(Sprint.class));
				Notifications.push(Icon.SAVE, "配置已保存", "液态玻璃测试配置");
				Notifications.push(Icon.LAYOUT, "HUD 位置已保存", "顶部居中");
				next(12);
			}
			case 8 -> {
				islandCheck("three notifications are stacked", Notifications.entries().size() == 3);
				islandBoundsCheck(mc, "three-entry stack", 103);
				islandShot("04_three_notifications");
				Notifications.entries().clear();
				setIslandBlocks(mc, 128);
				ModuleManager.get(Scaffold.class).setEnabled(true);
				next(25);
			}
			case 9 -> {
				Scaffold scaffold = ModuleManager.get(Scaffold.class);
				islandCheck("Scaffold reports 128 blocks and a full bar", scaffold.blocksLeft() == 128 && scaffold.progress() > 0.99f);
				islandBoundsCheck(mc, "Scaffold full", 52);
				islandShot("05_scaffold_full");
				setIslandBlocks(mc, 16);
				next(20);
			}
			case 10 -> {
				Scaffold scaffold = ModuleManager.get(Scaffold.class);
				islandCheck("Scaffold bar follows remaining blocks", scaffold.blocksLeft() == 16 && Math.abs(scaffold.progress() - 0.125f) < 0.01f);
				islandShot("06_scaffold_low");
				setIslandBlocks(mc, 0);
				next(20);
			}
			case 11 -> {
				Scaffold scaffold = ModuleManager.get(Scaffold.class);
				islandCheck("Scaffold exhausted state is zero", scaffold.blocksLeft() == 0 && scaffold.progress() == 0f);
				islandShot("07_scaffold_empty");
				scaffold.setEnabled(false);
				next(30);
			}
			case 12 -> {
				islandBoundsCheck(mc, "idle after Scaffold disabled", 22);
				islandShot("08_scaffold_disabled_idle");
				editor = new HudEditorScreen(null);
				mc.setScreen(editor);
				next(30);
			}
			case 13 -> {
				islandBoundsCheck(mc, "editor top-centred pill", 22);
				islandShot("09_editor_unobstructed");
				float[] bounds = HudLayout.bounds("island");
				if (bounds == null) throw new IllegalStateException("Island is not visible in the HUD editor");
				float x = bounds[0] + bounds[2] / 2;
				float y = bounds[1] + bounds[3] / 2;
				float targetY = islandScreenHeight(mc) * 0.30f;
				double[] a = islandToGui(mc, x, y);
				double[] b = islandToGui(mc, islandScreenWidth(mc) / 2, targetY);
				editor.mouseClicked(a[0], a[1], 0);
				editor.mouseDragged(b[0], b[1], 0, b[0] - a[0], b[1] - a[1]);
				editor.mouseReleased(b[0], b[1], 0);
				draggedIsland = HudLayout.fraction("island").clone();
				next(20);
			}
			case 14 -> {
				islandCheck("editor drag keeps island centred and moves it down", HudLayout.centered("island") && draggedIsland[1] > 0.20f);
				islandShot("10_editor_dragged");
				Config.saveAll();
				HudLayout.reset();
				islandCheck("HUD reset restores top-centre anchor", HudLayout.fraction("island")[0] == 0.5f
						&& Math.abs(HudLayout.fraction("island")[1] - 0.010f) < 0.0001f);
				Config.loadAll();
				next(20);
			}
			case 15 -> {
				float[] loaded = HudLayout.fraction("island");
				islandCheck("saved editor position survives config reload", Math.abs(loaded[0] - draggedIsland[0]) < 0.0001f
						&& Math.abs(loaded[1] - draggedIsland[1]) < 0.0001f);
				islandShot("11_editor_saved_reloaded");
				editor.onClose();
				HudLayout.reset();
				ClientSettings.INSTANCE.language.set("en");
				next(30);
			}
			case 16 -> {
				islandBoundsCheck(mc, "English idle pill", 22);
				islandShot("12_idle_en");
				ModuleManager.get(Sprint.class).setEnabled(true);
				Notifications.moduleToggled(ModuleManager.get(Sprint.class));
				next(12);
			}
			case 17 -> {
				islandCheck("English module title is translated", Notifications.entries().size() == 1
						&& Notifications.entries().get(0).title.equals(ModuleManager.get(Sprint.class).name()));
				islandShot("13_notification_en");
				Notifications.entries().clear();
				ClientSettings.INSTANCE.language.set("zh");
				setIslandBlocks(mc, 64);
				ModuleManager.get(Scaffold.class).setEnabled(true);
				next(20);
			}
			case 18 -> {
				islandCheck("Chinese language restored", ClientSettings.INSTANCE.language.is("zh"));
				islandShot("14_scaffold_zh");
				ModuleManager.get(Scaffold.class).setEnabled(false);
				restoreIslandInventory(mc);
				next(20);
			}
			case 19 -> finishIsland(mc);
			default -> { }
		}
	}

	private static void snapshotIslandState(Minecraft mc) {
		savedProfile = ClientSettings.INSTANCE.activeProfile;
		savedLayout = HudLayout.save().deepCopy();
		for (Setting<?> setting : ClientSettings.INSTANCE.all) savedSettings.put(setting, setting.save().deepCopy());
		for (Module module : ModuleManager.all()) {
			savedModules.put(module, new ModuleState(module.isEnabled(), module.getKey(), module.isHoldBind(), module.isFavorite()));
			for (Setting<?> setting : module.settings()) savedSettings.put(setting, setting.save().deepCopy());
		}
		if (mc.player != null) {
			savedHotbar = new ItemStack[9];
			for (int slot = 0; slot < 9; slot++) savedHotbar[slot] = mc.player.getInventory().getItem(slot).copy();
			savedOffhand = mc.player.getOffhandItem().copy();
		}
	}

	private static void setIslandBlocks(Minecraft mc, int count) {
		ItemStack[] hotbar = new ItemStack[9];
		for (int slot = 0; slot < 9; slot++) {
			int n = Math.min(64, Math.max(0, count - slot * 64));
			hotbar[slot] = n == 0 ? ItemStack.EMPTY : new ItemStack(Items.STONE, n);
		}
		setIslandInventory(mc, hotbar, ItemStack.EMPTY);
	}

	private static void restoreIslandInventory(Minecraft mc) {
		if (savedHotbar != null && mc.player != null && mc.getSingleplayerServer() != null) {
			setIslandInventory(mc, savedHotbar, savedOffhand);
		}
	}

	private static void setIslandInventory(Minecraft mc, ItemStack[] hotbar, ItemStack offhand) {
		if (mc.getSingleplayerServer() == null || !WORLD.equals(mc.getSingleplayerServer().getWorldData().getLevelName())) {
			throw new IllegalStateException("Development inventory data is restricted to cp_dev");
		}
		for (int slot = 0; slot < 9; slot++) mc.player.getInventory().setItem(slot, hotbar[slot].copy());
		mc.player.getInventory().setItem(40, offhand.copy());
		var uuid = mc.player.getUUID();
		var server = mc.getSingleplayerServer();
		server.execute(() -> {
			var player = server.getPlayerList().getPlayer(uuid);
			if (player == null) return;
			for (int slot = 0; slot < 9; slot++) player.getInventory().setItem(slot, hotbar[slot].copy());
			player.getInventory().setItem(40, offhand.copy());
			player.inventoryMenu.broadcastChanges();
		});
	}

	private static float islandScreenWidth(Minecraft mc) {
		return mc.getWindow().getWidth() / HudRenderer.hudScale();
	}

	private static float islandScreenHeight(Minecraft mc) {
		return mc.getWindow().getHeight() / HudRenderer.hudScale();
	}

	private static double[] islandToGui(Minecraft mc, float x, float y) {
		float scale = HudRenderer.hudScale();
		return new double[]{x * scale * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getWidth(),
				y * scale * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getHeight()};
	}

	private static void islandBoundsCheck(Minecraft mc, String label, float expectedHeight) {
		float[] bounds = HudLayout.bounds("island");
		islandCheck(label + " is visible", bounds != null);
		if (bounds == null) return;
		islandCheck(label + " is horizontally centred", Math.abs(bounds[0] + bounds[2] / 2 - islandScreenWidth(mc) / 2) < 2f);
		islandCheck(label + " is fully inside the upper screen", bounds[1] >= 2 && bounds[1] + bounds[3] < islandScreenHeight(mc) / 2);
		islandCheck(label + " has expected settled height", Math.abs(bounds[3] - expectedHeight) < 5f);
	}

	private static void islandCheck(String what, boolean ok) {
		check("ISLAND " + what, ok);
		if (!ok) islandFailures++;
	}

	private static void islandShot(String name) {
		shot("island_" + name);
		islandScreenshots++;
	}

	private static void finishIsland(Minecraft mc) {
		stage = 99;
		try {
			restoreIslandInventory(mc);
			if (MODE.equals("music-shots")) {
				MusicCapsule.devElapsed(Float.NaN);
				MusicService.devRelease();
				GradientSweep.releasePhase();
			}
			if ((MODE.equals("style-shots") || MODE.equals("music-shots")) && styleWorldPrepared && mc.getSingleplayerServer() != null) {
				var server = mc.getSingleplayerServer();
				server.execute(() -> {
					server.overworld().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(styleDaylight, server);
					server.overworld().setDayTime(styleDaytime);
				});
			}
			if (savedProfile != null) {
				for (Map.Entry<Setting<?>, JsonElement> entry : savedSettings.entrySet()) entry.getKey().load(entry.getValue());
				for (Map.Entry<Module, ModuleState> entry : savedModules.entrySet()) {
					Module module = entry.getKey();
					ModuleState state = entry.getValue();
					module.setKey(state.key());
					module.setHoldBind(state.hold());
					module.setFavorite(state.favorite());
					module.setEnabled(state.enabled());
				}
				HudLayout.load(savedLayout.getAsJsonObject());
				ClientSettings.INSTANCE.activeProfile = savedProfile;
				Config.saveAll();
			}
			if (savedTutorial != null) {
				mc.options.tutorialStep = savedTutorial;
				mc.options.pauseOnLostFocus = savedPause;
				mc.options.hideGui = savedHideGui;
			}
			Notifications.entries().clear();
			CheatProgram.LOGGER.info("DEV {} finished: {} screenshots, {} failed checks. Screenshots: {}", MODE.equals("style-shots") ? "STYLE" : MODE.equals("music-shots") ? "MUSIC" : "ISLAND",
					islandScreenshots, islandFailures, mc.gameDirectory.toPath().resolve("screenshots"));
		} catch (Throwable t) {
			CheatProgram.LOGGER.error("DEV ISLAND could not restore development state", t);
		} finally {
			mc.stop();
		}
	}

	private static void step(Minecraft mc) {
		switch (stage) {
			case 0 -> {
				if (mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
					mc.options.tutorialStep = TutorialSteps.NONE;
					mc.options.pauseOnLostFocus = false;
					next(60);
				}
			}
			case 1 -> {
				ClientSettings.INSTANCE.language.set(MODE.contains("en") ? "en" : "zh");
				gui = new ClickGuiScreen();
				mc.setScreen(gui);
				next(60);
			}
			case 2 -> {
				shot("01_title_gui");
				mc.setScreen(new TitleScreen());
				next(10);
			}
			case 3 -> {
				if (mc.getLevelSource().levelExists(WORLD)) {
					mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
				} else {
					LevelSettings settings = new LevelSettings(WORLD, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
							new GameRules(FeatureFlags.DEFAULT_FLAGS), WorldDataConfiguration.DEFAULT);
					mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(20261011L, true, false),
							WorldPresets::createNormalWorldDimensions, new TitleScreen());
				}
				next(0);
			}
			case 4 -> {
				if (mc.level != null && mc.player != null && mc.screen == null) next(160);
			}
			case 5 -> {
				for (Class<? extends Module> type : java.util.List.of(Hud.class, Keystrokes.class, InfoPanel.class, Sprint.class, Reach.class)) {
					ModuleManager.get(type).setEnabled(true);
				}
				mc.player.setYRot(-35f);
				mc.player.setXRot(12f);
				var server = mc.getSingleplayerServer();
				if (server != null) server.execute(() -> server.overworld().setDayTime(1000));
				next(60);
			}
			case 6 -> {
				shot("02_hud");
				gui = new ClickGuiScreen();
				mc.setScreen(gui);
				next(60);
			}
			case 7 -> {
				shot("03_gui_modules");
				gui.devOpenSettings(ModuleManager.get(Reach.class));
				next(45);
			}
			case 8 -> {
				shot("04_gui_settings");
				gui.devCloseSettings();
				gui.devDrawer(true);
				next(45);
			}
			case 9 -> {
				shot("05_gui_drawer");
				gui.devDrawerPage("general");
				next(40);
			}
			case 10 -> {
				shot("06_gui_drawer_general");
				gui.devDrawer(false);
				gui.devTab(2);
				next(40);
			}
			case 11 -> {
				shot("07_gui_profiles");
				gui.devTab(0);
				gui.devSearch("auto");
				next(40);
			}
			case 12 -> {
				shot("08_gui_search");
				gui.devSearch("");
				gui.onClose();
				next(40);
			}
			case 13 -> {
				// Smoke test: every regular module on at once for a few seconds.
				for (Module m : ModuleManager.all()) if (!m.isOneShot()) m.setEnabled(true);
				next(80);
			}
			case 14 -> {
				shot("09_all_modules");
				for (Module m : ModuleManager.all()) {
					if (m.isEnabled() && !(m instanceof Hud) && !(m instanceof Keystrokes) && !(m instanceof InfoPanel)) m.setEnabled(false);
				}
				next(30);
			}
			case 15 -> {
				ClientSettings.INSTANCE.language.set("en");
				gui = new ClickGuiScreen();
				mc.setScreen(gui);
				gui.devCategory(Category.VISUAL);
				next(60);
			}
			case 16 -> {
				shot("10_gui_en_visual");
				ClientSettings.INSTANCE.language.set("zh");
				gui.devCategory(Category.INVENTORY);
				next(40);
			}
			case 17 -> {
				shot("11_gui_inventory");
				gui.devCategory(Category.MACRO);
				next(40);
			}
			case 18 -> {
				shot("12_gui_macro");
				gui.devTab(1);
				next(40);
			}
			case 19 -> {
				shot("13_gui_friends");
				gui.devTab(0);
				mc.setScreen(new HudEditorScreen(gui));
				next(50);
			}
			case 20 -> {
				shot("14_hud_editor");
				gui = new ClickGuiScreen();
				mc.setScreen(gui);
				next(40);
			}
			// Interaction checks: clicks go through mouseClicked exactly like real input.
			case 21 -> {
				gui.devClick(74, 126 + 2 * 33 + 15, 0);
				next(10);
			}
			case 22 -> {
				check("sidebar click selects Movement", gui.devCurrentCategory() == Category.MOVEMENT);
				Module first = ModuleManager.byCategory(Category.MOVEMENT).get(0);
				toggledBefore = first.isEnabled();
				gui.devClick(650, 155, 0);
				next(10);
			}
			case 23 -> {
				Module first = ModuleManager.byCategory(Category.MOVEMENT).get(0);
				check("toggle switch flips " + first.id, first.isEnabled() != toggledBefore);
				first.setEnabled(toggledBefore);
				gui.devClick(400, 155 + 48, 1);
				next(30);
			}
			case 24 -> {
				Module second = ModuleManager.byCategory(Category.MOVEMENT).get(1);
				check("right click opens settings of " + second.id, gui.devSelected() == second);
				gui.devDrag(479 + 218 * 0.1f, 183, 479 + 218 * 0.9f, 183);
				next(20);
			}
			case 25 -> {
				Module second = ModuleManager.byCategory(Category.MOVEMENT).get(1);
				Object speed = second.settings().get(0).get();
				check("slider drag sets " + second.id + " speed (now " + speed + ")", speed instanceof Double d && Math.abs(d - 4.5) < 0.11);
				shot("15_click_settings");
				second.settings().get(0).reset();
				gui.devClick(720 - 37, 29, 0);
				next(30);
			}
			case 26 -> {
				check("gear opens the settings drawer", gui.devDrawerOpen());
				shot("16_click_drawer");
				gui.onClose();
				next(30);
			}
			case 27 -> {
				if (!MODE.contains("keep")) mc.stop();
				next(0);
			}
			default -> {
			}
		}
	}

	private static boolean toggledBefore;

	private static void check(String what, boolean ok) {
		CheatProgram.LOGGER.info("DEV CHECK {}: {}", ok ? "OK  " : "FAIL", what);
	}

	private static void next(int ticks) {
		stage++;
		wait = ticks;
	}

	private static void shot(String name) {
		Minecraft mc = Minecraft.getInstance();
		capturingScreenshot = true;
		try {
			Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), 1, message -> CheatProgram.LOGGER.info("Dev screenshot: {}", message.getString()));
		} finally {
			capturingScreenshot = false;
		}
	}
}
