package com.cheatprogram.render;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.module.visual.Hud;
import com.cheatprogram.util.Anim;
import com.cheatprogram.compat.gl.GlStateManager;
import com.cheatprogram.compat.gl.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

	public static boolean background(GuiGraphicsExtractor graphics, AbstractContainerScreen<?> screen, int x, int y, int width, int height) {
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

	public static boolean control(GuiGraphicsExtractor graphics, Screen screen, int x, int y, int width, int height, boolean selected, boolean disabled) {
		if (!enabled(screen) || R2D.isActive()) return false;
		paint(graphics, canvas -> {
			canvas.lens(x, y, width, height, width <= 12 ? 4 : 6, Theme.lift(selected ? 0.13f : 0.055f), Theme.lensGlass(canvas.scale));
			if (disabled) canvas.rrect(x, y, width, height, 4, Anim.black(0.055f));
		});
		return true;
	}

	private static void paint(GuiGraphicsExtractor graphics, java.util.function.Consumer<Canvas> draw) {
		com.cheatprogram.compat.GuiBridge.flush(graphics);
		NativeState state = new NativeState();
		// The caller may have a recipe-book or creative-tab scissor. Blurring must sample the full framebuffer.
		RenderSystem.disableScissor();
		graphics.pose().pushMatrix();
		try {
			R2D.begin(graphics);
			if (!captured) { Backdrop.capture(Theme.frost()); captured = true; R2D.applyState(); }
			if (state.scissor) RenderSystem.enableScissor(state.scissorBox[0], state.scissorBox[1], state.scissorBox[2], state.scissorBox[3]);
			Canvas canvas = new Canvas(); canvas.set((float) MC.getWindow().getGuiScale(), 0, 0);
			draw.accept(canvas);
		} finally {
			try { if (R2D.isActive()) R2D.end(); }
			finally { graphics.pose().popMatrix(); state.restore(); }
		}
	}

	/** Save actual native GL state after pending vanilla GUI vertices have been flushed. */
	private static final class NativeState {
		final boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
				cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST),
				depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
				srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
				readBuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), drawBuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING),
				activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		final int[] viewport = new int[4], scissorBox = new int[4];
		final int[] boundTextures = new int[2];
		final CompiledShaderProgram shader = RenderSystem.getShader();
		final float[] shaderColor = RenderSystem.getShaderColor().clone();
		final int texture0 = RenderSystem.getShaderTexture(0), texture1 = RenderSystem.getShaderTexture(1);
		NativeState() {
			GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport); GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
			for (int unit = 0; unit < boundTextures.length; unit++) {
				GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
				boundTextures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			}
			GlStateManager._activeTexture(activeTexture);
		}
		void restore() {
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readBuffer);
			GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawBuffer);
			RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
			RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
			if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
			if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
			if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
			RenderSystem.depthMask(depthMask);
			if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]); else RenderSystem.disableScissor();
			RenderSystem.setShaderTexture(0, texture0); RenderSystem.setShaderTexture(1, texture1);
			RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
			if (shader == null) RenderSystem.clearShader(); else RenderSystem.setShader(shader);
			GlStateManager._glUseProgram(program);
			for (int unit = 0; unit < boundTextures.length; unit++) {
				GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit); GlStateManager._bindTexture(boundTextures[unit]);
			}
			GlStateManager._activeTexture(activeTexture);
		}
	}
}
