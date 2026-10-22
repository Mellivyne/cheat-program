package com.cheatprogram.hud;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.HudEditorScreen;
import com.cheatprogram.gui.MusicPlayerScreen;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.R2D;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;

/** Draws module HUD elements on top of the vanilla HUD. */
public final class HudRenderer {
	private static final Canvas CANVAS = new Canvas();
	private static GuiGraphics deferredGraphics;
	private static DeltaTracker deferredDelta;
	private static Screen deferredScreen;

	private HudRenderer() {
	}

	public static float hudScale() {
		Minecraft mc = Minecraft.getInstance();
		return Math.max(0.75f, Math.min(3f, mc.getWindow().getHeight() / 640f));
	}

	/** A deferred HUD belongs only to this frame's screen/graphics pair. */
	public static void beginFrame() {
		deferredGraphics = null;
		deferredDelta = null;
		deferredScreen = null;
	}

	public static void render(GuiGraphics graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		// Music's full-screen backdrop must capture the scene before the island and other HUD
		// text. Drawing them now feeds their glyphs into the glass refraction at the panel rim.
		if (mc.screen instanceof MusicPlayerScreen && mc.getOverlay() == null) {
			deferredGraphics = graphics;
			deferredDelta = delta;
			deferredScreen = mc.screen;
			return;
		}
		renderNow(graphics, delta);
	}

	/** Draw exactly once, after the music page has completed every backdrop capture. */
	public static void afterScreen(GuiGraphics graphics, Screen screen) {
		DeltaTracker delta = deferredDelta;
		boolean pending = delta != null && graphics == deferredGraphics && screen == deferredScreen
				&& Minecraft.getInstance().screen == screen;
		beginFrame();
		if (pending) renderNow(graphics, delta);
	}

	private static void renderNow(GuiGraphics graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		boolean editing = mc.screen instanceof HudEditorScreen;
		if ((mc.options.hideGui && !editing) || mc.player == null) return;
		try {
			R2D.begin(graphics);
			float s = hudScale();
			CANVAS.set(s, 0, 0);
			HudContext ctx = new HudContext(CANVAS, R2D.width() / s, R2D.height() / s, delta.getGameTimeDeltaPartialTick(false), editing);
			AiVoiceHud.beginFrame(ctx);
			ModuleManager.onRender2D(ctx);
			AiVoiceHud.render(ctx);
			TitleIntro.render(ctx);
		} catch (Throwable t) {
			CheatProgram.LOGGER.error("HUD rendering failed", t);
		} finally {
			R2D.end();
		}
	}
}
