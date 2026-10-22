package com.cheatprogram.gui;

import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.cheatprogram.module.visual.ControlPanel;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.ControlPanelBackground;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.CapeImages;
import com.cheatprogram.util.MediaCapeFiles;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Selects source-media UVs without rewriting or reducing the original image or video. */
public final class PanelBackgroundEditorScreen extends GuiBase {
	private static final float W = 790, H = 520;
	private static final float VIEW_X = 24, VIEW_Y = 138, VIEW_W = 444, VIEW_H = 286;
	private static final float PREVIEW_X = 490, PREVIEW_Y = 160, PREVIEW_W = 276, PREVIEW_H = PREVIEW_W * 452 / 720;
	private static final double PANEL_ASPECT = 720d / 452;
	private static final ExecutorService FILES = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Cheat Program panel background editor"); thread.setDaemon(true); return thread;
	});
	private final Screen parent;
	private final ControlPanel panel;
	private final TextField path = new TextField(2048);
	private final ResourceLocation sourceTexture = ResourceLocation.fromNamespaceAndPath("cheatprogram", "panel_editor/source_" + Long.toUnsignedString(System.nanoTime()));
	private ControlPanel.Crop crop = ControlPanel.Crop.FULL;
	private Path sourceFile;
	private int sourceWidth, sourceHeight;
	private boolean registered, busy, closed, started, animatedSource;
	private double viewZoom = 1, panX, panY;
	private long request;
	private String status = "";
	private record Decoded(Path file, NativeImage pixels, int width, int height, boolean animated) { }
	private record View(double x, double y, double width, double height) {
		double u(double x) { return clamp((x - this.x) / width, 0, 1); }
		double v(double y) { return clamp((y - this.y) / height, 0, 1); }
	}

	public PanelBackgroundEditorScreen(Screen parent, ControlPanel panel) {
		super(Lang.t("ui.panel.editorTitle")); this.parent = parent; this.panel = panel;
		path.setText(panel.imagePath()); path.onEnter = () -> load(path.text);
	}
	@Override protected void init() {
		super.init();
		if (!started) { started = true; if (!path.text.isBlank()) load(path.text); }
	}
	@Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		beginFrame();
		if (!preparePanelBackground(graphics, partialTick)) { endFrame(); return; }
		R2D.begin(graphics);
		try {
			float fit = Math.min(R2D.width() / (W + 40), R2D.height() / (H + 40));
			float preferred = Math.max(.55f, Math.min(R2D.width() / 1060f, R2D.height() / 760f) * (float) ClientSettings.INSTANCE.uiScale.fraction());
			float scale = Math.max(.28f, Math.min(fit, preferred));
			c.set(scale, (R2D.width() - W * scale) / 2, (R2D.height() - H * scale) / 2); updateMouse();
			if (!drawCustomPanelSurface(graphics, W, H, 22)) {
				c.rect(-c.originX / scale, -c.originY / scale, R2D.width() / scale, R2D.height() / scale, Anim.black(.16f));
				Backdrop.capture(Theme.frost()); R2D.applyState();
				c.shadow(0, 6, W, H, 22, 28, Anim.black(.22f));
				c.lens(0, 0, W, H, 22, Theme.lift(.08f), Theme.sheetGlass(c.scale));
			}
			c.textC(BOLD, Lang.t("ui.panel.editorTitle"), 24, 28, 16, Theme.TEXT);
			c.textC(REG, c.ellipsize(REG, Lang.t("ui.panel.cropHint"), 9, W - 48), 24, 48, 9, Theme.TEXT_3);
			textField(path, Lang.t("ui.panel.pathHint"), Icon.FOLDER, 24, 65, 530, 28);
			action("panel.browse", "ui.panel.browse", 562, 65, 94, 28, false, this::choose);
			action("panel.load", "ui.panel.load", 664, 65, 102, 28, true, () -> load(path.text));
			c.textC(MED, Lang.t("ui.panel.selection"), VIEW_X, 119, 10.5f, Theme.TEXT_2);
			cropView(); preview(graphics);
			action("panel.reset", "ui.panel.resetCrop", VIEW_X, 436, 218, 28, false, () -> { crop = ControlPanel.Crop.FULL; resetView(); });
			action("panel.fit", "ui.panel.fitPanel", VIEW_X + 226, 436, 218, 28, false, this::fitPanel);
			String detail = status;
			if (detail.isBlank() && registered) detail = animatedSource ? Lang.t("ui.panel.mediaReady") : Lang.t("ui.panel.mediaHint");
			c.textC(REG, c.ellipsize(REG, detail, 9, 476), 24, 481, 9, Theme.TEXT_3);
			button("panel.cancel", Lang.t("ui.panel.cancel"), Icon.BACK, 520, 474, 112, 28, false, this::onClose);
			action("panel.apply", "ui.panel.apply", 640, 474, 126, 28, true, this::apply);
		} finally { R2D.end(); endFrame(); }
	}
	private void action(String key, String label, float x, float y, float width, float height, boolean primary, Runnable action) {
		R2D.pushAlpha(busy ? .45f : 1);
		try { button(key, Lang.t(label), null, x, y, width, height, primary, () -> { if (!busy) action.run(); }); }
		finally { R2D.popAlpha(); }
	}
	private View view() {
		double fit = Math.min((VIEW_W - 24) / sourceWidth, (VIEW_H - 24) / sourceHeight) * viewZoom;
		double width = sourceWidth * fit, height = sourceHeight * fit;
		return new View(VIEW_X + VIEW_W / 2d - width / 2 + panX, VIEW_Y + VIEW_H / 2d - height / 2 + panY, width, height);
	}
	private void cropView() {
		c.rrect(VIEW_X, VIEW_Y, VIEW_W, VIEW_H, 12, Anim.black(.12f));
		if (!registered) { c.textMid(REG, Lang.t("ui.panel.empty"), VIEW_X + VIEW_W / 2, VIEW_Y + VIEW_H / 2, 11, Theme.TEXT_3); return; }
		View view = view();
		float left = (float) (view.x + crop.x() * view.width), top = (float) (view.y + crop.y() * view.height);
		float width = (float) (crop.width() * view.width), height = (float) (crop.height() * view.height);
		c.clip(VIEW_X, VIEW_Y, VIEW_W, VIEW_H);
		try {
			drawTexture((float) view.x, (float) view.y, (float) view.width, (float) view.height);
			// The selected source region stays bright; the rest of the source is shaded.
			float x0 = Math.max(VIEW_X, left), x1 = Math.min(VIEW_X + VIEW_W, left + width);
			float y0 = Math.max(VIEW_Y, top), y1 = Math.min(VIEW_Y + VIEW_H, top + height);
			if (x1 > x0 && y1 > y0) {
				c.rect(VIEW_X, VIEW_Y, x0 - VIEW_X, VIEW_H, Anim.black(.48f));
				c.rect(x1, VIEW_Y, VIEW_X + VIEW_W - x1, VIEW_H, Anim.black(.48f));
				c.rect(x0, VIEW_Y, x1 - x0, y0 - VIEW_Y, Anim.black(.48f));
				c.rect(x0, y1, x1 - x0, VIEW_Y + VIEW_H - y1, Anim.black(.48f));
			} else c.rect(VIEW_X, VIEW_Y, VIEW_W, VIEW_H, Anim.black(.48f));
			c.rect(left, top, width, 1, Theme.TEXT); c.rect(left, top + height - 1, width, 1, Theme.TEXT);
			c.rect(left, top, 1, height, Theme.TEXT); c.rect(left + width - 1, top, 1, height, Theme.TEXT);
			for (int axis = 1; axis < 3; axis++) {
				c.rect(left + width * axis / 3, top, .5f, height, Anim.white(.25f));
				c.rect(left, top + height * axis / 3, width, .5f, Anim.white(.25f));
			}
			for (int gy = 0; gy < 3; gy++) for (int gx = 0; gx < 3; gx++) {
				if (gx == 1 && gy == 1) continue;
				float hx = left + width * gx / 2, hy = top + height * gy / 2;
				c.rrect(hx - 2.5f, hy - 2.5f, 5, 5, 1, Theme.TEXT);
			}
		} finally { c.unclip(); }
		hit(VIEW_X, VIEW_Y, VIEW_W, VIEW_H).click(button -> {
			if (busy) return; focused = null;
			if (button == 1) {
				double oldX = panX, oldY = panY; float initialX = mx, initialY = my;
				drag = (x, y) -> { panX = oldX + x - initialX; panY = oldY + y - initialY; clampPan(); }; return;
			}
			if (button == 0) beginCropDrag();
		}).scroll(amount -> {
			if (busy) return;
			View before = view(); double u = (mx - before.x) / before.width, v = (my - before.y) / before.height;
			viewZoom = clamp(viewZoom * Math.pow(1.12, amount), 1, 16);
			View after = view(); panX += mx - (after.x + u * after.width); panY += my - (after.y + v * after.height); clampPan();
		});
	}
	private void beginCropDrag() {
		View view = view(); ControlPanel.Crop original = crop;
		double left = view.x + crop.x() * view.width, right = left + crop.width() * view.width;
		double top = view.y + crop.y() * view.height, bottom = top + crop.height() * view.height;
		boolean nearX = mx >= left - 6 && mx <= right + 6, nearY = my >= top - 6 && my <= bottom + 6;
		boolean leftEdge = nearY && Math.abs(mx - left) <= 6, rightEdge = nearY && Math.abs(mx - right) <= 6;
		boolean topEdge = nearX && Math.abs(my - top) <= 6, bottomEdge = nearX && Math.abs(my - bottom) <= 6;
		boolean shift = Screen.hasShiftDown();
		if (!shift && (leftEdge || rightEdge || topEdge || bottomEdge)) {
			drag = (x, y) -> {
				View current = view(); double minW = Math.max(1d / sourceWidth, 1d / 65536), minH = Math.max(1d / sourceHeight, 1d / 65536);
				double x0 = original.x(), y0 = original.y(), x1 = x0 + original.width(), y1 = y0 + original.height();
				if (leftEdge) x0 = Math.min(x1 - minW, current.u(x));
				if (rightEdge) x1 = Math.max(x0 + minW, current.u(x));
				if (topEdge) y0 = Math.min(y1 - minH, current.v(y));
				if (bottomEdge) y1 = Math.max(y0 + minH, current.v(y));
				crop = new ControlPanel.Crop(x0, y0, x1 - x0, y1 - y0);
			}; return;
		}
		boolean inside = mx >= left && mx <= right && my >= top && my <= bottom;
		boolean full = crop.width() > .99999 && crop.height() > .99999;
		if (!shift && inside && !full) {
			float initialX = mx, initialY = my;
			drag = (x, y) -> crop = new ControlPanel.Crop(original.x() + (x - initialX) / view.width,
					original.y() + (y - initialY) / view.height, original.width(), original.height());
			return;
		}
		double startX = view.u(mx), startY = view.v(my);
		drag = (x, y) -> {
			double endX = view.u(x), endY = view.v(y);
			crop = new ControlPanel.Crop(Math.min(startX, endX), Math.min(startY, endY),
					Math.max(1d / sourceWidth, Math.abs(endX - startX)), Math.max(1d / sourceHeight, Math.abs(endY - startY)));
		};
	}
	private void clampPan() {
		View view = view();
		panX = clamp(panX, -view.width / 2 - VIEW_W / 2 + 24, view.width / 2 + VIEW_W / 2 - 24);
		panY = clamp(panY, -view.height / 2 - VIEW_H / 2 + 24, view.height / 2 + VIEW_H / 2 - 24);
	}
	private void resetView() { viewZoom = 1; panX = panY = 0; }
	private void fitPanel() {
		if (!registered) return;
		double aspect = (double) sourceWidth / sourceHeight, width = crop.width(), height = crop.height();
		if (aspect * width / height > PANEL_ASPECT) width = height * PANEL_ASPECT / aspect;
		else height = width * aspect / PANEL_ASPECT;
		crop = new ControlPanel.Crop(crop.x() + (crop.width() - width) / 2, crop.y() + (crop.height() - height) / 2, width, height);
	}
	private void preview(GuiGraphics graphics) {
		c.textC(MED, Lang.t("ui.panel.preview"), PREVIEW_X, 119, 10.5f, Theme.TEXT_2);
		c.lens(PREVIEW_X - 4, 138, PREVIEW_W + 8, 286, 14, Theme.lift(.035f), Theme.lensGlass(c.scale));
		if (registered) {
			ControlPanelBackground.drawPreview(graphics, c, panel, sourceTexture, sourceWidth, sourceHeight, crop,
					PREVIEW_X, PREVIEW_Y, PREVIEW_W, PREVIEW_H, 12);
			R2D.applyState();
			c.textC(BOLD, "Cheat Program", PREVIEW_X + 14, PREVIEW_Y + 18, 11, Theme.TEXT);
			for (int i = 0; i < 3; i++) {
				float y = PREVIEW_Y + 48 + i * 32;
				c.lens(PREVIEW_X + 92, y, 170, 25, 12, Theme.lift(.05f), Theme.lensGlass(c.scale));
				c.circle(PREVIEW_X + 104, y + 12, 2, Theme.TEXT_2);
				c.rrect(PREVIEW_X + 116, y + 9, 70 - i * 9, 5, 2.5f, Theme.TEXT_2);
				c.rrect(PREVIEW_X + 235, y + 7, 18, 11, 5.5f, Theme.TEXT);
			}
			c.textC(REG, Lang.t("ui.panel.cropDimensions",Math.max(1,Math.round(crop.width()*sourceWidth)),
					Math.max(1,Math.round(crop.height()*sourceHeight))),PREVIEW_X+10,361,9,Theme.TEXT_2);
			c.textC(REG, sourceWidth + " × " + sourceHeight, PREVIEW_X + 10, 380, 8.5f, Theme.TEXT_3);
		} else c.textMid(REG, Lang.t("ui.panel.empty"), PREVIEW_X + PREVIEW_W / 2, 255, 10, Theme.TEXT_3);
	}
	private void drawTexture(float x, float y, float width, float height) {
		R2D.texture(sourceTexture, c.px(x), c.py(y), width * c.scale, height * c.scale, 0, 0, 1, 1, 0xFFFFFFFF);
	}
	private void choose() {
		busy = true; status = Lang.t("ui.panel.loading"); long token = ++request;
		String suggested = path.text.isBlank() ? null : path.text;
		FILES.execute(() -> {
			String picked = null, error = null;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				String[] extensions = { "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif", "*.apng", "*.webp", "*.mp4", "*.webm", "*.mov", "*.mkv", "*.avi", "*.m4v" };
				var filters = stack.mallocPointer(extensions.length); for (String extension : extensions) filters.put(stack.UTF8(extension)); filters.flip();
				picked = TinyFileDialogs.tinyfd_openFileDialog(Lang.t("ui.panel.editorTitle"), suggested, filters, "Images / GIF / APNG / WebP / Video", false);
			} catch (Throwable unavailable) { error = "ui.panel.pickerUnavailable"; }
			String result = picked, failure = error;
			mc.execute(() -> {
				if (closed || token != request) return;
				busy = false;
				if (failure != null) status = Lang.t(failure);
				else if (result != null) { path.setText(result); load(result); }
				else status = "";
			});
		});
	}
	private void load(String value) {
		if (busy || closed || value.isBlank()) return;
		String input = value.strip();
		if (input.length() > 1 && input.startsWith("\"") && input.endsWith("\"")) input = input.substring(1, input.length() - 1);
		String filename = input; long token = ++request; busy = true; status = Lang.t("ui.panel.loading");
		FILES.execute(() -> {
			Decoded decoded = null; String error = null;
			try { decoded = decode(Path.of(filename)); }
			catch (MediaCapeFiles.DecoderUnavailable missing) { error = "ui.panel.decoderUnavailable"; }
			catch (OutOfMemoryError tooLarge) { error = "ui.panel.tooLarge"; }
			catch (Exception unreadable) { error = "ui.panel.fileError"; }
			Decoded result = decoded; String failure = error;
			mc.execute(() -> {
				if (closed || token != request) { if (result != null) result.pixels.close(); return; }
				busy = false;
				if (failure != null || result == null) { status = Lang.t(failure == null ? "ui.panel.fileError" : failure); return; }
				try {
					DynamicTexture texture = new DynamicTexture(result.pixels); texture.setFilter(true, false);
					mc.getTextureManager().register(sourceTexture, texture); registered = true;
					sourceFile = result.file; sourceWidth = result.width; sourceHeight = result.height; animatedSource = result.animated;
					path.setText(sourceFile.toString()); resetView();
					crop = sameFile(sourceFile, panel.imagePath()) ? panel.crop() : ControlPanel.Crop.FULL;
					status = "";
				} catch (Throwable uploadFailed) { result.pixels.close(); status = Lang.t("ui.panel.fileError"); }
			});
		});
	}
	private static Decoded decode(Path requested) throws IOException {
		Path file = requested.toAbsolutePath().normalize();
		if (!Files.isRegularFile(file) || Files.size(file) < 1) throw new IOException("Unreadable media");
		String extension = MediaCapeFiles.extension(file);
		boolean animated = List.of("gif", "apng", "webp", "mp4", "webm", "mov", "mkv", "avi", "m4v").contains(extension)
				|| extension.equals("png") && animatedPng(file);
		if (Files.size(file) > (animated ? MediaCapeFiles.MAX_MEDIA_BYTES : CapeImages.MAX_SAVED_BYTES)) throw new IOException("Media too large");
		BufferedImage image = null; NativeImage pixels = null;
		int width, height;
		try {
			if (animated) {
				MediaCapeFiles.Imported imported = MediaCapeFiles.importFile(file,2048); image = imported.firstFrame(); width = image.getWidth(); height = image.getHeight();
			} else {
				try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
					if (input == null) throw new IOException("Image stream unavailable");
					Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
					if (!readers.hasNext()) throw new IOException("Image decoder unavailable");
					ImageReader reader = readers.next();
					try {
						reader.setInput(input, true, true); width = reader.getWidth(0); height = reader.getHeight(0);
						if (width < 1 || height < 1 || width > 65536 || height > 65536) throw new IOException("Image dimensions invalid");
						var parameters = reader.getDefaultReadParam(); int sample = Math.max(1, (Math.max(width, height) + 2047) / 2048);
						parameters.setSourceSubsampling(sample, sample, 0, 0); image = reader.read(0, parameters);
					} finally { reader.dispose(); }
				}
			}
			if (image == null) throw new IOException("Empty image");
			if (image.getWidth() > 2048 || image.getHeight() > 2048) {
				double fit = Math.min(2048d / image.getWidth(), 2048d / image.getHeight());
				BufferedImage smaller = new BufferedImage(Math.max(1, (int) Math.round(image.getWidth() * fit)), Math.max(1, (int) Math.round(image.getHeight() * fit)), BufferedImage.TYPE_INT_ARGB);
				Graphics2D graphics = smaller.createGraphics();
				try { graphics.setComposite(AlphaComposite.Src); graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); graphics.drawImage(image, 0, 0, smaller.getWidth(), smaller.getHeight(), null); }
				finally { graphics.dispose(); }
				image.flush(); image = smaller;
			}
			pixels = new NativeImage(image.getWidth(), image.getHeight(), false);
			for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) pixels.setPixel(x, y, image.getRGB(x, y));
			return new Decoded(file, pixels, width, height, animated);
		} catch (Throwable failure) {
			if (pixels != null) pixels.close(); throw failure;
		} finally { if (image != null) image.flush(); }
	}
	private static boolean animatedPng(Path file) throws IOException {
		try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
			if (input.readLong() != 0x89504e470d0a1a0aL) return false;
			long traversed = 8;
			while (traversed < 1024 * 1024) {
				int length = input.readInt(), type = input.readInt(); traversed += 12L + Math.max(0, length);
				if (length < 0 || length > 1024 * 1024) return false;
				if (type == 0x6163544c) return true;
				if (type == 0x49444154 || type == 0x49454e44) return false;
				input.skipNBytes((long) length + 4);
			}
			return false;
		}
	}
	private static boolean sameFile(Path first, String second) {
		try { return !second.isBlank() && first.equals(Path.of(second).toAbsolutePath().normalize()); }
		catch (RuntimeException invalid) { return false; }
	}
	private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }
	private void apply() {
		if (busy || !registered || sourceFile == null || closed) return;
		if (!sameFile(sourceFile, path.text.strip())) { load(path.text); return; }
		panel.applyMedia(sourceFile.toString(), crop); Config.saveAll(); onClose();
	}
	@Override public boolean mouseClicked(double x, double y, int button) { dispatchClick(x, y, button); return true; }
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { dispatchScroll(x, y, vertical); return true; }
	@Override public boolean keyPressed(int key, int scan, int modifiers) {
		if (key == GLFW.GLFW_KEY_ESCAPE) { onClose(); return true; }
		if (focused == null && key == GLFW.GLFW_KEY_V && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) focused = path;
		if (focused != null) { focused.keyPressed(key, modifiers); return true; }
		return false;
	}
	@Override public boolean charTyped(char character, int modifiers) { return focused != null && focused.charTyped(character); }
	@Override public void onClose() { mc.setScreen(parent); }
	@Override public void removed() {
		closed = true; request++; drag = null;
		if (registered) { mc.getTextureManager().release(sourceTexture); registered = false; }
		super.removed();
	}
}
