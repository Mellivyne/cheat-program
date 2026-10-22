package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.hud.DynamicIsland;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.hud.LiquidToggleToasts;
import com.cheatprogram.hud.ModuleArrayList;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.render.PaletteText;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.GradientSweep;
import com.cheatprogram.util.MotionCurve;

import java.util.List;

public class Hud extends Module {
	private final BoolSetting island = add(new BoolSetting("island", true));
	private final BoolSetting worldScreen=add(new BoolSetting("worldScreen",false));
	private final NumberSetting worldScreenWidth=add(new NumberSetting("worldScreenWidth",4,1,20,.1,"blocks")).visibleWhen(worldScreen::on);
	private final NumberSetting worldScreenDistance=add(new NumberSetting("worldScreenDistance",3,1,8,.25,"blocks")).visibleWhen(()->false);
	private final NumberSetting worldScreenYaw=add(new NumberSetting("worldScreenYaw",0,-180,180,1,"°")).visibleWhen(worldScreen::on);
	private final NumberSetting worldScreenPitch=add(new NumberSetting("worldScreenPitch",0,-80,80,1,"°")).visibleWhen(worldScreen::on);
	private final NumberSetting worldScreenRoll=add(new NumberSetting("worldScreenRoll",0,-180,180,1,"°")).visibleWhen(worldScreen::on);
	private final NumberSetting worldScreenOpacity=add(new NumberSetting("worldScreenOpacity",100,25,100,5,"%")).visibleWhen(worldScreen::on);
	private final NumberSetting worldScreenFrameRate=add(new NumberSetting("worldScreenFrameRate",60,15,240,1,"FPS")).visibleWhen(worldScreen::on);
	private final NumberSetting worldScreenBrightness=add(new NumberSetting("worldScreenBrightness",100,10,200,5,"%")).visibleWhen(worldScreen::on);
	// Read old profile/code fields without reintroducing the retired hover interaction.
	private final BoolSetting legacyWorldScreenAutoInteract=add(new BoolSetting("worldScreenAutoInteract",false)).visibleWhen(()->false);
	private final BoolSetting legacyWorldScreenDoublePress=add(new BoolSetting("worldScreenDoublePress",true)).visibleWhen(()->false);
	private final BoolSetting worldScreenThroughWalls=add(new BoolSetting("worldScreenThroughWalls",false)).visibleWhen(worldScreen::on);
	private final NumberSetting worldScreenDoubleMillis=add(new NumberSetting("worldScreenDoubleMillis",350,180,700,10,"ms")).visibleWhen(worldScreen::on);
	private final BoolSetting titleIntro=add(new BoolSetting("titleIntro",true));
	private final ModeSetting titleIntroPalette=add(new ModeSetting("titleIntroPalette","intro_rainbow","intro_white","intro_rainbow","intro_trans")).visibleWhen(titleIntro::on);
	private final NumberSetting titleIntroWriteTime=add(new NumberSetting("titleIntroWriteTime",3000,800,10000,100,"ms")).visibleWhen(titleIntro::on);
	private final NumberSetting titleIntroHoldTime=add(new NumberSetting("titleIntroHoldTime",700,0,5000,100,"ms")).visibleWhen(titleIntro::on);
	private final NumberSetting titleIntroFadeTime=add(new NumberSetting("titleIntroFadeTime",450,150,2000,50,"ms")).visibleWhen(titleIntro::on);
	private final NumberSetting titleIntroSize=add(new NumberSetting("titleIntroSize",100,30,120,5,"%")).visibleWhen(titleIntro::on);
	private final NumberSetting titleIntroY=add(new NumberSetting("titleIntroY",48,15,80,1,"%")).visibleWhen(titleIntro::on);
	private final NumberSetting titleIntroThickness=add(new NumberSetting("titleIntroThickness",100,50,180,5,"%")).visibleWhen(titleIntro::on);
	private final NumberSetting titleIntroGlow=add(new NumberSetting("titleIntroGlow",70,0,150,5,"%")).visibleWhen(titleIntro::on);
	private final BoolSetting arraylist = add(new BoolSetting("arraylist", true));
	private final ModeSetting arraySide = add(new ModeSetting("arraySide", "array_right", "array_right", "array_left")).visibleWhen(arraylist::on);
	private final NumberSetting arrayScale = add(new NumberSetting("arrayScale", 100, 50, 200, 5, "%")).visibleWhen(arraylist::on);
	private final BoolSetting arrayRail = add(new BoolSetting("arrayRail", true)).visibleWhen(arraylist::on);
	private final ModeSetting arrayPalette = add(new ModeSetting("arrayPalette", "palette_white", "palette_white", "palette_rainbow", "palette_trans")).visibleWhen(arraylist::on);
	private final BoolSetting arrayColorAnimation = add(new BoolSetting("arrayColorAnimation", true)).visibleWhen(this::arrayColorsVisible);
	private final NumberSetting arrayColorPeriod = add(new NumberSetting("arrayColorPeriod", 6, .5, 60, .1, " s")).visibleWhen(() -> arrayColorsVisible() && arrayColorAnimation.on());
	private final NumberSetting arrayColorStrength = add(new NumberSetting("arrayColorStrength", 100, 0, 100, 5, "%")).visibleWhen(() -> arrayColorsVisible() && arrayColorAnimation.on());
	private final ModeSetting arrayColorDirection = add(new ModeSetting("arrayColorDirection", "palette_flow_left", "palette_flow_left", "palette_flow_right")).visibleWhen(() -> arrayColorsVisible() && arrayColorAnimation.on());
	private final NumberSetting arrayColorPhase = add(new NumberSetting("arrayColorPhase", 0, 0, 100, 1, "%")).visibleWhen(this::arrayColorsVisible);
	private final BoolSetting arrayGradient = add(new BoolSetting("arrayGradient", false)).visibleWhen(arraylist::on);
	private final NumberSetting gradientPeriod = add(new NumberSetting("gradientPeriod", 3, 0.5, 20, 0.1, " s"))
			.visibleWhen(() -> arraylist.on() && arrayGradient.on());
	private final NumberSetting gradientAcceleration = add(new NumberSetting("gradientAcceleration", 2, 0, 10, 0.1))
			.visibleWhen(() -> arraylist.on() && arrayGradient.on());
	private final NumberSetting gradientDarkness = add(new NumberSetting("gradientDarkness", 25, 0, 100, 1, "%"))
			.visibleWhen(() -> arraylist.on() && arrayGradient.on());
	private final NumberSetting gradientBlur = add(new NumberSetting("gradientBlur", 1.5, 0, 2.5, 0.1))
			.visibleWhen(() -> arraylist.on() && arrayGradient.on());
	private final NumberSetting gradientBand = add(new NumberSetting("gradientBand", 70, 20, 150, 5, "%"))
			.visibleWhen(() -> arraylist.on() && arrayGradient.on());
	private final ModeSetting musicAnimation = add(new ModeSetting("musicAnimation", "music_elastic",
			"music_slide", "music_elastic", "music_fade", "music_droplet", "music_split", "music_flip", "music_wave", "music_orbit")).visibleWhen(island::on);
	private final NumberSetting musicAnimationTime = add(new NumberSetting("musicAnimationTime", 100, 50, 200, 5, "%"))
			.visibleWhen(island::on);
	private final BoolSetting notifications = add(new BoolSetting("notifications", true));
	private final BoolSetting toggleSound=add(new BoolSetting("toggleSound",true));
	private final NumberSetting toggleSoundVolume=add(new NumberSetting("toggleSoundVolume",25,0,100,1,"%")).visibleWhen(toggleSound::on);
	private final BoolSetting toggleIsland=add(new BoolSetting("toggleIsland",true)).visibleWhen(()->notifications.on()&&island.on());
	private final BoolSetting toggleLiquid = add(new BoolSetting("toggleLiquid", false)).visibleWhen(notifications::on);
	private final NumberSetting toggleAnimationTime = add(new NumberSetting("toggleAnimationTime", 130, 50, 250, 5, "%"))
			.visibleWhen(() -> notifications.on() && toggleLiquid.on());
	private final BoolSetting islandEventAnimations = add(new BoolSetting("islandEventAnimations", true))
			.visibleWhen(() -> island.on() && notifications.on());
	private final ModeSetting islandEventStyle = add(new ModeSetting("islandEventStyle", "island_original",
			"island_original", "island_auto", "island_elastic", "island_ripple", "island_flip", "island_liquid", "island_focus", "island_slide",
			"island_split", "island_shutter", "island_orbit", "island_wave", "island_scan", "island_bloom"))
			.visibleWhen(this::eventSettingsVisible);
	private final NumberSetting islandEventTime = add(new NumberSetting("islandEventTime", 100, 50, 200, 5, "%"))
			.visibleWhen(this::eventSettingsVisible);
	private final NumberSetting islandEventHold = add(new NumberSetting("islandEventHold", 1800, 600, 5000, 100, "ms"))
			.visibleWhen(this::eventSettingsVisible);
	private final NumberSetting islandEventIntensity = add(new NumberSetting("islandEventIntensity", 100, 0, 150, 5, "%"))
			.visibleWhen(this::eventSettingsVisible);
	private final BoolSetting islandDeathEvents = add(new BoolSetting("islandDeathEvents", true)).visibleWhen(this::eventSettingsVisible);
	private final BoolSetting islandRespawnEvents = add(new BoolSetting("islandRespawnEvents", true)).visibleWhen(this::eventSettingsVisible);
	private final BoolSetting islandDimensionEvents = add(new BoolSetting("islandDimensionEvents", true)).visibleWhen(this::eventSettingsVisible);
	private final BoolSetting islandTotemEvents = add(new BoolSetting("islandTotemEvents", true)).visibleWhen(this::eventSettingsVisible);
	private final BoolSetting islandLowHealthEvents = add(new BoolSetting("islandLowHealthEvents", true)).visibleWhen(this::eventSettingsVisible);
	private final NumberSetting islandLowHealthThreshold = add(new NumberSetting("islandLowHealthThreshold", 6, 1, 20, .5, "HP"))
			.visibleWhen(() -> eventSettingsVisible() && islandLowHealthEvents.on());
	private final BoolSetting islandTargetDownEvents = add(new BoolSetting("islandTargetDownEvents", true)).visibleWhen(this::eventSettingsVisible);
	private final BoolSetting glass = add(new BoolSetting("glass", true));
	private final BoolSetting chatGlass = add(new BoolSetting("chatGlass", true));
	private final NumberSetting chatAnimationTime = add(new NumberSetting("chatAnimationTime", 100, 50, 200, 5, "%"))
			.visibleWhen(chatGlass::on);
	private final BoolSetting inventoryGlass = add(new BoolSetting("inventoryGlass", true));
	private final DynamicIsland dynamicIsland = new DynamicIsland();
	private final ModuleArrayList moduleArrayList = new ModuleArrayList();
	private long lastFrame = System.nanoTime();
	private double gradientSeconds;
	private long gradientStartedAt;
	private long paletteStartedAt;

