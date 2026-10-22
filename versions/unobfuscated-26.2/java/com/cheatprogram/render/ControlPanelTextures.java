package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.GuiBase;
import com.cheatprogram.module.visual.ControlPanel;
import com.cheatprogram.util.MediaCapeFiles;
import com.mojang.blaze3d.platform.NativeImage;
import com.cheatprogram.compat.gl.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One cached GUI background. All file access/decoding is asynchronous and animation holds one pending frame. */
public final class ControlPanelTextures {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final AtomicLong REQUEST = new AtomicLong();
	private static final ExecutorService FILES = executor("Cheat Program panel media");
	private static final ExecutorService VIDEO = executor("Cheat Program panel animation");
	private static final ExecutorService PICKER = executor("Cheat Program panel picker");
	public record Image(Identifier texture, int width, int height, long loadedAt) { }
	private record Source(Path file, int width, int height, boolean animated, long generation) { }
	private static Image image;
	private static DynamicTexture liveTexture;
	private static Source source;
	private static String requested = "", activePath = "";
	private static String status = "ui.panel.empty";
	private static boolean choosing, loading;
	private static volatile boolean guiVisible;
	private static volatile Playback playback;
	private static volatile long failedPlayback = -1;
	private static Object previousWorld;
	private ControlPanelTextures() { }
	private static ExecutorService executor(String name) {
		return Executors.newSingleThreadExecutor(r -> { Thread thread = new Thread(r, name); thread.setDaemon(true); return thread; });
	}

	public static String statusKey() { return status; }
	public static String statusKey(String path) { return status.equals("ui.panel.empty") && !normalize(path).isBlank() ? "ui.panel.selected" : status; }
	public static boolean choosing() { return choosing; }

	/** Starts loading immediately, including while the GUI is closed, without replacing a still-valid old image. */
	public static void warm(String path) {
		String value = normalize(path);
		if (value.isBlank()) {
			if (image != null || !requested.isEmpty() || loading) release();
			return;
		}
		if (!requested.equals(value)) request(value);
	}
	public static Image image(String path) { warm(path); return image; }
	public static boolean ready(String path) { return image != null && activePath.equals(normalize(path)); }
	public static boolean pending(String path) { warm(path); return loading && requested.equals(normalize(path)); }
	public static boolean isLoading(String path) { return pending(path); }
	/** Errors settle too, so a missing file can never prevent opening the GUI. */
	public static boolean settled(String path) { return !pending(path); }

	/** Global client tick: prepares the selected first frame ahead of the GUI opening. */
	public static void onTick() {
		ControlPanel panel = ModuleManager.get(ControlPanel.class);
		if (panel == null || !panel.isEnabled()) {
			if (image != null || !requested.isEmpty() || loading || playback != null) release();
			previousWorld = MC.level;
			return;
		}
		warm(panel.imagePath());
		// A settings-page change removes the old screen before installing the next one. Visibility
		// belongs to the current screen, so navigation never stops and restarts the shared decoder.
		setGuiVisible(MC.gui.screen() instanceof GuiBase && !panel.imagePath().isBlank());
		if (previousWorld != MC.level) { stopPlayback(); previousWorld = MC.level; failedPlayback = -1; }
		updatePlayback();
	}
	public static void tick() { onTick(); }

	/** Closing the GUI pauses decoding, while retaining the uploaded frame for its next opening. */
	public static void setGuiVisible(boolean visible) {
		if (guiVisible == visible) return;
		guiVisible = visible;
		if (!visible) { stopPlayback(); failedPlayback = -1; }
		else updatePlayback();
	}
	public static void retry() {
		REQUEST.incrementAndGet(); stopPlayback(); requested = ""; loading = false; failedPlayback = -1;
		status = image == null ? "ui.panel.selected" : "ui.panel.ready";
	}
	/** Module disable owns full disposal; onTick pauses playback after the last custom screen closes. */
	public static void release() {
		REQUEST.incrementAndGet(); stopPlayback(); guiVisible = false; loading = false;
		requested = ""; activePath = ""; source = null; failedPlayback = -1; status = "ui.panel.empty";
		Image prior = image; image = null; liveTexture = null;
		if (prior != null) onRender(() -> MC.getTextureManager().release(prior.texture()));
	}

