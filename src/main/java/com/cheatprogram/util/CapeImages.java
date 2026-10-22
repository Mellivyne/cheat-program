package com.cheatprogram.util;

import java.awt.image.BufferedImage;

/** The 10x16x1 PlayerCapeModel UV layout, not the player's skin or head overlay layout. */
public final class CapeImages {
	public static final int ATLAS_WIDTH = 64, ATLAS_HEIGHT = 32, FACE_WIDTH = 10, FACE_HEIGHT = 16;
	public static final int MAX_SCALE = 64;
	public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
	public static final long MAX_SAVED_BYTES = 32L * 1024 * 1024;
	public static final int MAX_SOURCE_DIMENSION = 4096;
	public static final long MAX_SOURCE_PIXELS = 16L * 1024 * 1024;
	private CapeImages() { }

	/** Keeps every pixel of a cropped 10*s by 16*s face; a missing back mirrors the same-resolution face. */
	public static BufferedImage composeAtlas(BufferedImage front, BufferedImage back) {
		int scale = faceScale(front);
		if (back != null && faceScale(back) != scale) throw new IllegalArgumentException("披风正反面必须使用相同的清晰度尺寸。");
		int width = FACE_WIDTH * scale, height = FACE_HEIGHT * scale;
		BufferedImage atlas = new BufferedImage(ATLAS_WIDTH * scale, ATLAS_HEIGHT * scale, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				atlas.setRGB(scale + x, scale + y, front.getRGB(x, y));
				atlas.setRGB(12 * scale + x, scale + y, back == null ? front.getRGB(width - 1 - x, y) : back.getRGB(x, y));
			}
			for (int depth = 0; depth < scale; depth++) {
				atlas.setRGB(depth, scale + y, front.getRGB(0, y));
				atlas.setRGB(11 * scale + depth, scale + y, front.getRGB(width - 1, y));
			}
		}
		for (int x = 0; x < width; x++) {
			for (int depth = 0; depth < scale; depth++) {
				atlas.setRGB(scale + x, depth, front.getRGB(x, 0));
				atlas.setRGB(11 * scale + x, depth, front.getRGB(x, height - 1));
			}
		}
		return atlas; // Every unused texel stays transparent; face and edge alpha are preserved exactly.
	}

	public static BufferedImage composeAtlas(BufferedImage front) { return composeAtlas(front, null); }

	public static BufferedImage copyAtlas(BufferedImage atlas) {
		if (atlas == null || atlasScale(atlas.getWidth(), atlas.getHeight()) == 0)
			throw new IllegalArgumentException("披风图集必须是 64×32 的整数倍，最高支持 4096×2048。");
		int width = atlas.getWidth(), height = atlas.getHeight();
		BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		int[] row = new int[width];
		for (int y = 0; y < height; y++) {
			atlas.getRGB(0, y, width, 1, row, 0, width);
			copy.setRGB(0, y, width, 1, row, 0, width);
		}
		return copy;
	}

	/** Zero means an ordinary image or invalid/over-budget atlas; callers can detect imports without an exception. */
	public static int atlasScale(int width, int height) {
		if (width < ATLAS_WIDTH || height < ATLAS_HEIGHT || width > ATLAS_WIDTH * MAX_SCALE || height > ATLAS_HEIGHT * MAX_SCALE
				|| width % ATLAS_WIDTH != 0 || height % ATLAS_HEIGHT != 0) return 0;
		int scale = width / ATLAS_WIDTH;
		return height / ATLAS_HEIGHT == scale ? scale : 0;
	}

	private static int faceScale(BufferedImage image) {
		if (image == null) throw new IllegalArgumentException("请选择披风图片。");
		int width = image.getWidth(), height = image.getHeight();
		if (width < FACE_WIDTH || height < FACE_HEIGHT || width % FACE_WIDTH != 0 || height % FACE_HEIGHT != 0
				|| width / FACE_WIDTH != height / FACE_HEIGHT || width / FACE_WIDTH > MAX_SCALE)
			throw new IllegalArgumentException("披风正反面必须按 10×16 等比例裁剪，最高支持 640×1024。");
		return width / FACE_WIDTH;
	}
}
