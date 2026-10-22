package com.cheatprogram.hud;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.Theme;
import com.cheatprogram.module.visual.Hud;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Spring;
import com.cheatprogram.compat.gl.GlStateManager;
import com.cheatprogram.compat.gl.RenderSystem;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientLevel;
import com.cheatprogram.compat.gl.CompiledShaderProgram;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.ChatVisiblity;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.util.List;

/** Background-only chat chrome. Vanilla retains every glyph, hit coordinate, tooltip and input widget. */
public final class GlassChat {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final Spring WIDTH = new Spring(0), HEIGHT = new Spring(0), OPACITY = new Spring(0), INPUT_OPEN = new Spring(0);
	private static long lastFrameNanos;
	private static ClientLevel world;
	private static boolean inputReplaced;
	private static boolean failed;

	private GlassChat() { }

	/** Invoked before ChatComponent applies its own scale/translation and before any chat text is batched. */
	public static boolean render(GuiGraphicsExtractor graphics, ChatComponent chat, List<GuiMessage.Line> lines, int scroll, int guiTicks, boolean focused) {
		inputReplaced = false;
		Hud hud = ModuleManager.get(Hud.class);
		if (hud == null || !hud.glassChat() || MC.player == null || failed || R2D.isActive() || MC.gui.hud.isHidden() && !focused) {
			reset();
			return false;
		}
		if (world != MC.level) { reset(); world = MC.level; }
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 1f / 60 : Math.min(.05f, Math.max(0, (now - lastFrameNanos) / 1_000_000_000f));
		lastFrameNanos = now;
		float duration = Mth.clamp(hud.interfaceAnimationScale(), .5f, 2f);
		float scale = MC.options.chatScale().get().floatValue();
		if (scale <= .001f) return false;
		boolean visible = MC.options.chatVisibility().get() != ChatVisiblity.HIDDEN;
		float maxWidth = 0, fade = 0;
		int rows = 0;
		int lineHeight = (int) (9 * (MC.options.chatLineSpacing().get() + 1));
		double textOpacity = MC.options.chatOpacity().get() * .9 + .1;
		if (visible) {
			for (int row = 0; row < chat.getLinesPerPage() && row + scroll < lines.size(); row++) {
				GuiMessage.Line line = lines.get(row + scroll);
				int age = guiTicks - line.addedTime();
				if (age >= 200 && !focused) continue;
				float lifetime = focused ? 1 : Mth.clamp((1 - age / 200f) * 10, 0, 1);
				float alpha = (float) (lifetime * lifetime * textOpacity);
				if (alpha * 255 <= 3) continue;
				rows = row + 1;
				fade = Math.max(fade, alpha);
				int textWidth = MC.font.width(line.content());
				if (line.tag() != null && line.tag().icon() != null) textWidth += line.tag().icon().width + 6;
				maxWidth = Math.max(maxWidth, textWidth);
			}
		}
		boolean queue = visible && !lines.isEmpty() && MC.gui.chatListener().queueSize() > 0;
		float expandedWidth = ChatComponent.getWidth(MC.options.chatWidth().get()) + 12 * scale;
		float desiredWidth = focused && visible ? expandedWidth : Math.min(expandedWidth, Math.max(56 * scale, (maxWidth + 14) * scale));
		float desiredHeight = rows * lineHeight * scale + (rows > 0 ? 6 : 0);
		if (queue) { desiredHeight += 9 * scale + (rows == 0 ? 6 : 0); fade = Math.max(fade, (float) textOpacity * .65f); }
		if (focused && visible) {
			desiredHeight = Math.max(desiredHeight, Math.min(ChatComponent.getHeight((chat.isChatFocused() ? MC.options.chatHeightFocused() : MC.options.chatHeightUnfocused()).get()), 96) * scale + 6);
			fade = (float) textOpacity;
		}
		WIDTH.target = desiredHeight > 0 ? desiredWidth : WIDTH.target;
		HEIGHT.target = desiredHeight;
		OPACITY.target = desiredHeight > 0 ? fade : 0;
		INPUT_OPEN.target = focused ? 1 : 0;
		if (HEIGHT.value < 1 && desiredHeight > 0) {
			WIDTH.value = desiredWidth * .82f;
			HEIGHT.value = desiredHeight * .74f;
			OPACITY.value = fade * .75f;
		}
		WIDTH.update(dt / duration, 300, 22);
		HEIGHT.update(dt / duration, 300, 22);
		OPACITY.update(dt / duration, 260, 32);
		INPUT_OPEN.update(dt / duration, 300, 21);
		float panelWidth = Math.max(0, WIDTH.value), panelHeight = Math.max(0, HEIGHT.value);
		float panelAlpha = Mth.clamp(OPACITY.value, 0, 1);
		float open = Mth.clamp(INPUT_OPEN.value, 0, 1.12f);
		boolean drawMessages = panelHeight > .75f && panelWidth > 1 && panelAlpha > .005f;
		boolean drawInput = open > .012f;
		if (!drawMessages && !drawInput) return false;
		RenderState saved = new RenderState();
		if (saved.drawFramebuffer != com.cheatprogram.compat.gl.Framebuffers.framebuffer(MC.gameRenderer.mainRenderTarget())) return false;
		graphics.pose().pushMatrix();
		try {
			R2D.begin(graphics); // Flush all previous GUI batches before copying the world, never the new chat glyphs.
			RenderSystem.disableScissor();
			Backdrop.capture(Theme.frost());
			R2D.applyState();
			saved.viewportAndScissor();
			float pixels = (float) MC.getWindow().getGuiScale();
			if (drawMessages) {
				float bottom = Mth.floor((graphics.guiHeight() - 40) / scale) * scale + 3 + (queue ? 9 * scale : 0);
				float left = Math.max(0, 4 * scale - 6);
				panelWidth = Math.min(panelWidth, graphics.guiWidth() - left - 1);
				panelHeight = Math.min(panelHeight, bottom - 2);
				panel(left * pixels, (bottom - panelHeight) * pixels, panelWidth * pixels, panelHeight * pixels,
						Math.min(7 * pixels, panelHeight * pixels / 2), panelAlpha, pixels);
			}
			if (drawInput) {
				float height = 12 * open;
				float width = (graphics.guiWidth() - 4) * (.82f + .18f * Math.min(1.03f, open));
				panel(2 * pixels, (graphics.guiHeight() - 2 - height) * pixels, width * pixels, height * pixels,
						Math.min(6 * pixels, height * pixels / 2), Math.min(1, open), pixels);
				inputReplaced = focused;
			}
			return drawMessages;
		} catch (RuntimeException error) {
			// Preserve vanilla chat if a shader/backend is unavailable. Do not create render-loop log spam.
			failed = true;
			inputReplaced = false;
			return false;
		} finally {
			try { R2D.end(); } finally { graphics.pose().popMatrix(); saved.restore(); }
		}
	}

