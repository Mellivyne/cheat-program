package com.cheatprogram.util;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.visual.CustomCape;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One local cape file and a render-thread-owned texture; source files are decoded by the explicit GUI importer. */
public final class CapeTextures {
	private static final Minecraft MC = Minecraft.getInstance();
	public static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("cheatprogram", "dynamic/custom_cape");
	private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
	private static final AtomicLong GENERATION = new AtomicLong();
	private static final Object UPDATES = new Object();
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Cheat Program local cape");
		thread.setDaemon(true);
		return thread;
	});
	private static volatile ResourceLocation current;
	private static volatile BufferedImage atlas;
	private static volatile String status = "未导入披风";
	private static volatile DynamicTexture liveTexture;
	private static volatile SavedMedia media;
	private static volatile Playback playback;
	private static volatile boolean saving;
	private static volatile SavedMedia failedPlayback;
	private static Object previousWorld;
	private static final ExecutorService VIDEO = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "Cheat Program animated cape"); thread.setDaemon(true); return thread;
	});
	private record SavedMedia(Path file, MediaCapeRecipe recipe) { }

	private CapeTextures() { }
	public static ResourceLocation currentTexture() { initialize(); return current; }
	public static BufferedImage currentAtlas() { initialize(); BufferedImage saved = atlas; return saved == null ? null : CapeImages.copyAtlas(saved); }
	public static String status() { return status; }
	public static Path savedFile() { return FabricLoader.getInstance().getConfigDir().resolve("cheatprogram-open").resolve("cape.png"); }
	private static Path mediaDirectory() { return savedFile().getParent().resolve("cape_media"); }
	private static Path mediaMetadata() { return mediaDirectory().resolve("animation.properties"); }

	/** Idempotent startup load. File access is asynchronous; texture construction/upload is always on the render thread. */
	public static void initialize() {
		if (!INITIALIZED.compareAndSet(false, true)) return;
		long token = GENERATION.get();
		IO.execute(() -> {
			try {
				String warning = null;
				SavedMedia restored;
				try { restored = readMedia(); }
				catch (Exception ignored) { restored = null; warning = "动态披风媒体无法读取，已保留静态披风；请重新导入"; }
				synchronized (UPDATES) { check(token); media = restored; }
				Path file = savedFile();
				if (GENERATION.get() != token) return;
				if (!Files.isRegularFile(file)) {
					if (media == null) return;
					MediaCapeFiles.Imported imported = MediaCapeFiles.importFile(media.file());
					BufferedImage fallback;
					try { fallback = media.recipe().atlas(imported.firstFrame()); } finally { imported.firstFrame().flush(); }
					upload(fallback, token, false, new CompletableFuture<>()); return;
				}
				status = "正在加载披风";
				BufferedImage image = readSaved(file);
				CompletableFuture<ResourceLocation> loaded = new CompletableFuture<>();
				String problem = warning;
				if (problem != null) loaded.thenRun(() -> { if (GENERATION.get() == token) status = problem; });
				upload(image, token, false, loaded);
			} catch (Exception ignored) { if (GENERATION.get() == token) status = "保存的披风无法读取，请重新导入"; }
		});
	}

	public static CompletableFuture<ResourceLocation> applyCropped(BufferedImage front, BufferedImage back) {
		try { return saveAtlas(CapeImages.composeAtlas(front, back)); }
		catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
	}
	public static CompletableFuture<ResourceLocation> applyCropped(BufferedImage front) { return applyCropped(front, null); }

	/** Takes an owned snapshot so the editor may keep modifying its preview after this call. */
	public static CompletableFuture<ResourceLocation> saveAtlas(BufferedImage image) {
		return save(image, null, null);
	}

	/** Stores the media itself, not its entire decoded frame sequence. The PNG remains a portable first-frame fallback. */
	public static CompletableFuture<ResourceLocation> saveMedia(Path source, MediaCapeRecipe recipe, BufferedImage firstAtlas) {
		if (source == null || recipe == null) return CompletableFuture.failedFuture(new IOException("请选择动态披风媒体。"));
		return save(firstAtlas, source, recipe);
	}

	private static CompletableFuture<ResourceLocation> save(BufferedImage image, Path source, MediaCapeRecipe recipe) {
		BufferedImage owned;
		try { owned = CapeImages.copyAtlas(image); }
		catch (RuntimeException error) { return CompletableFuture.failedFuture(error); }
		long token;
		synchronized (UPDATES) { INITIALIZED.set(true); saving = true; token = GENERATION.incrementAndGet(); }
		stopPlayback();
		CompletableFuture<ResourceLocation> result = new CompletableFuture<>();
		result.whenComplete((value, failure) -> { if (GENERATION.get() == token) saving = false; });
		status = "正在保存披风";
		IO.execute(() -> {
			Path temporary = null;
			Path copiedMedia = null, temporaryMetadata = null;
			try {
				synchronized(com.cheatprogram.core.Config.persistenceLock()) {
				check(token);
				Path file = savedFile();
				Files.createDirectories(file.getParent());
				temporary = file.resolveSibling("cape-" + token + ".tmp");
				if (!ImageIO.write(owned, "png", temporary.toFile())) throw new IOException("PNG writer unavailable");
				SavedMedia replacement = null;
				if (source != null) {
					if (!Files.isRegularFile(source) || Files.size(source) > MediaCapeFiles.MAX_MEDIA_BYTES) throw new IOException("媒体文件无法读取或过大。");
					String extension = MediaCapeFiles.extension(source);
					if (!extension.matches("[a-z0-9]{1,6}")) throw new IOException("无效的媒体扩展名。");
					Files.createDirectories(mediaDirectory());
					copiedMedia = mediaDirectory().resolve("source-" + UUID.randomUUID() + "." + extension);
					Files.copy(source, copiedMedia);
					if (Files.size(copiedMedia) > MediaCapeFiles.MAX_MEDIA_BYTES) throw new IOException("媒体文件过大。");
					temporaryMetadata = mediaDirectory().resolve("animation-" + token + ".tmp");
					try (var output = Files.newOutputStream(temporaryMetadata)) { recipe.properties(copiedMedia.getFileName().toString()).store(output, "Cheat Program looping cape"); }
					replacement = new SavedMedia(copiedMedia, recipe);
				}
				synchronized (UPDATES) {
					check(token);
					try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
					catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
					if (temporaryMetadata != null) {
						try { Files.move(temporaryMetadata, mediaMetadata(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
						catch (AtomicMoveNotSupportedException ignored) { Files.move(temporaryMetadata, mediaMetadata(), StandardCopyOption.REPLACE_EXISTING); }
					} else Files.deleteIfExists(mediaMetadata());
					media = replacement; failedPlayback = null;
					copiedMedia = null; // The published metadata owns this file from here on.
				}
				cleanupMedia(replacement);
				upload(owned, token, true, result);
				}
			} catch (CancellationException error) { result.completeExceptionally(error); }
			catch (Exception ignored) {
				if (GENERATION.get() == token) status = "披风保存失败，原披风保持不变";
				result.completeExceptionally(new IOException("披风保存失败，请检查配置目录是否可写。"));
			} finally {
				if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
				if (temporaryMetadata != null) try { Files.deleteIfExists(temporaryMetadata); } catch (IOException ignored) { }
				if (copiedMedia != null) try { Files.deleteIfExists(copiedMedia); } catch (IOException ignored) { }
			}
		});
		return result;
	}

	public static CompletableFuture<Void> clear() {
		long token;
		synchronized (UPDATES) { INITIALIZED.set(true); token = GENERATION.incrementAndGet(); }
		stopPlayback(); saving = false; media = null; failedPlayback = null;
		current = null;
		atlas = null;
		status = "已移除自定义披风";
		CompletableFuture<Void> result = new CompletableFuture<>();
		IO.execute(() -> {
			try { synchronized(com.cheatprogram.core.Config.persistenceLock()) { check(token); Files.deleteIfExists(savedFile()); Files.deleteIfExists(mediaMetadata()); cleanupMedia(null); } }
			catch (CancellationException error) { result.completeExceptionally(error); return; }
			catch (IOException ignored) {
				onRender(() -> {
					if (GENERATION.get() == token) { liveTexture = null; MC.getTextureManager().release(TEXTURE); }
					result.completeExceptionally(new IOException("已移除当前披风，但保存文件无法删除。"));
				});
				return;
			}
			onRender(() -> {
				if (GENERATION.get() == token) { liveTexture = null; MC.getTextureManager().release(TEXTURE); }
				result.complete(null);
			});
		});
		return result;
	}

	private static BufferedImage readSaved(Path file) throws IOException {
		if (Files.size(file) > CapeImages.MAX_SAVED_BYTES) throw new IOException("Cape file is too large");
		try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
			if (input == null) throw new IOException("Invalid cape file");
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) throw new IOException("Invalid cape image");
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				if (CapeImages.atlasScale(reader.getWidth(0), reader.getHeight(0)) == 0) throw new IOException("Invalid cape dimensions");
				return CapeImages.copyAtlas(reader.read(0));
			} finally { reader.dispose(); }
		}
	}

	private static void upload(BufferedImage image, long token, boolean enable, CompletableFuture<ResourceLocation> result) {
		// Both callers run on IO. NativeImage allocation/pixel writes use CPU memory only;
		// defer GL texture construction and registration until after conversion is finished.
		final NativeImage pixels;
		final int scale;
		try {
			check(token);
			scale = CapeImages.atlasScale(image.getWidth(), image.getHeight());
			if (scale == 0) throw new IllegalArgumentException("Invalid cape dimensions");
			pixels = preparePixels(image, token);
		} catch (CancellationException error) { result.completeExceptionally(error); return; }
		catch (Exception ignored) { uploadFailed(token, result); return; }
		try { onRender(() -> {
			DynamicTexture texture = null;
			boolean registered = false;
			try {
				check(token);
				texture = new DynamicTexture(pixels);
				texture.setFilter(scale > 1, false); // HD uses linear filtering; standard pixels stay crisp. No mipmap atlas bleed.
				MC.getTextureManager().register(TEXTURE, texture); // Vanilla closes the prior DynamicTexture and NativeImage.
				registered = true;
				liveTexture = texture;
				atlas = image;
				current = TEXTURE;
				status = "披风已应用";
				if (enable) {
					CustomCape module = ModuleManager.get(CustomCape.class);
					if (module != null) module.setEnabled(true);
				}
				result.complete(TEXTURE);
			} catch (CancellationException error) { result.completeExceptionally(error); }
			catch (Exception ignored) { uploadFailed(token, result); }
			finally {
				if (!registered) { if (texture != null) texture.close(); else pixels.close(); }
			}
		}); } catch (RuntimeException ignored) {
			pixels.close(); // Scheduling failed before the render thread took ownership.
			uploadFailed(token, result);
		}
	}

	private static NativeImage preparePixels(BufferedImage image, long token) {
		int width = image.getWidth(), height = image.getHeight();
		NativeImage pixels = new NativeImage(width, height, true);
		boolean finished = false;
		try {
			int[] row = new int[width];
			for (int y = 0; y < height; y++) {
				check(token);
				image.getRGB(0, y, width, 1, row, 0, width);
				for (int x = 0; x < width; x++) pixels.setPixel(x, y, row[x]);
			}
			check(token);
			finished = true;
			return pixels;
		} finally { if (!finished) pixels.close(); }
	}

	private static void uploadFailed(long token, CompletableFuture<ResourceLocation> result) {
		if (GENERATION.get() == token) status = "披风纹理上传失败，请重新导入";
		result.completeExceptionally(new IOException("披风纹理上传失败。"));
	}

	/** Client-tick hook: decoding is paused out of a world and while CustomCape is disabled. */
	public static void onTick() {
		initialize();
		CustomCape module = ModuleManager.get(CustomCape.class);
		boolean enabled = module != null && module.isEnabled() && MC.level != null && MC.player != null;
		if (previousWorld != MC.level) { stopPlayback(); previousWorld = MC.level; failedPlayback = null; }
		if (!enabled || saving || media == null || current == null) {
			stopPlayback(); if (!enabled) failedPlayback = null; return;
		}
		SavedMedia wanted = media;
		Playback active = playback;
		if (active != null && (!active.cancelled && active.media == wanted && active.world == MC.level)) return;
		stopPlayback();
		if (failedPlayback == wanted) return;
		Playback next = new Playback(wanted, GENERATION.get(), MC.level);
		playback = next;
		VIDEO.execute(next);
	}

	private static SavedMedia readMedia() throws IOException {
		Path metadata = mediaMetadata();
		if (!Files.isRegularFile(metadata)) return null;
		if (Files.size(metadata) > 16 * 1024) throw new IOException("动态披风配置过大。");
		Properties values = new Properties();
		try (var input = Files.newInputStream(metadata)) { values.load(input); }
		String name = values.getProperty("source", "");
		if (!name.matches("source-[a-fA-F0-9-]+\\.[a-z0-9]{1,6}")) throw new IOException("无效的披风媒体路径。");
		Path file = mediaDirectory().resolve(name).toAbsolutePath().normalize();
		if (!file.getParent().equals(mediaDirectory().toAbsolutePath().normalize()) || !Files.isRegularFile(file)
				|| Files.size(file) > MediaCapeFiles.MAX_MEDIA_BYTES) throw new IOException("保存的披风媒体缺失或过大。");
		try { return new SavedMedia(file, MediaCapeRecipe.from(values)); }
		catch (RuntimeException invalid) { throw new IOException("动态披风裁剪配置无法读取。", invalid); }
	}

	private static void cleanupMedia(SavedMedia keep) {
		Path directory = mediaDirectory();
		if (!Files.isDirectory(directory)) return;
		try (var files = Files.list(directory)) {
			files.filter(path -> path.getFileName().toString().matches("source-[a-fA-F0-9-]+\\.[a-z0-9]{1,6}"))
					.filter(path -> keep == null || !path.toAbsolutePath().normalize().equals(keep.file.toAbsolutePath().normalize()))
					.forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } });
		} catch (IOException ignored) { }
	}

	private static void stopPlayback() {
		Playback active = playback; playback = null;
		if (active != null) active.cancel();
	}

	private static final class Playback implements Runnable {
		private final SavedMedia media;
		private final long generation;
		private final Object world;
		private final AtomicReference<NativeImage> pending = new AtomicReference<>();
		private final AtomicBoolean scheduled = new AtomicBoolean();
		private volatile boolean cancelled;
		private volatile Thread worker;
		private volatile MediaCapeFiles.Frames frames;
		Playback(SavedMedia media, long generation, Object world) { this.media = media; this.generation = generation; this.world = world; }

		@Override public void run() {
			worker = Thread.currentThread();
			try {
				checkActive();
				MediaCapeFiles.Frames source = MediaCapeFiles.open(media.file, media.recipe); frames = source;
				try (source) {
					long due = System.nanoTime();
					while (!cancelled) {
						checkActive();
						MediaCapeFiles.Frame decoded = source.next();
						BufferedImage nextAtlas = null;
						try {
							checkActive(); nextAtlas = media.recipe.atlas(decoded.image());
							offer(pixels(nextAtlas));
						} finally { decoded.image().flush(); if (nextAtlas != null) nextAtlas.flush(); }
						due += decoded.durationNanos();
						long wait = due - System.nanoTime();
						if (wait > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);
						else if (wait < -1_000_000_000L) due = System.nanoTime();
					}
				}
			} catch (CancellationException | InterruptedException ignored) { }
			catch (Exception | OutOfMemoryError failure) {
				if (!cancelled && GENERATION.get() == generation && media == CapeTextures.media) {
					failedPlayback = media;
					status = failure instanceof MediaCapeFiles.DecoderUnavailable
							? "视频解码器不可用，暂用静态首帧；安装 FFmpeg 后重新开启披风"
							: "动态披风播放失败，已保留静态披风；请重新导入媒体";
				}
			} finally {
				frames = null; worker = null; cancelled = true;
				NativeImage image = pending.getAndSet(null); if (image != null) image.close();
			}
		}

		private NativeImage pixels(BufferedImage image) {
			NativeImage result = new NativeImage(image.getWidth(), image.getHeight(), true);
			boolean ready = false;
			try {
				int width = 22 * media.recipe.scale(), height = 17 * media.recipe.scale();
				int[] row = new int[width];
				for (int y = 0; y < height; y++) {
					checkActive(); image.getRGB(0, y, width, 1, row, 0, width);
					for (int x = 0; x < width; x++) result.setPixel(x, y, row[x]);
				}
				checkActive(); ready = true; return result;
			} finally { if (!ready) result.close(); }
		}

		private void offer(NativeImage image) {
			if (cancelled) { image.close(); return; }
			NativeImage old = pending.getAndSet(image); if (old != null) old.close();
			if (cancelled && pending.compareAndSet(image, null)) { image.close(); return; }
			if (scheduled.compareAndSet(false, true)) scheduleUpload();
		}

		private void scheduleUpload() {
			try { onRender(this::uploadFrame); }
			catch (RuntimeException failure) {
				scheduled.set(false); NativeImage image = pending.getAndSet(null); if (image != null) image.close(); cancel();
			}
		}
		private void uploadFrame() {
			NativeImage image = pending.getAndSet(null);
			try {
				if (image == null || cancelled || playback != this || GENERATION.get() != generation || MC.level != world) return;
				CustomCape module = ModuleManager.get(CustomCape.class);
				if (module == null || !module.isEnabled()) return;
				DynamicTexture texture = liveTexture;
				NativeImage target = texture == null ? null : texture.getPixels();
				if (target == null || target.getWidth() != image.getWidth() || target.getHeight() != image.getHeight()) return;
				target.copyFrom(image); texture.upload(); // Fixed ResourceLocation, texture id and GPU allocation for every frame.
				status = "动态披风循环播放中";
			} catch (RuntimeException failure) {
				if (!cancelled) { failedPlayback = media; status = "动态披风上传失败，请重新开启披风"; } cancel();
			} finally {
				if (image != null) image.close(); scheduled.set(false);
				if (!cancelled && pending.get() != null && scheduled.compareAndSet(false, true)) scheduleUpload();
			}
		}
		private void checkActive() {
			if (cancelled || playback != this || GENERATION.get() != generation || Thread.currentThread().isInterrupted())
				throw new CancellationException("动态披风播放已暂停。");
		}
		void cancel() {
			cancelled = true;
			MediaCapeFiles.Frames source = frames; if (source != null) source.cancel();
			Thread thread = worker; if (thread != null) thread.interrupt();
			NativeImage image = pending.getAndSet(null); if (image != null) image.close();
		}
	}

	private static void check(long token) { if (GENERATION.get() != token || !com.cheatprogram.core.Config.persistenceAllowed()) throw new CancellationException("已由更新的披风操作替代。"); }
	public static void stopForInitialization(){GENERATION.incrementAndGet();stopPlayback();saving=false;media=null;failedPlayback=null;current=null;atlas=null;}
	private static void onRender(Runnable action) {
		MC.execute(() -> { if (RenderSystem.isOnRenderThread()) action.run(); else RenderSystem.recordRenderCall(action::run); });
	}
}