	public Hud() {
		super("hud", Category.VISUAL);
	}

	public boolean glass() {
		return glass.on();
	}
	public boolean worldScreenEnabled(){return isEnabled()&&worldScreen.on();}
	public void worldScreen(boolean value){worldScreen.set(value);com.cheatprogram.core.Config.markDirty();}
	public float worldScreenWidth(){return worldScreenWidth.floatVal();}
	public void worldScreenWidth(double value){worldScreenWidth.set(Math.max(1,Math.min(20,value)));com.cheatprogram.core.Config.markDirty();}
	public float worldScreenDistance(){return worldScreenDistance.floatVal();}
	public float worldScreenYaw(){return worldScreenYaw.floatVal();}
	public void worldScreenYaw(float value){worldScreenYaw.set((double)net.minecraft.util.Mth.wrapDegrees(value));com.cheatprogram.core.Config.markDirty();}
	public float worldScreenPitch(){return worldScreenPitch.floatVal();}
	public void worldScreenPitch(float value){worldScreenPitch.set((double)Math.max(-80,Math.min(80,value)));com.cheatprogram.core.Config.markDirty();}
	public float worldScreenRoll(){return worldScreenRoll.floatVal();}
	public float worldScreenOpacity(){return (float)worldScreenOpacity.fraction();}
	public int worldScreenFrameRate(){return worldScreenFrameRate.intVal();}
	public float worldScreenBrightness(){return (float)worldScreenBrightness.fraction();}
	public boolean worldScreenThroughWalls(){return worldScreenThroughWalls.on();}
	public boolean worldScreenDoublePress(){return true;}
	public long worldScreenDoubleMillis(){return (long)worldScreenDoubleMillis.val();}
	public boolean toggleSoundEnabled(){return toggleSound.on();}
	public float toggleSoundVolume(){return (float)toggleSoundVolume.fraction();}
	public boolean titleIntroEnabled(){return isEnabled()&&titleIntro.on();}
	public String titleIntroPalette(){return titleIntroPalette.get();}
	public float titleIntroWriteSeconds(){return titleIntroWriteTime.floatVal()/1000;}
	public float titleIntroHoldSeconds(){return titleIntroHoldTime.floatVal()/1000;}
	public float titleIntroFadeSeconds(){return titleIntroFadeTime.floatVal()/1000;}
	public float titleIntroSize(){return (float)titleIntroSize.fraction();}
	public float titleIntroY(){return (float)titleIntroY.fraction();}
	public float titleIntroThickness(){return (float)titleIntroThickness.fraction();}
	public float titleIntroGlow(){return (float)titleIntroGlow.fraction();}
	public boolean arraylistRight() { return arraySide.is("array_right"); }
	private boolean arrayColorsVisible() { return arraylist.on() && !arrayPalette.is("palette_white"); }

