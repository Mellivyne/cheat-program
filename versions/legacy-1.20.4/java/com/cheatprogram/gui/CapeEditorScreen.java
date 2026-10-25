package com.cheatprogram.gui;

import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Lang;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.CapeImages;
import com.cheatprogram.util.CapeTextures;
import com.cheatprogram.util.MediaCapeFiles;
import com.cheatprogram.util.MediaCapeRecipe;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A local image editor. Native choosing/decoding never blocks the game's render loop. */
public final class CapeEditorScreen extends GuiBase {
	private static final float W = 740, H = 520, VIEW_X = 24, VIEW_Y = 144, VIEW_W = 432, VIEW_H = 280;
	private static final float CROP_W = 150, CROP_H = 240, CX = VIEW_X + VIEW_W / 2, CY = VIEW_Y + VIEW_H / 2;
	private static final ExecutorService FILES = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Cheat Program cape image chooser"); thread.setDaemon(true); return thread;
	});
	private final Screen parent;
	private final TextField path = new TextField(4096);
	private final ResourceLocation sourceTexture = new ResourceLocation("cheatprogram", "cape_editor/source_" + Long.toUnsignedString(System.nanoTime()));
	private final ResourceLocation atlasTexture = new ResourceLocation("cheatprogram", "cape_editor/atlas_" + Long.toUnsignedString(System.nanoTime()));
	private final Crop front = new Crop(), back = new Crop();
	private BufferedImage source, atlas;
	private Path sourceFile;
	private boolean animatedSource;
	private boolean sourceRegistered, atlasRegistered, mirrorBack = true, originalQuality = true, editingBack, atlasImport, busy, closed;
	private long request;
	private String status = "";
	private static final class Crop { double x, y, zoom; }
	private record CropSnapshot(double x, double y, double zoom) { }

	public CapeEditorScreen(Screen parent) {
		super(Lang.t("ui.cape.title")); this.parent = parent; path.onEnter = () -> load(path.text);
	}
	@Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		beginFrame();
		if (!preparePanelBackground(graphics, partialTick)) { endFrame(); return; }
		R2D.begin(graphics);
		try {
			float scale = Math.min(R2D.width() / (W + 40), R2D.height() / (H + 40));
			scale = Math.max(0.35f, Math.min(scale, Math.max(0.55f, Math.min(R2D.width() / 1060f, R2D.height() / 760f)
					* (float) ClientSettings.INSTANCE.uiScale.fraction())));
			c.set(scale, (R2D.width() - W * scale) / 2, (R2D.height() - H * scale) / 2); updateMouse();
			if (!drawCustomPanelSurface(graphics, W, H, 22)) {
				c.rect(-c.originX / scale, -c.originY / scale, R2D.width() / scale, R2D.height() / scale, Anim.black(0.16f));
				Backdrop.capture(Theme.frost()); R2D.applyState();
				c.shadow(0, 6, W, H, 22, 28, Anim.black(0.22f));
				c.lens(0, 0, W, H, 22, Theme.lift(0.08f), Theme.sheetGlass(c.scale));
			}
			c.textC(BOLD, Lang.t("ui.cape.title"), 24, 28, 16, Theme.TEXT);
			c.textC(REG, Lang.t("ui.cape.cropHint"), 24, 46, 9, Theme.TEXT_3);
			textField(path, Lang.t("ui.cape.pathHint"), Icon.FOLDER, 24, 62, 504, 28);
			action("cape.browse", "ui.cape.browse", 536, 62, 90, 28, false, this::choose);
			action("cape.load", "ui.cape.load", 634, 62, 82, 28, true, () -> load(path.text));
			action("cape.front", "ui.cape.front", 24, 105, 90, 26, !editingBack && !atlasImport, () -> { atlasImport = false; editingBack = false; refreshAtlas(); });
			action("cape.back", "ui.cape.back", 120, 105, 90, 26, editingBack && !atlasImport, () -> { atlasImport = false; editingBack = true; refreshAtlas(); });
			action("cape.atlas", "ui.cape.atlasImport", 216, 105, 240, 26, atlasImport, () -> {
				if (source == null) return;
				if (CapeImages.atlasScale(source.getWidth(), source.getHeight()) == 0) { status = Lang.t("ui.cape.atlasSize"); return; }
				atlasImport = true; originalQuality = true; refreshAtlas();
			});
			cropView(); preview();
			String displayStatus = status.isBlank() || status.equals(Lang.t("ui.cape.saved")) ? CapeTextures.status() : status;
			c.textC(REG, c.ellipsize(REG, displayStatus, 9, 690), 24, 446, 9, Theme.TEXT_2);
			if (source != null) {
				int savedScale = saveScale();
				String dimensions = (64 * savedScale) + " × " + (32 * savedScale);
				c.textC(REG, source.getWidth() + " × " + source.getHeight() + (atlasImport ? " · " : " · 10:16 → ") + dimensions,
						24, 466, 8.5f, Theme.TEXT_3);
			}
			c.textC(REG, c.ellipsize(REG, Lang.t("ui.cape.mediaHint"), 8, 444), 24, 489, 8, Theme.TEXT_3);
			button("cape.cancel", Lang.t("ui.cape.cancel"), Icon.BACK, 490, 476, 102, 28, false, this::onClose);
			action("cape.apply", "ui.cape.apply", 600, 476, 116, 28, true, this::apply);
		} finally { R2D.end(); endFrame(); }
	}
	private void action(String key, String label, float x, float y, float width, float height, boolean primary, Runnable callback) {
		R2D.pushAlpha(busy ? 0.45f : 1);
		try { button(key, Lang.t(label), null, x, y, width, height, primary, () -> { if (!busy) callback.run(); }); }
		finally { R2D.popAlpha(); }
	}
	private Crop activeCrop() { return editingBack && !mirrorBack ? back : front; }
	private void cropView() {
		c.rrect(VIEW_X, VIEW_Y, VIEW_W, VIEW_H, 12, Anim.black(0.10f));
		if (!sourceRegistered || source == null) { c.textMid(REG, Lang.t("ui.cape.empty"), CX, CY, 11, Theme.TEXT_3); return; }
		c.clip(VIEW_X, VIEW_Y, VIEW_W, VIEW_H);
		try {
			if (atlasImport) {
				float fit = Math.min((VIEW_W - 24) / source.getWidth(), (VIEW_H - 24) / source.getHeight());
				drawTexture(sourceTexture, CX - source.getWidth() * fit / 2, CY - source.getHeight() * fit / 2, source.getWidth() * fit, source.getHeight() * fit, 0, 0, 1, 1);
			} else {
				Crop crop = activeCrop();
				float left = CX - (float) (crop.x * crop.zoom), top = CY - (float) (crop.y * crop.zoom);
				drawTexture(sourceTexture, left, top, (float) (source.getWidth() * crop.zoom), (float) (source.getHeight() * crop.zoom), 0, 0, 1, 1);
				float frameX = CX - CROP_W / 2, frameY = CY - CROP_H / 2;
				c.rect(VIEW_X, VIEW_Y, frameX - VIEW_X, VIEW_H, Anim.black(0.40f));
				c.rect(frameX + CROP_W, VIEW_Y, VIEW_X + VIEW_W - frameX - CROP_W, VIEW_H, Anim.black(0.40f));
				c.rect(frameX, VIEW_Y, CROP_W, frameY - VIEW_Y, Anim.black(0.40f));
				c.rect(frameX, frameY + CROP_H, CROP_W, VIEW_Y + VIEW_H - frameY - CROP_H, Anim.black(0.40f));
				c.rect(frameX, frameY, CROP_W, 1, Theme.TEXT); c.rect(frameX, frameY + CROP_H - 1, CROP_W, 1, Theme.TEXT);
				c.rect(frameX, frameY, 1, CROP_H, Theme.TEXT); c.rect(frameX + CROP_W - 1, frameY, 1, CROP_H, Theme.TEXT);
			}
		} finally { c.unclip(); }
		hit(VIEW_X, VIEW_Y, VIEW_W, VIEW_H).left(() -> {
			if (busy || atlasImport) return;
			focused = null; Crop crop = activeCrop(); double ox = crop.x, oy = crop.y; float px = mx, py = my;
			drag = (x, y) -> { if (busy) return; crop.x = ox - (x - px) / crop.zoom; crop.y = oy - (y - py) / crop.zoom; clamp(crop); refreshAtlas(); };
		}).scroll(amount -> {
			if (busy || atlasImport) return;
			Crop crop = activeCrop(); double min = minimumZoom();
			crop.zoom = Math.max(min, Math.min(min * 40, crop.zoom * Math.pow(1.12, amount)));
			clamp(crop); refreshAtlas();
		});
	}
	private void preview() {
		c.lens(478, 105, 238, 332, 14, Theme.lift(0.035f), Theme.lensGlass(c.scale));
		c.textC(MED, Lang.t("ui.cape.preview"), 492, 121, 10.5f, Theme.TEXT);
		c.textMid(REG, Lang.t("ui.cape.front"), 536, 143, 9, Theme.TEXT_3);
		c.textMid(REG, Lang.t("ui.cape.back"), 652, 143, 9, Theme.TEXT_3);
		c.rrect(498, 155, 75, 120, 5, Anim.black(0.08f)); c.rrect(614, 155, 75, 120, 5, Anim.black(0.08f));
		ResourceLocation texture = atlasRegistered ? atlasTexture : CapeTextures.currentTexture();
		if (texture != null) {
			drawTexture(texture, 498, 155, 75, 120, 1 / 64f, 1 / 32f, 11 / 64f, 17 / 32f);
			drawTexture(texture, 614, 155, 75, 120, 12 / 64f, 1 / 32f, 22 / 64f, 17 / 32f);
		}
		c.textC(REG, Lang.t("ui.cape.mirrorBack"), 492, 305, 9.5f, Theme.TEXT_2);
		toggle("cape.mirror", 674, 298, mirrorBack, atlasImport || busy ? 0.4f : 1);
		hit(490, 292, 214, 28).left(() -> { if (!busy && !atlasImport) { mirrorBack = !mirrorBack; refreshAtlas(); } });
		float y = 328;
		for (String line : c.wrap(REG, Lang.t("ui.cape.mirrorHint"), 9, 208)) { c.textC(REG, line, 492, y, 9, Theme.TEXT_3); y += 12; }
		c.textC(REG, Lang.t("ui.cape.original"), 492, 360, 9.5f, Theme.TEXT_2);
		toggle("cape.original", 674, 353, originalQuality, atlasImport || busy ? 0.4f : 1);
		hit(490, 347, 214, 28).left(() -> { if (!busy && !atlasImport) { originalQuality = !originalQuality; refreshAtlas(); } });
		y = 381;
		for (String line : c.wrap(REG, Lang.t("ui.cape.originalHint"), 8.5f, 208)) { c.textC(REG, line, 492, y, 8.5f, Theme.TEXT_3); y += 11; }
		action("cape.reset", "ui.cape.resetCrop", 490, 408, 214, 26, false, () -> { if (source != null) { reset(activeCrop()); atlasImport = false; refreshAtlas(); } });
	}
	private void drawTexture(ResourceLocation texture, float x, float y, float width, float height, float u0, float v0, float u1, float v1) {
		R2D.texture(texture, c.px(x), c.py(y), width * c.scale, height * c.scale, u0, v0, u1, v1, 0xFFFFFFFF);
	}
	private double minimumZoom() { return Math.max(CROP_W / source.getWidth(), CROP_H / source.getHeight()); }
	private void reset(Crop crop) { crop.x = source.getWidth() / 2d; crop.y = source.getHeight() / 2d; crop.zoom = minimumZoom(); }
	private void clamp(Crop crop) {
		double halfW = CROP_W / (2 * crop.zoom), halfH = CROP_H / (2 * crop.zoom);
		crop.x = Math.max(halfW, Math.min(source.getWidth() - halfW, crop.x));
		crop.y = Math.max(halfH, Math.min(source.getHeight() - halfH, crop.y));
	}
	private static CropSnapshot snapshot(Crop crop) { return new CropSnapshot(crop.x, crop.y, crop.zoom); }
	private static int cropScale(CropSnapshot crop) {
		double width = CROP_W / crop.zoom(), height = CROP_H / crop.zoom();
		return Math.max(1, Math.min(CapeImages.MAX_SCALE, (int) Math.floor(Math.min(width / 10, height / 16))));
	}
	private int saveScale() {
		if (atlasImport) return CapeImages.atlasScale(source.getWidth(), source.getHeight());
		if (!originalQuality) return 1;
		int scale = cropScale(snapshot(front));
		return mirrorBack ? scale : Math.min(scale, cropScale(snapshot(back)));
	}
	private static BufferedImage face(BufferedImage original, CropSnapshot crop, int scale) {
		int faceWidth = 10 * scale, faceHeight = 16 * scale;
		BufferedImage image = new BufferedImage(faceWidth, faceHeight, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try {
			graphics.setComposite(AlphaComposite.Src);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			double width = CROP_W / crop.zoom(), height = CROP_H / crop.zoom();
			AffineTransform transform = new AffineTransform(); transform.scale(faceWidth / width, faceHeight / height);
			transform.translate(-(crop.x() - width / 2), -(crop.y() - height / 2));
			graphics.drawImage(original, transform, null);
		} finally { graphics.dispose(); }
		return image;
	}
	private void refreshAtlas() {
		if (source == null || closed) return;
		BufferedImage next;
		int previewScale = Math.min(8, saveScale());
		if (atlasImport) next = resize(source, 64 * previewScale, 32 * previewScale, previewScale > 1);
		else {
			BufferedImage frontFace = face(source, snapshot(front), previewScale);
			BufferedImage backFace = mirrorBack ? null : face(source, snapshot(back), previewScale);
			next = CapeImages.composeAtlas(frontFace, backFace); frontFace.flush(); if (backFace != null) backFace.flush();
		}
		if (atlas != null) atlas.flush(); atlas = next;
		register(atlasTexture, next, previewScale > 1); atlasRegistered = true;
	}
	private static BufferedImage resize(BufferedImage source, int width, int height, boolean smooth) {
		BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = result.createGraphics();
		try {
			graphics.setComposite(AlphaComposite.Src);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, smooth ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			graphics.drawImage(source, 0, 0, width, height, null);
		} finally { graphics.dispose(); }
		return result;
	}
	private void register(ResourceLocation id, BufferedImage image, boolean smooth) {
		NativeImage nativeImage = new NativeImage(image.getWidth(), image.getHeight(), false);
		for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) nativeImage.setPixelRGBA(x, y, image.getRGB(x, y));
		DynamicTexture texture = new DynamicTexture(nativeImage); texture.setFilter(smooth, false); mc.getTextureManager().register(id, texture);
	}
	private void choose() {
		busy = true; status = Lang.t("ui.cape.loading"); long token = ++request;
		String suggested = path.text.isBlank() ? null : path.text;
		FILES.execute(() -> {
			String picked = null; String error = null;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				String[] extensions = { "*.png", "*.jpg", "*.jpeg", "*.bmp", "*.gif", "*.apng", "*.webp", "*.mp4", "*.webm", "*.mov", "*.mkv", "*.avi", "*.m4v" };
				var filters = stack.mallocPointer(extensions.length); for (String extension : extensions) filters.put(stack.UTF8(extension)); filters.flip();
				picked = TinyFileDialogs.tinyfd_openFileDialog(Lang.t("ui.cape.title"), suggested, filters, "Images / GIF / APNG / WebP / Video", false);
			} catch (Throwable unavailable) { error = "ui.cape.pickerUnavailable"; }
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
		if (busy || value.isBlank()) return;
		String input = value.strip();
		if (input.length() > 1 && input.startsWith("\"") && input.endsWith("\"")) input = input.substring(1, input.length() - 1);
		String filename = input; long token = ++request; busy = true; status = Lang.t("ui.cape.loading");
		FILES.execute(() -> {
			MediaCapeFiles.Imported decoded = null; String failure = null;
			try { decoded = MediaCapeFiles.importFile(Path.of(filename)); }
			catch (MediaCapeFiles.DecoderUnavailable unavailable) { failure = "ui.cape.decoderUnavailable"; }
			catch (OutOfMemoryError tooLarge) { failure = "ui.cape.tooLarge"; }
			catch (Exception badFile) { failure = "ui.cape.fileError"; }
			MediaCapeFiles.Imported imported = decoded; BufferedImage image = imported == null ? null : imported.firstFrame(); String error = failure;
			mc.execute(() -> {
				if (closed || token != request) { if (image != null) image.flush(); return; }
				busy = false;
				if (error != null || image == null) { status = Lang.t(error == null ? "ui.cape.fileError" : error); return; }
				if (source != null) source.flush(); source = image; atlasImport = false; editingBack = false;
				sourceFile = imported.file(); animatedSource = imported.animated();
				reset(front); reset(back);
				int width = source.getWidth(), height = source.getHeight();
				float factor = Math.min(1, 1024f / Math.max(width, height));
				BufferedImage preview = new BufferedImage(Math.max(1, Math.round(width * factor)), Math.max(1, Math.round(height * factor)), BufferedImage.TYPE_INT_ARGB);
				Graphics2D graphics = preview.createGraphics();
				try { graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); graphics.drawImage(source, 0, 0, preview.getWidth(), preview.getHeight(), null); }
				finally { graphics.dispose(); }
				register(sourceTexture, preview, true); sourceRegistered = true; preview.flush(); refreshAtlas(); status = animatedSource ? Lang.t("ui.cape.mediaReady") : "";
			});
		});
	}
	private static MediaCapeRecipe.Crop normalized(CropSnapshot crop, BufferedImage original) {
		double width = CROP_W / crop.zoom(), height = CROP_H / crop.zoom();
		return new MediaCapeRecipe.Crop((crop.x() - width / 2) / original.getWidth(), (crop.y() - height / 2) / original.getHeight(),
				width / original.getWidth(), height / original.getHeight());
	}
	private void apply() {
		if (busy || closed || atlas == null || source == null) return;
		// Capture only immutable crop values and the original decode. The small GUI textures are never saved.
		BufferedImage original = source;
		CropSnapshot frontCrop = snapshot(front), backCrop = snapshot(back);
		boolean importAtlas = atlasImport, mirror = mirrorBack;
		Path mediaFile = animatedSource ? sourceFile : null;
		int scale = saveScale();
		long token = ++request; busy = true; status = Lang.t("ui.cape.saving");
		FILES.execute(() -> {
			BufferedImage saved = null, frontFace = null, backFace = null;
			try {
				if (importAtlas) saved = CapeImages.copyAtlas(original);
				else {
					frontFace = face(original, frontCrop, scale);
					if (!mirror) backFace = face(original, backCrop, scale);
					saved = CapeImages.composeAtlas(frontFace, backFace);
				}
				// saveAtlas takes its own snapshot here on FILES; PNG encoding is on the backend's IO worker.
				var saving = mediaFile == null ? CapeTextures.saveAtlas(saved) : CapeTextures.saveMedia(mediaFile,
						new MediaCapeRecipe(original.getWidth(), original.getHeight(), scale, importAtlas, mirror,
								normalized(frontCrop, original), normalized(backCrop, original)), saved);
				saving.whenComplete((texture, error) -> saveFinished(token, error == null ? "ui.cape.saved" : "ui.cape.fileError"));
			} catch (OutOfMemoryError tooLarge) { saveFinished(token, "ui.cape.tooLarge"); }
			catch (Exception failure) { saveFinished(token, "ui.cape.fileError"); }
			finally {
				if (saved != null) saved.flush(); if (frontFace != null) frontFace.flush(); if (backFace != null) backFace.flush();
			}
		});
	}
	private void saveFinished(long token, String message) {
		mc.execute(() -> { if (closed || token != request) return; busy = false; status = Lang.t(message); });
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
		if (sourceRegistered) mc.getTextureManager().release(sourceTexture);
		if (atlasRegistered) mc.getTextureManager().release(atlasTexture);
		if (source != null) source.flush(); if (atlas != null) atlas.flush(); source = null; atlas = null;
		super.removed();
	}
}