	/** Compatibility picker; the full GUI importer additionally offers the normalized crop editor. */
	public static void choose(ControlPanel panel) {
		if (choosing) return;
		choosing = true;
		String suggested = panel.imagePath().isBlank() ? null : panel.imagePath();
		PICKER.execute(() -> {
			String picked = null; boolean failed = false;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				String[] extensions = {"*.png", "*.jpg", "*.jpeg", "*.bmp", "*.webp", "*.gif", "*.apng", "*.mp4", "*.webm", "*.mov", "*.mkv", "*.avi", "*.m4v"};
				var filters = stack.mallocPointer(extensions.length);
				for (String extension : extensions) filters.put(stack.UTF8(extension)); filters.flip();
				picked = TinyFileDialogs.tinyfd_openFileDialog(Lang.t("ui.panel.choose"), suggested, filters, "Images / GIF / APNG / WebP / MP4 / WebM", false);
			} catch (Throwable e) { failed = true; }
			String result = picked; boolean error = failed;
			MC.execute(() -> {
				choosing = false;
				if (error) status = "ui.panel.pickerUnavailable";
				else if (result != null) { panel.imagePath(result); warm(result); Config.saveAll(); }
			});
		});
	}

	private static String normalize(String path) {
		String value = path == null ? "" : path.strip();
		if (value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1).strip();
		return value;
	}
	private static void request(String value) {
		long token = REQUEST.incrementAndGet(); stopPlayback(); requested = value; loading = true; failedPlayback = -1; status = "ui.panel.loading";
		FILES.execute(() -> {
			NativeImage pixels = null;
			try {
				check(token);
				Path file = Path.of(value).toAbsolutePath().normalize();
				if (!Files.isRegularFile(file) || Files.size(file) < 1 || Files.size(file) > MediaCapeFiles.MAX_MEDIA_BYTES)
					throw new IOException("Panel media is unavailable or too large");
				String extension = MediaCapeFiles.extension(file);
				if (!List.of("png", "jpg", "jpeg", "bmp", "webp", "gif", "apng", "mp4", "webm", "mov", "mkv", "avi", "m4v").contains(extension))
					throw new IOException("Unsupported panel media extension");
				int limit = List.of("jpg", "jpeg", "bmp", "png").contains(extension) ? 2048 : 1280;
				MediaCapeFiles.Imported imported = MediaCapeFiles.importFile(file, limit);
				BufferedImage first = imported.firstFrame();
				try {
					if (imported.animated()) first = bounded(first, 1280);
					check(token);
					Source replacement = new Source(file, first.getWidth(), first.getHeight(), imported.animated(), token);
					pixels = pixels(first, () -> check(token));
					NativeImage owned = pixels; pixels = null;
					try { onRender(() -> publish(owned, replacement, value)); }
					catch (RuntimeException error) { owned.close(); throw error; }
				} finally { first.flush(); }
			} catch (CancellationException ignored) { if (pixels != null) pixels.close(); }
			catch (Exception | OutOfMemoryError error) {
				if (pixels != null) pixels.close();
				MC.execute(() -> {
					if (REQUEST.get() == token) {
						loading = false; status = error instanceof MediaCapeFiles.DecoderUnavailable ? "ui.panel.decoderUnavailable" : "ui.panel.fileError";
						CheatProgram.LOGGER.warn("Unable to read control panel media", error);
					}
				});
			}
		});
	}
	private static BufferedImage bounded(BufferedImage original, int maximum) {
		if (Math.max(original.getWidth(), original.getHeight()) <= maximum) return original;
		double ratio = (double) maximum / Math.max(original.getWidth(), original.getHeight());
		BufferedImage result = new BufferedImage(Math.max(1, (int) Math.round(original.getWidth() * ratio)), Math.max(1, (int) Math.round(original.getHeight() * ratio)), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = result.createGraphics();
		try { graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR); graphics.drawImage(original, 0, 0, result.getWidth(), result.getHeight(), null); }
		finally { graphics.dispose(); original.flush(); }
		return result;
	}
	private static NativeImage pixels(BufferedImage buffered, Runnable check) {
		NativeImage result = new NativeImage(buffered.getWidth(), buffered.getHeight(), false);
		boolean complete = false;
		try {
			int[] row = new int[buffered.getWidth()];
			for (int y = 0; y < buffered.getHeight(); y++) {
				check.run(); buffered.getRGB(0, y, row.length, 1, row, 0, row.length);
				for (int x = 0; x < row.length; x++) result.setPixel(x, y, row[x]);
			}
			check.run(); complete = true; return result;
		} finally { if (!complete) result.close(); }
	}
	private static void publish(NativeImage pixels, Source replacement, String value) {
		if (REQUEST.get() != replacement.generation()) { pixels.close(); return; }
		Identifier id = Identifier.fromNamespaceAndPath("cheatprogram", "dynamic/control_panel/" + replacement.generation());
		DynamicTexture texture = null;
		boolean registered = false;
		try {
			texture = new DynamicTexture(() -> "Cheat Program texture", pixels); com.cheatprogram.compat.gl.RenderSystem.setTextureFilter(texture, true, false);
			MC.getTextureManager().register(id, texture); registered = true;
			Image prior = image;
			liveTexture = texture; source = replacement; activePath = value;
			image = new Image(id, replacement.width(), replacement.height(), System.nanoTime());
			loading = false; status = "ui.panel.ready";
			if (prior != null) MC.getTextureManager().release(prior.texture());
			updatePlayback();
		} catch (RuntimeException error) {
			if (!registered) { if (texture != null) texture.close(); else pixels.close(); }
			loading = false; status = "ui.panel.fileError";
			CheatProgram.LOGGER.warn("Unable to upload control panel media", error);
		}
	}
	private static void updatePlayback() {
		ControlPanel panel = ModuleManager.get(ControlPanel.class);
		if (!guiVisible || panel == null || !panel.isEnabled() || loading || source == null || !source.animated() || !activePath.equals(requested)) {
			stopPlayback(); return;
		}
		Playback current = playback;
		if (current != null && !current.cancelled && current.source == source) return;
		if (failedPlayback == source.generation()) return;
		stopPlayback();
		Playback next = new Playback(source); playback = next; VIDEO.execute(next);
	}
	private static void stopPlayback() { Playback current = playback; playback = null; if (current != null) current.cancel(); }
	private static void check(long token) { if (REQUEST.get() != token) throw new CancellationException("Panel media request was replaced"); }
	private static void onRender(Runnable action) {
		MC.execute(() -> { if (RenderSystem.isOnRenderThread()) action.run(); else RenderSystem.recordRenderCall(action::run); });
	}

	private static final class Playback implements Runnable {
		private final Source source;
		private final AtomicReference<NativeImage> pending = new AtomicReference<>();
		private final AtomicBoolean scheduled = new AtomicBoolean();
		private volatile boolean cancelled;
		private volatile Thread worker;
		private volatile MediaCapeFiles.Frames frames;
		Playback(Source source) { this.source = source; }
		@Override public void run() {
			worker = Thread.currentThread();
			try {
				checkActive();
				MediaCapeFiles.Frames decoded = MediaCapeFiles.openFrames(source.file(), source.width(), source.height(), 20); frames = decoded;
				try (decoded) {
					long due = System.nanoTime();
					while (!cancelled) {
						checkActive();
						MediaCapeFiles.Frame frame = decoded.next();
						try { checkActive(); offer(pixels(frame.image(), this::checkActive)); }
						finally { frame.image().flush(); }
						due += frame.durationNanos();
						long wait = due - System.nanoTime();
						if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
						else if (wait < -1_000_000_000L) due = System.nanoTime();
					}
				}
			} catch (CancellationException | InterruptedException ignored) { }
			catch (Exception | OutOfMemoryError failure) {
				if (!cancelled && REQUEST.get() == source.generation()) {
					failedPlayback = source.generation();
					MC.execute(() -> {
						if (REQUEST.get() == source.generation()) status = failure instanceof MediaCapeFiles.DecoderUnavailable ? "ui.panel.decoderUnavailable" : "ui.panel.animationError";
					});
					CheatProgram.LOGGER.warn("Unable to play control panel media; keeping the last frame", failure);
				}
			} finally {
				frames = null; worker = null; cancelled = true;
				NativeImage old = pending.getAndSet(null); if (old != null) old.close();
			}
		}
		private void checkActive() {
			if (cancelled || !guiVisible || playback != this || REQUEST.get() != source.generation() || Thread.currentThread().isInterrupted())
				throw new CancellationException("Panel animation paused");
		}
		private void offer(NativeImage frame) {
			if (cancelled) { frame.close(); return; }
			NativeImage old = pending.getAndSet(frame); if (old != null) old.close();
			if (cancelled && pending.compareAndSet(frame, null)) { frame.close(); return; }
			if (scheduled.compareAndSet(false, true)) schedule();
		}
		private void schedule() {
			try { onRender(this::upload); }
			catch (RuntimeException failure) { scheduled.set(false); cancel(); }
		}
		private void upload() {
			NativeImage frame = pending.getAndSet(null);
			try {
				if (frame == null) return;
				checkActive();
				DynamicTexture texture = liveTexture;
				NativeImage destination = texture == null ? null : texture.getPixels();
				if (ControlPanelTextures.source != source || destination == null || destination.getWidth() != frame.getWidth() || destination.getHeight() != frame.getHeight()) return;
				destination.copyFrom(frame); texture.upload();
			} catch (CancellationException ignored) { }
			catch (RuntimeException failure) { failedPlayback = source.generation(); status = "ui.panel.animationError"; cancel(); }
			finally {
				if (frame != null) frame.close(); scheduled.set(false);
				if (!cancelled && pending.get() != null && scheduled.compareAndSet(false, true)) schedule();
			}
		}
		void cancel() {
			cancelled = true;
			MediaCapeFiles.Frames current = frames; if (current != null) current.cancel();
			Thread thread = worker; if (thread != null) thread.interrupt();
			NativeImage old = pending.getAndSet(null); if (old != null) old.close();
		}
	}
}