	public boolean glassChat() { return isEnabled() && chatGlass.on(); }
	public boolean glassInventory() { return isEnabled() && inventoryGlass.on(); }
	public float interfaceAnimationScale() { return (float) chatAnimationTime.fraction(); }

	public String musicAnimation() {
		return musicAnimation.get();
	}

	public float musicAnimationScale() {
		return (float) musicAnimationTime.fraction();
	}
	public boolean musicPreviewEnabled() { return isEnabled() && island.on(); }
	private boolean eventSettingsVisible() { return island.on() && notifications.on() && islandEventAnimations.on(); }
	public boolean islandEventsEnabled() { return isEnabled() && eventSettingsVisible(); }
	public String islandEventStyle() { return islandEventStyle.get(); }
	public float islandEventTimeScale() { return (float) islandEventTime.fraction(); }
	public long islandEventHoldMillis() { return (long) islandEventHold.val(); }
	public float islandEventIntensity() { return (float) islandEventIntensity.fraction(); }
	public boolean islandDeathEventsEnabled() { return islandEventsEnabled() && islandDeathEvents.on(); }
	public boolean islandRespawnEventsEnabled() { return islandEventsEnabled() && islandRespawnEvents.on(); }
	public boolean islandDimensionEventsEnabled() { return islandEventsEnabled() && islandDimensionEvents.on(); }
	public boolean islandTotemEventsEnabled() { return islandEventsEnabled() && islandTotemEvents.on(); }
	public boolean islandLowHealthEventsEnabled() { return islandEventsEnabled() && islandLowHealthEvents.on(); }
	public float islandLowHealthThreshold() { return islandLowHealthThreshold.floatVal(); }
	public boolean islandTargetDownEventsEnabled() { return islandEventsEnabled() && islandTargetDownEvents.on(); }

