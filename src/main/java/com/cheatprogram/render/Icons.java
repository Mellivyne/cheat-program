package com.cheatprogram.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/** Rasterizes {@link Icon}s at the exact pixel size they are drawn at, cached in the shared atlas. */
final class Icons {
	static final int PAD = 2;
	private static final Map<Long, Atlas.Region> CACHE = new HashMap<>();
	private static int generation = -1;

	private Icons() {
	}

	static void invalidate() {
		CACHE.clear();
	}

	static Atlas.Region region(Icon icon, int px) {
		Atlas atlas = Fonts.ATLAS;
		if (generation != atlas.generation) {
			CACHE.clear();
			generation = atlas.generation;
		}
		long key = ((long) icon.ordinal() << 32) | px;
		Atlas.Region region = CACHE.get(key);
		if (region == null) {
			BufferedImage image = new BufferedImage(px + PAD * 2, px + PAD * 2, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = image.createGraphics();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.translate(PAD, PAD);
			g.scale(px / 24.0, px / 24.0);
			float stroke = Math.max(2.0f, 1.4f * 24f / px);
			g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(Color.WHITE);
			icon.paint(g);
			g.dispose();
			region = atlas.add(image, 1f);
			if (region == null) {
				atlas.clear();
				CACHE.clear();
				generation = atlas.generation;
				region = atlas.add(image, 1f);
			}
			CACHE.put(key, region);
		}
		return region;
	}
}
