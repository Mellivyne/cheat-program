package com.cheatprogram.util;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.Properties;

/** A normalized crop of the original media, independent of the editor's small display texture. */
public record MediaCapeRecipe(int width, int height, int scale, boolean atlasImport, boolean mirrorBack,
                              Crop front, Crop back) {

    public record Crop(double x, double y, double width, double height) {
        public Crop {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)
                    || x < -0.00001 || y < -0.00001 || width <= 0 || height <= 0
                    || x + width > 1.00001 || y + height > 1.00001)
                throw new IllegalArgumentException("无效的披风裁剪区域");
        }
    }

    public MediaCapeRecipe {
        if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 16_000_000
                || scale < 1 || scale > CapeImages.MAX_SCALE || front == null || back == null)
            throw new IllegalArgumentException("无效的动态披风尺寸");
    }

    public int frameRate() {
        // At full HD only the used 22*s by 17*s cape UV rectangle needs CPU pixel conversion.
        return Math.max(8, Math.min(20, 12_000_000 / (22 * 17 * scale * scale)));
    }

    public BufferedImage atlas(BufferedImage source) {
        if (atlasImport) return resize(source, 64 * scale, 32 * scale);
        BufferedImage frontImage = face(source, front), backImage = mirrorBack ? null : face(source, back);
        try { return CapeImages.composeAtlas(frontImage, backImage); }
        finally { frontImage.flush(); if (backImage != null) backImage.flush(); }
    }

    private BufferedImage face(BufferedImage source, Crop crop) {
        BufferedImage result = new BufferedImage(10 * scale, 16 * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale > 1
                    ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            AffineTransform transform = new AffineTransform();
            transform.scale(result.getWidth() / (crop.width * source.getWidth()), result.getHeight() / (crop.height * source.getHeight()));
            transform.translate(-crop.x * source.getWidth(), -crop.y * source.getHeight());
            graphics.drawImage(source, transform, null);
        } finally { graphics.dispose(); }
        return result;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height) {
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = result.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, width > 64
                    ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        return result;
    }

    public Properties properties(String sourceName) {
        Properties result = new Properties();
        result.setProperty("version", "1"); result.setProperty("source", sourceName);
        result.setProperty("width", Integer.toString(width)); result.setProperty("height", Integer.toString(height));
        result.setProperty("scale", Integer.toString(scale)); result.setProperty("atlas", Boolean.toString(atlasImport));
        result.setProperty("mirror", Boolean.toString(mirrorBack));
        putCrop(result, "front", front); putCrop(result, "back", back);
        return result;
    }

    public static MediaCapeRecipe from(Properties properties) {
        if (!"1".equals(properties.getProperty("version"))) throw new IllegalArgumentException("不支持的披风配置版本");
        return new MediaCapeRecipe(Integer.parseInt(properties.getProperty("width")), Integer.parseInt(properties.getProperty("height")),
                Integer.parseInt(properties.getProperty("scale")), Boolean.parseBoolean(properties.getProperty("atlas")),
                Boolean.parseBoolean(properties.getProperty("mirror")), getCrop(properties, "front"), getCrop(properties, "back"));
    }

    private static void putCrop(Properties result, String name, Crop crop) {
        result.setProperty(name + ".x", Double.toString(crop.x)); result.setProperty(name + ".y", Double.toString(crop.y));
        result.setProperty(name + ".width", Double.toString(crop.width)); result.setProperty(name + ".height", Double.toString(crop.height));
    }
    private static Crop getCrop(Properties properties, String name) {
        return new Crop(Double.parseDouble(properties.getProperty(name + ".x")), Double.parseDouble(properties.getProperty(name + ".y")),
                Double.parseDouble(properties.getProperty(name + ".width")), Double.parseDouble(properties.getProperty(name + ".height")));
    }
}