	@Override
	public void onRender2D(HudContext ctx) {
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		if (!arraylist.on()) paletteStartedAt = 0;
		if (arraylist.on() && arrayGradient.on()) {
			if (gradientStartedAt == 0) gradientStartedAt = now;
			gradientSeconds = (now - gradientStartedAt) / 1_000_000_000.0;
		} else {
			gradientSeconds = 0;
			gradientStartedAt = 0;
		}
		boolean useLiquidToggles = notifications.on() && toggleLiquid.on();
		if (island.on()) dynamicIsland.render(ctx, dt, notifications.on(), toggleIsland.on());
		if (arraylist.on()) arrayList(ctx, dt);
		if (notifications.on() && !island.on()) notifications(ctx, dt, !useLiquidToggles);
		if (useLiquidToggles) LiquidToggleToasts.render(ctx, dt, Notifications.entries().stream()
				.filter(entry -> entry.kind == Notifications.Kind.MODULE).toList(), (float) toggleAnimationTime.fraction());
		else LiquidToggleToasts.clear();
	}

	private void arrayList(HudContext ctx, float dt) {
		if (arrayColorsVisible() && arrayColorAnimation.on()) {
			if (paletteStartedAt == 0) paletteStartedAt = System.nanoTime();
		} else paletteStartedAt = 0;
		double colorSeconds = paletteStartedAt == 0 ? 0 : (System.nanoTime() - paletteStartedAt) / 1_000_000_000.0;
		PaletteText.Style palette = new PaletteText.Style(arrayPalette.get(), PaletteText.flowingPhase(colorSeconds,
				arrayColorPeriod.val(), arrayColorStrength.fraction(), arrayColorDirection.is("palette_flow_right"), arrayColorPhase.fraction()));
		moduleArrayList.render(ctx, dt, this, new ModuleArrayList.Style(arraylistRight(), (float) arrayScale.fraction(),
				arrayRail.on(), arrayGradient.on(), PaletteText.flowingPhase(gradientSeconds, gradientPeriod.val(),
						gradientAcceleration.val() / (1 + gradientAcceleration.val()), false, 0),
				(float) gradientBand.fraction(), (float) gradientDarkness.fraction(), gradientBlur.floatVal(), palette));
	}

