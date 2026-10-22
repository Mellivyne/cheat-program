package com.cheatprogram.ai;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Per-character skin copies. Credentials and remote image services never participate in importing a skin. */
final class AiPlayerSkins {
	private static final Minecraft MC = Minecraft.getInstance();
	private static final ExecutorService FILES = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "AI player skins"); t.setDaemon(true); return t; });
	private static final Map<UUID, AtomicLong> GENERATIONS = new ConcurrentHashMap<>();
	private AiPlayerSkins() { }
	static CompletableFuture<ResourceLocation> importImage(UUID id, BufferedImage original, Path target) {
		if (original == null) return CompletableFuture.failedFuture(new IllegalArgumentException("请选择皮肤 PNG 图片。"));
		int width = original.getWidth(), height = original.getHeight();
		if (width < 64 || width > 4096 || width % 64 != 0 || !(height == width || height * 2 == width))
			return CompletableFuture.failedFuture(new IllegalArgumentException("皮肤需为 64×64 或 64×32 的整数倍，最大 4096×4096。"));
		BufferedImage owned = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D copy = owned.createGraphics(); copy.drawImage(original, 0, 0, null); copy.dispose();
		long expected = GENERATIONS.computeIfAbsent(id, ignored -> new AtomicLong()).incrementAndGet();
		CompletableFuture<ResourceLocation> result = new CompletableFuture<>();
		FILES.execute(() -> {
			NativeImage nativeImage = null; BufferedImage square = null;
			try {
				square = square(owned);
				synchronized(com.cheatprogram.core.Config.persistenceLock()) {
				if(!com.cheatprogram.core.Config.persistenceAllowed())throw new IllegalStateException("Restart required after initialization");
				Files.createDirectories(target.getParent()); Path pending = target.resolveSibling(target.getFileName() + ".tmp");
				if (!ImageIO.write(square, "PNG", pending.toFile())) throw new IllegalStateException();
				if (!current(id, expected)) { Files.deleteIfExists(pending); throw new IllegalStateException("皮肤导入已取消。"); }
				Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
				}
				nativeImage = new NativeImage(square.getWidth(), square.getHeight(), false);
				for (int y = 0; y < square.getHeight(); y++) {
					if (!current(id, expected)) throw new IllegalStateException("皮肤导入已取消。");
					for (int x = 0; x < square.getWidth(); x++) nativeImage.setPixel(x, y, square.getRGB(x, y));
				}
				NativeImage prepared = nativeImage; nativeImage = null; int resolution = square.getWidth();
				try { MC.execute(() -> {
					if (!current(id, expected)) { prepared.close(); result.completeExceptionally(new IllegalStateException("皮肤导入已取消。")); return; }
					ResourceLocation texture = ResourceLocation.fromNamespaceAndPath("cheatprogram", "dynamic/ai_skin/" + id);
					try {
						DynamicTexture dynamic = new DynamicTexture(prepared); dynamic.setFilter(resolution > 64, false);
						MC.getTextureManager().register(texture, dynamic); result.complete(texture);
					} catch (RuntimeException failure) { prepared.close(); result.completeExceptionally(new IllegalStateException("皮肤纹理上传失败。")); }
				}); } catch (RuntimeException failure) { prepared.close(); throw failure; }
			} catch (Exception failure) { result.completeExceptionally(new IllegalStateException("皮肤导入失败，请检查图片尺寸与文件权限。")); }
			finally { if (nativeImage != null) nativeImage.close(); if (square != null && square != owned) square.flush(); owned.flush(); }
		});
		return result;
	}
	static CompletableFuture<ResourceLocation> load(UUID id, Path target) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				if (!Files.isRegularFile(target) || Files.size(target) > 32L * 1024 * 1024) return null;
				return ImageIO.read(target.toFile());
			} catch (Exception ignored) { return null; }
		}, FILES).thenCompose(image -> {
			if (image == null) return CompletableFuture.completedFuture(null);
			CompletableFuture<ResourceLocation> result = importImage(id, image, target); image.flush(); return result;
		});
	}
	static void remove(UUID id, Path target) {
		GENERATIONS.computeIfAbsent(id, ignored -> new AtomicLong()).incrementAndGet();
		MC.getTextureManager().release(ResourceLocation.fromNamespaceAndPath("cheatprogram", "dynamic/ai_skin/" + id));
		FILES.execute(() -> { synchronized(com.cheatprogram.core.Config.persistenceLock()){if(!com.cheatprogram.core.Config.persistenceAllowed())return;try { Files.deleteIfExists(target); } catch (Exception ignored) { } } });
	}
	private static boolean current(UUID id, long expected) { return GENERATIONS.get(id).get() == expected; }
	private static BufferedImage square(BufferedImage original) {
		if (original.getWidth() == original.getHeight()) return original;
		int scale = original.getWidth() / 64;
		BufferedImage result = new BufferedImage(original.getWidth(), original.getWidth(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = result.createGraphics(); graphics.drawImage(original, 0, 0, null);
		// Legacy skins share right limbs. Mirror their six faces into the standard modern left limb locations.
		int[][] faces = {
				{20,48,24,52,8,16,4,20},{24,48,28,52,12,16,8,20},{16,52,20,64,12,20,8,32},
				{20,52,24,64,8,20,4,32},{24,52,28,64,4,20,0,32},{28,52,32,64,16,20,12,32},
				{36,48,40,52,48,16,44,20},{40,48,44,52,52,16,48,20},{32,52,36,64,52,20,48,32},
				{36,52,40,64,48,20,44,32},{40,52,44,64,44,20,40,32},{44,52,48,64,56,20,52,32}
		};
		for (int[] face : faces) graphics.drawImage(original, face[0]*scale, face[1]*scale, face[2]*scale, face[3]*scale,
				face[4]*scale, face[5]*scale, face[6]*scale, face[7]*scale, null);
		graphics.dispose(); return result;
	}
}
