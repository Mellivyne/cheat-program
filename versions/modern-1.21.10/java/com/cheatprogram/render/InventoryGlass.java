package com.cheatprogram.render;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.module.visual.Hud;
import com.cheatprogram.util.Anim;
import com.cheatprogram.compat.gl.GlStateManager;
import com.cheatprogram.compat.gl.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import com.cheatprogram.compat.gl.CompiledShaderProgram;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/** Paints native inventory geometry; item poses and all input coordinates stay with vanilla. */
public final class InventoryGlass {
	private static final Minecraft MC = Minecraft.getInstance();
	private static boolean captured;
	private InventoryGlass() { }

	public static boolean enabled(Screen screen) {
		Hud hud = ModuleManager.get(Hud.class);
		return hud != null && hud.glassInventory() && (screen instanceof InventoryScreen || screen instanceof CreativeModeInventoryScreen);
	}
	public static void beginBackground() { captured = false; }

	public static boolean background(GuiGraphics graphics, AbstractContainerScreen<?> screen, int x, int y, int width, int height) {
		if (!enabled(screen) || R2D.isActive()) return false;
		paint(graphics, canvas -> {
			canvas.shadow(x, y + 2, width, height, 9, 9, Anim.black(0.15f));
			canvas.lens(x, y, width, height, 9, Theme.lift(0.065f), Theme.sheetGlass(canvas.scale));
			if (screen instanceof InventoryScreen) {
				canvas.rrect(x + 25, y + 7, 51, 72, 5, Anim.black(0.035f));
				canvas.textMid(Fonts.Face.REGULAR, "→", x + 140, y + 36, 15, Theme.TEXT_2);
			} else if (screen instanceof CreativeModeInventoryScreen creative && !creative.isInventoryOpen()) {
				canvas.rrect(x + 175, y + 18, 12, 112, 4, Anim.black(0.05f));
			}
			for (var slot : screen.getMenu().slots) {
				if (!slot.isActive() || slot.x < 0 || slot.y < 0 || slot.x + 16 > width || slot.y + 16 > height) continue;
				canvas.rrect4(x + slot.x - 1, y + slot.y - 1, 18, 18, 3, 3, 3, 3,
						Anim.black(0.025f), Anim.black(0.055f), 0.5f * canvas.scale, Anim.white(0.14f), Anim.white(0.065f));
			}
		});
		return true;
	}

	public static boolean control(GuiGraphics graphics, Screen screen, int x, int y, int width, int height, boolean selected, boolean disabled) {
		if (!enabled(screen) || R2D.isActive()) return false;
		paint(graphics, canvas -> {
			canvas.lens(x, y, width, height, width <= 12 ? 4 : 6, Theme.lift(selected ? 0.13f : 0.055f), Theme.lensGlass(canvas.scale));
			if (disabled) canvas.rrect(x, y, width, height, 4, Anim.black(0.055f));
		});
		return true;
	}

	private static void paint(GuiGraphics graphics, java.util.function.Consumer<Canvas> draw) {
		RenderStateSnapshot saved = new RenderStateSnapshot();
		boolean clipped = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
		int[] box = new int[4];
		if (clipped) GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box);
		try {
			com.cheatprogram.compat.GuiBridge.flush(graphics);
			RenderSystem.disableScissor();
			graphics.pose().pushMatrix();
			try {
				R2D.begin(graphics);
				if (!captured) { Backdrop.capture(Theme.frost()); captured = true; R2D.applyState(); }
				if (clipped) RenderSystem.enableScissor(box[0], box[1], box[2], box[3]);
				Canvas canvas = new Canvas(); canvas.set((float) MC.getWindow().getGuiScale(), 0, 0);
				draw.accept(canvas);
			} finally {
				try { if (R2D.isActive()) R2D.end(); }
				finally { graphics.pose().popMatrix(); }
			}
		} finally {
			// Native item/PIP passes resume after glass; they need the original
			// model-view, projection and render-target overrides, not GUI values.
			saved.restore();
		}
	}
}
