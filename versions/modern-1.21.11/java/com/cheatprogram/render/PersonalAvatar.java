package com.cheatprogram.render;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.visual.Personal;
import com.cheatprogram.util.Anim;
import com.mojang.blaze3d.platform.NativeImage;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.cheatprogram.compat.gl.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** One independently owned high-resolution avatar texture; original media is never rewritten. */
public final class PersonalAvatar {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final Shaders.Program SHADER = new Shaders.Program("personal_avatar", DefaultVertexFormat.POSITION_TEX_COLOR);
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Personal avatar files"); thread.setDaemon(true); return thread;
    });
    private static final ExecutorService PICKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Personal avatar picker"); thread.setDaemon(true); return thread;
    });
    private static final AtomicLong GENERATION = new AtomicLong();
    private static String requested = "";
    private static Identifier texture;
    private static int imageWidth = 1, imageHeight = 1;
    private static volatile String statusKey = "ui.personal.avatarDefault";
    private static volatile boolean choosing;
    private PersonalAvatar() { }

    /** Caller owns pixels until uploaded. Full source edges are retained; only decoding is sampled. */
    public record ImageData(Path file, NativeImage pixels, int width, int height) { }
    public static ImageData decodeImage(Path requested) throws IOException {
        Path file = requested.toAbsolutePath().normalize();
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif"))
                || !Files.isRegularFile(file) || Files.size(file) < 1 || Files.size(file) > 32L * 1024 * 1024)
            throw new IOException("Unsupported or unavailable avatar image");
        NativeImage pixels = null;
        BufferedImage image = null;
        try (var input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) throw new IOException("Image stream is unavailable");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Image decoder is unavailable");
            var reader = readers.next();
            int width, height;
            try {
                reader.setInput(input, true, true);
                width = reader.getWidth(0); height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 65536 || height > 65536 || (long) width * height > 268435456L)
                    throw new IOException("Image dimensions are invalid");
                var parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, (Math.max(width, height) + 2047) / 2048);
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                image = reader.read(0, parameters);
                if (image == null) throw new IOException("Image decoder returned no pixels");
            } finally { reader.dispose(); }
            pixels = new NativeImage(image.getWidth(), image.getHeight(), false);
            int[] row = new int[image.getWidth()];
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, row.length, 1, row, 0, row.length);
                for (int x = 0; x < row.length; x++) pixels.setPixel(x, y, row[x]);
            }
            return new ImageData(file, pixels, width, height);
        } catch (IOException | RuntimeException | OutOfMemoryError failed) {
            if (pixels != null) pixels.close(); throw failed;
        } finally { if (image != null) image.flush(); }
    }

    /** Must be called on the client/render thread. Decoding remains on the file worker. */
    public static void warm(String path) {
        String value = path == null ? "" : path.strip();
        if (value.equals(requested)) return;
        requested = value; long token = GENERATION.incrementAndGet(); discardTexture();
        if (value.isBlank()) { statusKey = "ui.personal.avatarDefault"; return; }
        statusKey = "ui.personal.avatarLoading";
        FILES.execute(() -> {
            try {
                ImageData decoded = decodeImage(Path.of(value));
                MC.execute(() -> upload(decoded, token));
            } catch (Exception | OutOfMemoryError failed) {
                MC.execute(() -> { if (GENERATION.get() == token) statusKey = "ui.personal.avatarError"; });
            }
        });
    }
    private static void upload(ImageData decoded, long token) {
        NativeImage pixels = decoded.pixels();
        if (GENERATION.get() != token) { pixels.close(); return; }
        Identifier id = Identifier.fromNamespaceAndPath("cheatprogram", "dynamic/personal_avatar/" + token);
        DynamicTexture image = null;
        try {
            image = new DynamicTexture(() -> "Cheat Program texture", pixels); com.cheatprogram.compat.gl.RenderSystem.setTextureFilter(image, true, false);
            MC.getTextureManager().register(id, image); texture = id;
            imageWidth = decoded.width(); imageHeight = decoded.height(); statusKey = "ui.personal.avatarReady";
        } catch (RuntimeException failed) {
            if (image != null) image.close(); else pixels.close();
            statusKey = "ui.personal.avatarError";
        }
    }
    private static void discardTexture() {
        Identifier prior = texture; texture = null;
        if (prior != null) MC.getTextureManager().release(prior);
    }
    public static void release() {
        GENERATION.incrementAndGet(); requested = ""; discardTexture(); statusKey = "ui.personal.avatarDefault";
    }
    public static void retry(String path) { requested = "\u0000"; warm(path); }
    /** Optional global tick hook: preload profile changes before a welcome card first appears. */
    public static void tickLifecycle() {
        Personal module = ModuleManager.get(Personal.class);
        warm(module == null ? "" : module.avatarPath());
    }
    public static String statusKey() { return statusKey; }
    public static void choose(String suggested, Consumer<Path> selected, Runnable failed) {
        if (choosing) return; choosing = true;
        PICKER.execute(() -> {
            String result = null; boolean error = false;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var filters = stack.mallocPointer(4);
                for (String extension : new String[]{"*.png", "*.jpg", "*.jpeg", "*.gif"}) filters.put(stack.UTF8(extension));
                filters.flip();
                result = TinyFileDialogs.tinyfd_openFileDialog(Lang.t("ui.personal.avatarChoose"),
                        suggested == null || suggested.isBlank() ? null : suggested, filters, "PNG / JPG / GIF", false);
            } catch (Throwable unavailable) { error = true; }
            String value = result; boolean unavailable = error;
            MC.execute(() -> {
                choosing = false;
                if (unavailable) { if (failed != null) failed.run(); }
                else if (value != null) try { selected.accept(Path.of(value)); } catch (RuntimeException badPath) { if (failed != null) failed.run(); }
            });
        });
    }
    public static void draw(Canvas c, float x, float y, float width, float height, float radius, float alpha) {
        drawFlipped(c, x, y, width, height, radius, alpha, 0);
    }
    /** Front face only; 16 perspective strips preserve the circular/rounded local image mask. */
    public static void drawFlipped(Canvas c, float x, float y, float width, float height, float radius, float alpha, float yawDegrees) {
        Personal module = ModuleManager.get(Personal.class);
        warm(module == null ? "" : module.avatarPath());
        drawImage(c, texture, imageWidth, imageHeight, module != null && module.hasAvatarCrop() ? module.avatarCrop() : legacyCrop(imageWidth, imageHeight),
                x, y, width, height, radius, alpha, yawDegrees);
    }
    /** Old profiles were decoded as a central square before any output aspect was applied. */
    public static Personal.AvatarCrop legacyCrop(int width, int height) {
        double side = Math.min(Math.max(1, width), Math.max(1, height));
        double u = side / Math.max(1, width), v = side / Math.max(1, height);
        return new Personal.AvatarCrop((1 - u) / 2, (1 - v) / 2, u, v, false);
    }
    /** Also used by the editor with its separately owned texture; no live profile mutation. */
    public static void drawImage(Canvas c, Identifier image, int sourceWidth, int sourceHeight, Personal.AvatarCrop selection,
                                 float x, float y, float width, float height, float radius, float alpha, float yawDegrees) {
        if (width <= 0 || height <= 0 || alpha <= 0) return;
        float angle = Float.isFinite(yawDegrees) ? yawDegrees : 0;
        double radians = Math.toRadians(angle), cosine = Math.cos(radians), sine = Math.sin(radians);
        if (cosine <= .001) return;
        R2D.pushAlpha(alpha);
        try {
            var shader = image == null ? null : Shaders.get(SHADER);
            if (shader == null) {
                float squashed = width * (float) cosine;
                c.rrect(x + (width - squashed) / 2, y, squashed, height, Math.min(radius, squashed / 2), Anim.white(.10f));
                if (cosine > .65) c.icon(Icon.USER, x + width / 2, y + height / 2, Math.min(squashed, height) * .48f, Anim.white(.9f));
                return;
            }
            float w = width * c.scale, h = height * c.scale;
            Personal.AvatarCrop crop = selection == null ? Personal.AvatarCrop.FULL : selection;
            double u = crop.width(), v = crop.height();
            if (!crop.stretch()) {
                double sourceAspect = Math.max(1, sourceWidth) * u / (Math.max(1, sourceHeight) * v);
                double outputAspect = width / height;
                if (sourceAspect > outputAspect) u = v * Math.max(1, sourceHeight) * outputAspect / Math.max(1, sourceWidth);
                else v = u * Math.max(1, sourceWidth) / (outputAspect * Math.max(1, sourceHeight));
            }
            RenderSystem.setShader(shader); RenderSystem.setShaderTexture(0, image);
            shader.safeGetUniform("Size").set(w, h);
            shader.safeGetUniform("Radius").set(Math.max(0, radius * c.scale));
            shader.safeGetUniform("UVRect").set((float) (crop.x() + (crop.width() - u) / 2),
                    (float) (crop.y() + (crop.height() - v) / 2), (float) u, (float) v);
            shader.safeGetUniform("Opacity").set(R2D.alpha());
            RenderSystem.setShaderColor(1, 1, 1, 1);
            float scale = (float) MC.getWindow().getGuiScale();
            BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
            int strips = Math.abs(angle) < .01 ? 1 : 16;
            for (int i = 0; i < strips; i++) {
                float left = w * i / strips, right = w * (i + 1) / strips;
                vertex(quad, c.px(x), c.py(y), w, h, left, 0, cosine, sine, scale);
                vertex(quad, c.px(x), c.py(y), w, h, left, h, cosine, sine, scale);
                vertex(quad, c.px(x), c.py(y), w, h, right, h, cosine, sine, scale);
                vertex(quad, c.px(x), c.py(y), w, h, right, 0, cosine, sine, scale);
            }
            BufferUploader.drawWithShader(quad.buildOrThrow());
        } finally { RenderSystem.setShaderColor(1, 1, 1, 1); R2D.popAlpha(); }
    }
    private static void vertex(BufferBuilder quad, float x, float y, float width, float height, float u, float v,
                               double cosine, double sine, float guiScale) {
        double local = u - width / 2, distance = Math.max(width, height) * 4;
        double factor = distance / (distance + local * sine);
        float px = x + width / 2 + (float) (local * cosine * factor);
        float py = y + height / 2 + (float) ((v - height / 2) * factor);
        quad.addVertex(px / guiScale, py / guiScale, 0).setUv(u, v).setColor(0xFFFFFFFF);
    }
}