	@Override public void onDisable() { paletteStartedAt = 0; }

	private void notifications(HudContext ctx, float dt, boolean includeModuleToggles) {
		Canvas c = ctx.c;
		List<Notifications.Entry> list = Notifications.entries().stream()
				.filter(entry -> includeModuleToggles || entry.kind != Notifications.Kind.MODULE).toList();
		long now = System.currentTimeMillis();
		float h = 32;
		float baseY = ctx.height - h - 46;
		int index = 0;
		for (int i = list.size() - 1; i >= 0; i--, index++) {
			Notifications.Entry e = list.get(i);
			long age = now - e.created;
			if (e.slot < 0) e.slot = index;
			e.slot += (index - e.slot) * (1f - (float) Math.exp(-dt * 14f));
			float in = Anim.easeOutCubic(age / 300f);
			float out = age > Notifications.DURATION_MS ? 1f - MotionCurve.smootherStep((age - Notifications.DURATION_MS) / 320f) : 1f;
			if (out <= 0.01f) continue;
			float tw = Math.max(c.textWidth(Fonts.Face.MEDIUM, e.title, 10f), c.textWidth(Fonts.Face.REGULAR, e.detail, 8.5f));
			float w = Math.max(132, tw + 52);
			float x = ctx.width - 8 - w + (1f - in) * (w + 18);
			float y = baseY - e.slot * (h + 6);
			R2D.pushAlpha(out);
			ctx.panel(x, y, w, h, h / 2f);
			float ix = x + 17;
			float iy = y + h / 2f;
			if (e.positive) {
				c.shadow(ix - 9, iy - 9, 18, 18, 9, 5, Anim.white(0.35f));
				c.circle(ix, iy, 9, Anim.white(0.95f));
			} else {
				c.circle(ix, iy, 9, Anim.white(0.12f));
			}
			c.icon(Icon.POWER, ix, iy, 10.5f, e.positive ? 0xFF0B0B0D : Theme.TEXT_2);
			c.textC(Fonts.Face.MEDIUM, e.title, x + 33, y + 12, 10f, Theme.TEXT);
			c.textC(Fonts.Face.REGULAR, e.detail, x + 33, y + 23, 8.5f, Theme.TEXT_3);
			float progress = 1f - MotionCurve.smootherStep(age / (float) Notifications.DURATION_MS);
			c.rrect(x + 10, y + h - 3.5f, (w - 20) * progress, 1.5f, 0.75f, Anim.white(0.55f));
			R2D.popAlpha();
		}
	}
}