	public static boolean inputBackgroundReplaced() { return inputReplaced; }

	private static void panel(float x, float y, float w, float h, float radius, float opacity, float pixels) {
		R2D.pushAlpha(opacity);
		try {
			R2D.shadow(x, y + pixels, w, h, radius, 6 * pixels, 0x22000000);
			float lift = Theme.lift(.07f);
			int top = (Math.round(Math.min(1, lift * 1.2f) * 255) << 24) | 0xFFFFFF;
			int bottom = (Math.round(Math.min(1, lift * .8f) * 255) << 24) | 0xFFFFFF;
			R2D.glass(x, y, w, h, radius, top, bottom, Theme.chipGlass(pixels));
		} finally { R2D.popAlpha(); }
	}

	private static void reset() {
		for (Spring spring : new Spring[]{WIDTH, HEIGHT, OPACITY, INPUT_OPEN}) spring.value = spring.velocity = spring.target = 0;
		lastFrameNanos = 0;
	}

	/** R2D and Backdrop are normally top-level renderers; chat must return all their touched state. */
	private static final class RenderState {
		final int drawFramebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
		final int readFramebuffer = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
		final int[] viewport = new int[4], scissorBox = new int[4];
		final boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND);
		final boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
		final boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
		final int srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
		final int srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
		final int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
		final int[] textures = new int[2];
		final int shaderTexture0 = RenderSystem.getShaderTexture(0), shaderTexture1 = RenderSystem.getShaderTexture(1);
		final CompiledShaderProgram shader = RenderSystem.getShader();
		final float[] color = RenderSystem.getShaderColor().clone();

		RenderState() {
			GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
			GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissorBox);
			for (int unit = 0; unit < 2; unit++) {
				GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
				textures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
			}
			GlStateManager._activeTexture(activeTexture);
		}

		void viewportAndScissor() {
			RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
			if (scissor) RenderSystem.enableScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
			else RenderSystem.disableScissor();
		}

		void restore() {
			GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
			GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFramebuffer);
			viewportAndScissor();
			GlStateManager._blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
			if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
			if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
			if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
			RenderSystem.depthMask(depthMask);
			RenderSystem.setShaderColor(color[0], color[1], color[2], color[3]);
			if (shader == null) RenderSystem.clearShader(); else RenderSystem.setShader(shader);
			RenderSystem.setShaderTexture(0, shaderTexture0);
			RenderSystem.setShaderTexture(1, shaderTexture1);
			for (int unit = 0; unit < 2; unit++) {
				GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
				GlStateManager._bindTexture(textures[unit]);
			}
			GlStateManager._activeTexture(activeTexture);
			GlStateManager._glUseProgram(program);
		}
	}
}
