package com.cheatprogram.util;

import org.w3c.dom.Node;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Local media decoder. Each process is passed discrete arguments and owns a bounded pipe, never a shell command. */
public final class MediaCapeFiles {
    public static final long MAX_MEDIA_BYTES = 256L * 1024 * 1024;
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "Cheat Program cape decoder timeout"); thread.setDaemon(true); return thread;
    });
    private static volatile Path cachedFfmpeg;
    private MediaCapeFiles() { }

    public record Imported(Path file, BufferedImage firstFrame, boolean animated) { }
    public record Frame(BufferedImage image, long durationNanos) { }
    public interface Frames extends AutoCloseable {
        Frame next() throws IOException;
        default void cancel() { close(); }
        @Override void close();
    }

    public static final class DecoderUnavailable extends IOException {
        public DecoderUnavailable() { super("视频解码器不可用，请安装 FFmpeg；静态图片和 GIF 仍可用。"); }
    }

    public static Imported importFile(Path input) throws IOException {
        Path file = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file) || Files.size(file) == 0) throw new IOException("无法读取披风媒体文件。");
        String extension = extension(file);
        boolean media = List.of("gif", "apng", "webp", "mp4", "webm", "mov", "mkv", "avi", "m4v").contains(extension)
                || (extension.equals("png") && isAnimatedPng(file));
        if (Files.size(file) > (media ? MAX_MEDIA_BYTES : CapeImages.MAX_SAVED_BYTES)) throw new IOException("披风媒体文件过大。");
        if (!media) return new Imported(file, readStatic(file), false);
        Path decoder = ffmpeg();
        if (decoder != null) {
            try { return new Imported(file, firstFfmpegFrame(decoder, file), true); }
            catch (IOException failure) { if (!extension.equals("gif")) throw failure; }
        }
        if (!extension.equals("gif")) throw new DecoderUnavailable();
        try (Frames frames = new GifFrames(file)) { return new Imported(file, frames.next().image(), true); }
    }

    public static Frames open(Path file, MediaCapeRecipe recipe) throws IOException {
        Path decoder = ffmpeg();
        if (decoder != null) return new FfmpegFrames(decoder, file, recipe.width(), recipe.height(), recipe.frameRate());
        if (extension(file).equals("gif")) return new GifFrames(file);
        throw new DecoderUnavailable();
    }

    /** A bounded original-aspect stream for non-cape clients. No atlas conversion or audio output. */
    public static Frames openFrames(Path file, int width, int height, int frameRate) throws IOException {
        validate(width, height, 4096);
        if (frameRate < 1 || frameRate > 60) throw new IOException("无效的媒体帧率。");
        Path decoder = ffmpeg();
        // ImageIO keeps the GIF's individual frame durations, unlike fixed-fps video output.
        if (extension(file).equals("gif")) return new BoundedGifFrames(file, width, height);
        if (decoder != null) return new FfmpegFrames(decoder, file, width, height, frameRate);
        throw new DecoderUnavailable();
    }

    /** Read a bounded first frame for GUI media without allocating a full-resolution still image. */
    public static Imported importFile(Path input, int maximum) throws IOException {
        if (maximum < 1 || maximum > 4096) throw new IOException("无效的媒体预览尺寸。");
        Path file = input.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file) || Files.size(file) == 0 || Files.size(file) > MAX_MEDIA_BYTES)
            throw new IOException("无法读取媒体文件或文件过大。");
        String ext = extension(file);
        boolean animated = List.of("gif", "apng", "webp", "mp4", "webm", "mov", "mkv", "avi", "m4v").contains(ext)
                || (ext.equals("png") && isAnimatedPng(file));
        if (!animated) {
            try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
                Iterator<ImageReader> readers = stream == null ? java.util.Collections.emptyIterator() : ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw new IOException("不支持此图片格式。");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 32768 || height > 32768 || (long) width * height > 128L * 1024 * 1024)
                        throw new IOException("图片尺寸过大。");
                    var parameters = reader.getDefaultReadParam();
                    int sample = Math.max(1, (Math.max(width, height) + maximum - 1) / maximum);
                    parameters.setSourceSubsampling(sample, sample, 0, 0);
                    BufferedImage result = reader.read(0, parameters);
                    if (result == null) throw new IOException("图片无法解码。");
                    return new Imported(file, result, false);
                } finally { reader.dispose(); }
            }
        }
        if (ext.equals("gif")) {
            try (Frames frames = new GifFrames(file, maximum)) {
                return new Imported(file, bound(frames.next().image(), maximum), true);
            }
        }
        Path decoder = ffmpeg();
        if (decoder == null) throw new DecoderUnavailable();
        return new Imported(file, firstFfmpegFrame(decoder, file, maximum), true);
    }

    public static String extension(Path file) {
        String name = file.getFileName().toString(); int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static Path ffmpeg() {
        Path cached = cachedFfmpeg;
        if (cached != null && Files.isRegularFile(cached)) return cached;
        List<Path> candidates = new ArrayList<>();
        String override = System.getenv("FFMPEG_PATH");
        if (override != null && !override.isBlank()) try { candidates.add(Path.of(override)); } catch (RuntimeException ignored) { }
        String search = System.getenv("PATH");
        if (search != null) for (String entry : search.split(java.io.File.pathSeparator)) {
            try { if (!entry.isBlank()) candidates.add(Path.of(entry.replace("\"", "")).resolve("ffmpeg.exe")); }
            catch (RuntimeException ignored) { }
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null) {
            candidates.add(Path.of(local, "Microsoft", "WinGet", "Links", "ffmpeg.exe"));
            Path packages = Path.of(local, "Microsoft", "WinGet", "Packages");
            if (Files.isDirectory(packages)) try (var files = Files.walk(packages, 4)) {
                files.filter(path -> path.getFileName().toString().equalsIgnoreCase("ffmpeg.exe")).limit(8).forEach(candidates::add);
            } catch (IOException ignored) { }
        }
        for (Path path : candidates) if (Files.isRegularFile(path)) { cachedFfmpeg = path.toAbsolutePath(); return cachedFfmpeg; }
        return null;
    }

    private static BufferedImage readStatic(Path file) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) throw new IOException("无法读取披风图片。");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("不支持此图片格式。");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                validate(reader.getWidth(0), reader.getHeight(0), 8192);
                BufferedImage image = reader.read(0);
                if (image == null) throw new IOException("无法读取披风图片。");
                return image;
            } finally { reader.dispose(); }
        }
    }

    private static boolean isAnimatedPng(Path file) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            if (input.readLong() != 0x89504e470d0a1a0aL) return false;
            long traversed = 8;
            while (traversed < 1024 * 1024) {
                int length = input.readInt(), type = input.readInt(); traversed += 12L + Math.max(length, 0);
                if (length < 0 || length > 1024 * 1024) return false;
                if (type == 0x6163544c) return true; // acTL always precedes IDAT in APNG.
                if (type == 0x49444154 || type == 0x49454e44) return false;
                input.skipNBytes((long) length + 4);
            }
            return false;
        }
    }

    private static BufferedImage firstFfmpegFrame(Path decoder, Path file) throws IOException {
        return firstFfmpegFrame(decoder, file, 4096);
    }
    private static BufferedImage firstFfmpegFrame(Path decoder, Path file, int maximum) throws IOException {
        List<String> arguments = baseArguments(decoder);
        arguments.addAll(List.of("-i", file.toString(), "-map", "0:v:0", "-an", "-sn", "-dn", "-vf",
                "scale=w='min(" + maximum + ",iw)':h='min(" + maximum + ",ih)':force_original_aspect_ratio=decrease",
                "-frames:v", "1", "-c:v", "png", "-f", "image2pipe", "pipe:1"));
        Process process = process(arguments);
        var timeout = TIMEOUTS.schedule(process::destroyForcibly, 15, TimeUnit.SECONDS);
        try (InputStream input = process.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192]; int count;
            while ((count = input.read(block)) != -1) {
                if ((long) bytes.size() + count > CapeImages.MAX_SAVED_BYTES) throw new IOException("媒体首帧过大。");
                bytes.write(block, 0, count);
            }
            try (ImageInputStream imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                Iterator<ImageReader> readers = imageInput == null ? java.util.Collections.emptyIterator() : ImageIO.getImageReaders(imageInput);
                if (!readers.hasNext()) throw new IOException("媒体解码失败，请检查文件格式或 FFmpeg。");
                ImageReader reader = readers.next();
                try { reader.setInput(imageInput, true, true); validate(reader.getWidth(0), reader.getHeight(0), 4096); return reader.read(0); }
                finally { reader.dispose(); }
            }
        } finally { timeout.cancel(false); process.destroyForcibly(); }
    }

    private static List<String> baseArguments(Path decoder) {
        return new ArrayList<>(List.of(decoder.toString(), "-nostdin", "-hide_banner", "-loglevel", "error", "-threads", "2",
                "-protocol_whitelist", "file,pipe", "-filter_threads", "1"));
    }
    private static Process process(List<String> arguments) throws IOException {
        return new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
    private static void validate(int width, int height, int maximum) throws IOException {
        if (width < 1 || height < 1 || width > maximum || height > maximum || (long) width * height > 16_000_000)
            throw new IOException("披风图片尺寸过大。");
    }

    private static BufferedImage bound(BufferedImage original, int maximum) {
        if (Math.max(original.getWidth(), original.getHeight()) <= maximum) return original;
        double ratio = (double) maximum / Math.max(original.getWidth(), original.getHeight());
        return resize(original, Math.max(1, (int) Math.round(original.getWidth() * ratio)), Math.max(1, (int) Math.round(original.getHeight() * ratio)));
    }
    private static BufferedImage resize(BufferedImage original, int width, int height) {
        if (original.getWidth() == width && original.getHeight() == height) return original;
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = resized.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(original, 0, 0, width, height, null);
        } finally { graphics.dispose(); original.flush(); }
        return resized;
    }

    private static final class BoundedGifFrames implements Frames {
        private final GifFrames source;
        private final int width, height;
        BoundedGifFrames(Path file, int width, int height) throws IOException { this.source = new GifFrames(file, Math.max(width, height)); this.width = width; this.height = height; }
        @Override public Frame next() throws IOException {
            Frame frame = source.next();
            return new Frame(resize(frame.image(), width, height), frame.durationNanos());
        }
        @Override public void cancel() { source.cancel(); }
        @Override public void close() { source.close(); }
    }

    private static final class FfmpegFrames implements Frames {
        private final Process process;
        private final InputStream input;
        private final int width, height;
        private final byte[] bytes;
        private final long duration;
        private volatile boolean closed;
        FfmpegFrames(Path decoder, Path file, int width, int height, int frameRate) throws IOException {
            this.width = width; this.height = height; this.duration = 1_000_000_000L / frameRate;
            bytes = new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)];
            List<String> arguments = baseArguments(decoder);
            arguments.addAll(List.of("-stream_loop", "-1", "-i", file.toString(), "-map", "0:v:0", "-an", "-sn", "-dn",
                    "-vf", "fps=" + frameRate + ",scale=" + width + ":" + height + ":flags=bilinear",
                    "-pix_fmt", "rgba", "-c:v", "rawvideo", "-f", "rawvideo", "pipe:1"));
            process = process(arguments); input = process.getInputStream();
        }
        @Override public Frame next() throws IOException {
            int offset = 0;
            while (offset < bytes.length && !closed) {
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) throw new IOException("动态披风解码结束或失败。");
                offset += count;
            }
            if (closed) throw new IOException("动态披风播放已暂停。");
            BufferedImage frame = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            int[] pixels = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
            for (int i = 0, j = 0; i < pixels.length; i++, j += 4)
                pixels[i] = (bytes[j + 3] & 255) << 24 | (bytes[j] & 255) << 16 | (bytes[j + 1] & 255) << 8 | bytes[j + 2] & 255;
            return new Frame(frame, duration);
        }
        @Override public void close() {
            closed = true; process.destroyForcibly();
            try { input.close(); } catch (IOException ignored) { }
        }
    }

    /** ImageIO GIF fallback composites image rectangles and honours both restore disposal modes and frame delays. */
    private static final class GifFrames implements Frames {
        private final ImageInputStream input;
        private final ImageReader reader;
        private final BufferedImage canvas;
        private final int count;
        private final int backgroundIndex, backgroundArgb;
        private final double coordinateScale;
        private final int maximum;
        private int index, lastX, lastY, lastW, lastH;
        private String disposal = "none";
        private boolean transparentBackground;
        private BufferedImage previous;
        private volatile boolean closed;
        GifFrames(Path file) throws IOException { this(file, 4096); }
        GifFrames(Path file, int maximum) throws IOException {
            this.maximum = maximum;
            input = ImageIO.createImageInputStream(file.toFile());
            if (input == null) throw new IOException("无法读取 GIF。");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) { input.close(); throw new IOException("GIF 解码器不可用。"); }
            reader = readers.next();
            try {
                reader.setInput(input, false, false); count = reader.getNumImages(true);
                Node metadata = tree(reader.getStreamMetadata(), "javax_imageio_gif_stream_1.0");
                Node size = find(metadata, "LogicalScreenDescriptor");
                int width = attributeInt(size, "logicalScreenWidth", reader.getWidth(0));
                int height = attributeInt(size, "logicalScreenHeight", reader.getHeight(0));
                Node table = find(metadata, "GlobalColorTable");
                backgroundIndex = attributeInt(table, "backgroundColorIndex", -1);
                int background = 0;
                if (table != null) for (Node entry = table.getFirstChild(); entry != null; entry = entry.getNextSibling()) {
                    if (attributeInt(entry, "index", -2) == backgroundIndex) {
                        background = 0xff000000 | attributeInt(entry, "red", 0) << 16 | attributeInt(entry, "green", 0) << 8 | attributeInt(entry, "blue", 0); break;
                    }
                }
                backgroundArgb = background;
                validate(width, height, 4096);
                if (count < 1) throw new IOException("GIF 不含图像。");
                coordinateScale = Math.min(1, (double) maximum / Math.max(width, height));
                canvas = new BufferedImage(Math.max(1, scaled(width)), Math.max(1, scaled(height)), BufferedImage.TYPE_INT_ARGB);
            } catch (IOException | RuntimeException error) { reader.dispose(); input.close(); throw error; }
        }
        @Override public Frame next() throws IOException {
            if (closed) throw new IOException("GIF 播放已暂停。");
            Graphics2D graphics = canvas.createGraphics();
            BufferedImage patch = null;
            try {
                Node metadata = tree(reader.getImageMetadata(index), "javax_imageio_gif_image_1.0");
                Node descriptor = find(metadata, "ImageDescriptor"), control = find(metadata, "GraphicControlExtension");
                boolean nextTransparentBackground = Boolean.parseBoolean(attribute(control, "transparentColorFlag", "FALSE"))
                        && attributeInt(control, "transparentColorIndex", -1) == backgroundIndex;
                if (index == 0) { background(graphics, 0, 0, canvas.getWidth(), canvas.getHeight(), nextTransparentBackground); }
                else if (disposal.equals("restoreToBackgroundColor")) {
                    background(graphics, lastX, lastY, lastW, lastH, transparentBackground);
                } else if (disposal.equals("restoreToPrevious") && previous != null) {
                    graphics.setComposite(AlphaComposite.Src); graphics.drawImage(previous, 0, 0, null);
                }
                if (previous != null) { previous.flush(); previous = null; }
                transparentBackground = nextTransparentBackground;
                int rawX = attributeInt(descriptor, "imageLeftPosition", 0), rawY = attributeInt(descriptor, "imageTopPosition", 0);
                int rawW = attributeInt(descriptor, "imageWidth", reader.getWidth(index)), rawH = attributeInt(descriptor, "imageHeight", reader.getHeight(index));
                lastX = scaled(rawX); lastY = scaled(rawY);
                lastW = Math.max(1, scaled(rawX + rawW) - lastX); lastH = Math.max(1, scaled(rawY + rawH) - lastY);
                disposal = attribute(control, "disposalMethod", "none");
                if (disposal.equals("restoreToPrevious")) previous = copy(canvas);
                var parameters = reader.getDefaultReadParam();
                int sample = Math.max(1, (Math.max(reader.getWidth(index), reader.getHeight(index)) + maximum - 1) / maximum);
                parameters.setSourceSubsampling(sample, sample, 0, 0);
                patch = reader.read(index, parameters); graphics.setComposite(AlphaComposite.SrcOver);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(patch, lastX, lastY, lastW, lastH, null);
                int delay = attributeInt(control, "delayTime", 10); index = (index + 1) % count;
                return new Frame(copy(canvas), Math.max(10, delay > 0 ? delay * 10L : 100L) * 1_000_000);
            } finally { graphics.dispose(); if (patch != null) patch.flush(); }
        }
        @Override public void close() {
            closed = true; reader.dispose(); try { input.close(); } catch (IOException ignored) { }
            canvas.flush(); if (previous != null) previous.flush();
        }
        @Override public void cancel() { closed = true; } // Reader ownership stays with the decode worker until its finally block.
        private int scaled(int coordinate) { return (int) Math.round(coordinate * coordinateScale); }
        private void background(Graphics2D graphics, int x, int y, int width, int height, boolean transparent) {
            graphics.setComposite(transparent || backgroundArgb == 0 ? AlphaComposite.Clear : AlphaComposite.Src);
            graphics.setColor(new Color(backgroundArgb, true)); graphics.fillRect(x, y, width, height);
        }
        private static BufferedImage copy(BufferedImage image) {
            BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = result.createGraphics();
            try { graphics.setComposite(AlphaComposite.Src); graphics.drawImage(image, 0, 0, null); } finally { graphics.dispose(); }
            return result;
        }
        private static Node tree(IIOMetadata metadata, String name) { return metadata == null ? null : metadata.getAsTree(name); }
        private static Node find(Node root, String name) {
            if (root == null) return null;
            if (root.getNodeName().equals(name)) return root;
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) { Node result = find(child, name); if (result != null) return result; }
            return null;
        }
        private static String attribute(Node node, String name, String fallback) {
            Node value = node == null || node.getAttributes() == null ? null : node.getAttributes().getNamedItem(name);
            return value == null ? fallback : value.getNodeValue();
        }
        private static int attributeInt(Node node, String name, int fallback) {
            try { return Integer.parseInt(attribute(node, name, Integer.toString(fallback))); } catch (NumberFormatException ignored) { return fallback; }
        }
    }
}
