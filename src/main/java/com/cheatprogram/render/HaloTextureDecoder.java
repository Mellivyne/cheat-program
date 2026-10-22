package com.cheatprogram.render;

import com.mojang.blaze3d.platform.NativeImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Header inspection and subsampled PNG decoding; no texture/GPU operations occur here. */
public final class HaloTextureDecoder {
	private static final long MAX_ENCODED_BYTES = 16L * 1024 * 1024;
	private static final long MAX_ORIGINAL_PIXELS = 256L * 1024 * 1024;
	private static final int MAX_ORIGINAL_SIDE = 65536;
	public record Size(int width, int height) { }
	private HaloTextureDecoder() { }

	/** ImageReader obtains the dimensions without allocating a complete original bitmap. */
	public static Size inspect(ZipFile zip, ZipEntry entry) throws IOException {
		checkEntry(entry);
		try (InputStream input = new BoundedInputStream(zip.getInputStream(entry));
			 MemoryCacheImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
			ImageReader reader = reader(imageInput);
			try {
				reader.setInput(imageInput, true, true);
				return checkedSize(reader.getWidth(0), reader.getHeight(0));
			} finally { reader.dispose(); }
		} catch (RuntimeException error) { throw new IOException("Invalid halo PNG header", error); }
	}

	/** Decode directly at the requested sampling step, retaining source RGB and alpha. */
	public static NativeImage decode(ZipFile zip, ZipEntry entry, int sampleStep) throws IOException {
		checkEntry(entry);
		if (sampleStep < 1) throw new IOException("Halo texture sampling step must be positive");
		NativeImage output = null;
		boolean completed = false;
		BufferedImage decoded = null;
		try {
			try (InputStream input = new BoundedInputStream(zip.getInputStream(entry));
				 MemoryCacheImageInputStream imageInput = new MemoryCacheImageInputStream(input)) {
				ImageReader reader = reader(imageInput);
				try {
					reader.setInput(imageInput, true, true);
					checkedSize(reader.getWidth(0), reader.getHeight(0));
					ImageReadParam parameters = reader.getDefaultReadParam();
					parameters.setSourceSubsampling(sampleStep, sampleStep, 0, 0);
					decoded = reader.read(0, parameters);
					if (decoded == null || decoded.getWidth() < 1 || decoded.getHeight() < 1) throw new IOException("Halo PNG decode returned no pixels");
					output = new NativeImage(decoded.getWidth(), decoded.getHeight(), false);
					int[] row = new int[decoded.getWidth()];
					for (int y = 0; y < decoded.getHeight(); y++) {
						decoded.getRGB(0, y, decoded.getWidth(), 1, row, 0, decoded.getWidth());
						for (int x = 0; x < row.length; x++) output.setPixel(x, y, row[x]);
					}
				} finally { reader.dispose(); }
			}
			completed = true;
			return output;
		} catch (RuntimeException error) { throw new IOException("Unable to decode halo PNG", error); }
		finally {
			if (decoded != null) decoded.flush();
			if (!completed && output != null) output.close();
		}
	}

	private static void checkEntry(ZipEntry entry) throws IOException {
		if (entry == null || entry.isDirectory()) throw new IOException("Halo texture entry is missing");
		if (entry.getSize() > MAX_ENCODED_BYTES) throw new IOException("Halo PNG encoded size exceeded");
	}
	private static Size checkedSize(int width, int height) throws IOException {
		if (width < 1 || height < 1 || width > MAX_ORIGINAL_SIDE || height > MAX_ORIGINAL_SIDE
				|| (long)width * height > MAX_ORIGINAL_PIXELS) throw new IOException("Invalid halo PNG dimensions");
		return new Size(width, height);
	}
	private static ImageReader reader(MemoryCacheImageInputStream input) throws IOException {
		Iterator<ImageReader> candidates = ImageIO.getImageReaders(input);
		while (candidates.hasNext()) {
			ImageReader reader = candidates.next();
			if ("PNG".equalsIgnoreCase(reader.getFormatName())) return reader;
			reader.dispose();
		}
		throw new IOException("Halo texture is not a supported PNG");
	}
	/** Enforces the encoded-file limit even for archives whose entry size is unknown. */
	private static final class BoundedInputStream extends FilterInputStream {
		private long read;
		BoundedInputStream(InputStream input) { super(input); }
		private void count(long bytes) throws IOException {
			if (bytes > 0 && (read += bytes) > MAX_ENCODED_BYTES) throw new IOException("Halo PNG encoded size exceeded");
		}
		@Override public int read() throws IOException { int value = in.read(); if (value >= 0) count(1); return value; }
		@Override public int read(byte[] bytes, int offset, int length) throws IOException {
			int amount = in.read(bytes, offset, (int)Math.min(length, MAX_ENCODED_BYTES - read + 1));
			count(amount); return amount;
		}
		@Override public long skip(long amount) throws IOException {
			long skipped = in.skip(Math.min(amount, MAX_ENCODED_BYTES - read + 1)); count(skipped); return skipped;
		}
	}
}
