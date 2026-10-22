package com.cheatprogram.render;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

/**
 * Smooth text rendered from system fonts through Java2D into the shared atlas.
 * Noto Sans SC is preferred (it is what the reference design uses); Microsoft YaHei and
 * other CJK fonts are used when it is missing.
 */
public final class Fonts {
	public enum Face {
		REGULAR, MEDIUM, BOLD,
		/** Heavy display face for the wordmark. */
		BLACK,
		/** Light display face for the wordmark. */
		LIGHT
	}

	static final Atlas ATLAS = new Atlas();
	private static final FontRenderContext FRC = new FontRenderContext(new AffineTransform(), true, true);
	private static final int PAD = 2;
	private static final float GAMMA = 0.82f;

	private static final Map<Face, Font> BASE = new EnumMap<>(Face.class);
	private static final Map<Long, Font> DERIVED = new HashMap<>();
	private static final Map<Long, Glyph> GLYPHS = new HashMap<>();
	private static final Map<Face, List<Font>> FALLBACKS = new EnumMap<>(Face.class);
	private static Set<String> families;
	private static String configuration = "";
	private static long revision;
	private static boolean atlasDirty;
	private static Font imported;
	private static String importedPath = "";
	private static String errorKey = "";

	static final class Glyph {
		final float advance;
		final int offsetX;
		final int offsetY;
		final BufferedImage bitmap;
		final String vanilla;
		Atlas.Region region;
		int generation = -1;

		Glyph(float advance, int offsetX, int offsetY, BufferedImage bitmap) {
			this(advance, offsetX, offsetY, bitmap, null);
		}

		Glyph(float advance, int offsetX, int offsetY, BufferedImage bitmap, String vanilla) {
			this.advance = advance;
			this.offsetX = offsetX;
			this.offsetY = offsetY;
			this.bitmap = bitmap;
			this.vanilla = vanilla;
		}
	}

	private Fonts() {
	}

	private static Boolean available;

	/** Polling also catches configuration loads which bypass Setting.set. No GL work here. */
	public static long revision() {
		ClientSettings settings = ClientSettings.INSTANCE;
		String value = settings == null ? "smooth|||" + Lang.language() : settings.fontMode.get() + "|" + settings.fontFamily.get() + "|" + settings.fontPath.get() + "|" + Lang.language();
		if (!configuration.equals(value)) {
			configuration = value;
			BASE.clear(); DERIVED.clear(); GLYPHS.clear(); FALLBACKS.clear();
			imported = null; importedPath = ""; errorKey = "";
			atlasDirty = true; revision++;
		}
		return revision;
	}

	public static boolean useVanilla() {
		revision();
		return ClientSettings.INSTANCE != null && ClientSettings.INSTANCE.fontMode.is("vanilla") || !available();
	}

	public static Component vanillaComponent(Face face, String text) {
		return face == Face.BOLD || face == Face.BLACK ? Component.literal(text).withStyle(ChatFormatting.BOLD) : Component.literal(text);
	}

	public static float vanillaWidth(Face face, String text, float sizePx) {
		return net.minecraft.client.Minecraft.getInstance().font.width(vanillaComponent(face, text)) * pixelSize(sizePx) / 9f;
	}

	/** A localized key describing an invalid custom selection; empty when usable. */
	public static String errorKey() { revision(); if (!useVanilla()) base(Face.REGULAR); return errorKey; }

	public static List<String> systemFamilies() {
		try { return Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()).sorted(String.CASE_INSENSITIVE_ORDER).toList(); }
		catch (Throwable unavailable) { return List.of(); }
	}

	/** False when the Java runtime has no Java2D (java.desktop); text then falls back to the vanilla font. */
	public static boolean available() {
		if (available == null) {
			try {
				base(Face.REGULAR).canDisplay('A');
				new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics().dispose();
				available = true;
			} catch (Throwable t) {
				available = false;
				CheatProgram.LOGGER.warn("Java2D fonts are unavailable, using the Minecraft font instead", t);
			}
		}
		return available;
	}

	public static int pixelSize(float sizePx) {
		return Math.max(5, Math.min(200, Math.round(sizePx)));
	}

	public static float width(Face face, String text, float sizePx) {
		if (text == null || text.isEmpty()) return 0;
		if (useVanilla()) return vanillaWidth(face, text, sizePx);
		int px = pixelSize(sizePx);
		float w = 0;
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			w += glyph(face, px, cp).advance;
			i += Character.charCount(cp);
		}
		return w;
	}

	/** Truncates the text with an ellipsis so it fits into maxWidth pixels. */
	public static String ellipsize(Face face, String text, float sizePx, float maxWidth) {
		if (width(face, text, sizePx) <= maxWidth) return text;
		String dots = "...";
		float dotsW = width(face, dots, sizePx);
		if (maxWidth <= 0) return "";
		if (dotsW > maxWidth) {
			String result = "";
			while (width(face, result + ".", sizePx) <= maxWidth && result.length() < 3) result += ".";
			return result;
		}
		int px = pixelSize(sizePx);
		float w = 0;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < text.length(); ) {
			int cp = text.codePointAt(i);
			float a = useVanilla() ? vanillaWidth(face, new String(Character.toChars(cp)), sizePx) : glyph(face, px, cp).advance;
			if (w + a + dotsW > maxWidth) break;
			w += a;
			sb.appendCodePoint(cp);
			i += Character.charCount(cp);
		}
		return sb.toString().stripTrailing() + dots;
	}

	static Glyph glyph(Face face, int px, int cp) {
		revision();
		long key = ((long) face.ordinal() << 48) | ((long) px << 24) | cp;
		Glyph glyph = GLYPHS.get(key);
		if (glyph == null) {
			glyph = rasterize(face, px, cp);
			GLYPHS.put(key, glyph);
		}
		return glyph;
	}

	/** Makes sure the glyph bitmap lives in the atlas; returns null for blank glyphs. */
	static Atlas.Region region(Glyph glyph) {
		if (atlasDirty) { ATLAS.clear(); Icons.invalidate(); atlasDirty = false; }
		if (glyph.bitmap == null) return null;
		if (glyph.region == null || glyph.generation != ATLAS.generation) {
			Atlas.Region region = ATLAS.add(glyph.bitmap, GAMMA);
			if (region == null) {
				ATLAS.clear();
				Icons.invalidate();
				region = ATLAS.add(glyph.bitmap, GAMMA);
			}
			glyph.region = region;
			glyph.generation = ATLAS.generation;
		}
		return glyph.region;
	}

	private static Glyph rasterize(Face face, int px, int cp) {
		Font font = font(face, px);
		if (!font.canDisplay(cp)) {
			for (Font fallback : fallbacks(face)) {
				if (fallback.canDisplay(cp)) { font = fallback.deriveFont((float) px); break; }
			}
		}
		String s = new String(Character.toChars(cp));
		if (!font.canDisplay(cp)) return new Glyph(vanillaWidth(face, s, px), 0, 0, null, s);
		GlyphVector gv = font.createGlyphVector(FRC, s);
		float advance = (float) gv.getGlyphPosition(gv.getNumGlyphs()).getX();
		if (Character.isWhitespace(cp)) return new Glyph(advance, 0, 0, null);
		Rectangle bounds = gv.getPixelBounds(FRC, 0, 0);
		if (bounds.width <= 0 || bounds.height <= 0) return new Glyph(advance, 0, 0, null);

		int w = bounds.width + PAD * 2;
		int h = bounds.height + PAD * 2;
		BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
		g.setColor(Color.WHITE);
		g.drawGlyphVector(gv, PAD - bounds.x, PAD - bounds.y);
		g.dispose();
		return new Glyph(advance, bounds.x - PAD, bounds.y - PAD, image);
	}

	private static Font font(Face face, int px) {
		long key = ((long) face.ordinal() << 32) | px;
		Font font = DERIVED.get(key);
		if (font == null) {
			font = base(face).deriveFont((float) px);
			DERIVED.put(key, font);
		}
		return font;
	}

	private static Font base(Face face) {
		Font font = BASE.get(face);
		if (font == null) {
			font = load(face);
			BASE.put(face, font);
			CheatProgram.LOGGER.info("Cheat Program font {} -> {}", face, font.getFontName(Locale.ROOT));
		}
		return font;
	}

	private static Font load(Face face) {
		ClientSettings settings = ClientSettings.INSTANCE;
		if (settings != null && settings.fontMode.is("custom")) {
			String path = settings.fontPath.get().strip();
			if (!path.isEmpty()) {
				if (!path.equals(importedPath)) {
					importedPath = path;
					try { imported = Font.createFont(Font.TRUETYPE_FONT, new File(path)); }
					catch (Exception failure) { errorKey = "ui.font.invalidFile"; CheatProgram.LOGGER.warn("Could not load custom font {}", path, failure); }
				}
				if (imported != null) return styled(imported, face);
			} else if (!settings.fontFamily.get().isBlank()) {
				String family = settings.fontFamily.get().strip();
				if (availableFamilies().contains(family.toLowerCase(Locale.ROOT))) return styled(new Font(family, Font.PLAIN, 12), face);
				errorKey = "ui.font.invalidFamily";
			} else errorKey = "ui.font.chooseCustom";
		}
		return loadDefault(face);
	}

	private static Font styled(Font font, Face face) {
		float weight = switch (face) { case LIGHT -> TextAttribute.WEIGHT_LIGHT; case MEDIUM -> TextAttribute.WEIGHT_MEDIUM; case BOLD -> TextAttribute.WEIGHT_BOLD; case BLACK -> TextAttribute.WEIGHT_HEAVY; default -> TextAttribute.WEIGHT_REGULAR; };
		return font.deriveFont(Map.of(TextAttribute.WEIGHT, weight));
	}

	private static List<Font> fallbacks(Face face) {
		return FALLBACKS.computeIfAbsent(face, selected -> {
			List<Font> fonts = new ArrayList<>(); fonts.add(loadDefault(selected));
			String[] names = {"Noto Sans CJK JP", "Noto Sans JP", "Yu Gothic", "Meiryo", "Noto Sans CJK KR", "Noto Sans KR", "Malgun Gothic", "Noto Sans CJK TC", "Microsoft JhengHei", "Noto Sans CJK SC", "Microsoft YaHei", "Segoe UI Symbol", "Segoe UI Emoji", Font.DIALOG};
			for (String name : names) if (name.equals(Font.DIALOG) || availableFamilies().contains(name.toLowerCase(Locale.ROOT))) fonts.add(styled(new Font(name, Font.PLAIN, 12), selected));
			return fonts;
		});
	}

	private static Font loadDefault(Face face) {
		String[] localeNames = switch (Lang.language()) {
			case "ja" -> new String[]{"Noto Sans JP", "Noto Sans CJK JP", "Yu Gothic UI", "Yu Gothic", "Meiryo", "Hiragino Sans"};
			case "ko" -> new String[]{"Noto Sans KR", "Noto Sans CJK KR", "Malgun Gothic", "Apple SD Gothic Neo"};
			case "zh_tw" -> new String[]{"Noto Sans TC", "Noto Sans CJK TC", "Microsoft JhengHei UI", "Microsoft JhengHei", "PingFang TC"};
			default -> new String[0];
		};
		for (String name : localeNames) if (availableFamilies().contains(name.toLowerCase(Locale.ROOT))) return styled(new Font(name, Font.PLAIN, 12), face);
		String[] files = switch (face) {
			case REGULAR -> new String[]{"Noto Sans SC (TrueType).otf", "NotoSansSC-Regular.otf", "NotoSansSC-Regular.ttf", "SourceHanSansSC-Regular.otf"};
			case MEDIUM -> new String[]{"Noto Sans SC Medium (TrueType).otf", "NotoSansSC-Medium.otf", "NotoSansSC-Medium.ttf", "SourceHanSansSC-Medium.otf"};
			case BOLD -> new String[]{"Noto Sans SC Bold (TrueType).otf", "NotoSansSC-Bold.otf", "NotoSansSC-Bold.ttf", "SourceHanSansSC-Bold.otf"};
			case BLACK -> new String[]{"seguibl.ttf"};
			case LIGHT -> new String[]{"segoeuisl.ttf", "segoeuil.ttf"};
		};
		for (String dir : fontDirectories()) {
			for (String name : files) {
				File file = new File(dir, name);
				if (!file.isFile()) continue;
				try {
					return Font.createFont(Font.TRUETYPE_FONT, file);
				} catch (Exception e) {
					CheatProgram.LOGGER.warn("Could not load font {}", file, e);
				}
			}
		}
		int style = face == Face.BOLD || face == Face.BLACK ? Font.BOLD : Font.PLAIN;
		String[] names = switch (face) {
			case BLACK -> new String[]{"Segoe UI Black", "Noto Sans SC", "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC"};
			case LIGHT -> new String[]{"Segoe UI Semilight", "Segoe UI Light", "Noto Sans SC", "Microsoft YaHei UI Light", "Microsoft YaHei UI", "PingFang SC"};
			default -> new String[]{"Noto Sans SC", "Source Han Sans SC", "Microsoft YaHei UI", "Microsoft YaHei", "PingFang SC", "Hiragino Sans GB", "Noto Sans CJK SC", "WenQuanYi Micro Hei"};
		};
		Set<String> available = availableFamilies();
		for (String name : names) {
			if (available.contains(name.toLowerCase(Locale.ROOT))) return new Font(name, style, 12);
		}
		if (face == Face.MEDIUM) return loadDefault(Face.REGULAR);
		return new Font(Font.SANS_SERIF, style, 12);
	}

	private static String[] fontDirectories() {
		String windir = System.getenv("WINDIR");
		String local = System.getenv("LOCALAPPDATA");
		return Arrays.stream(new String[]{
				windir == null ? null : windir + File.separator + "Fonts",
				local == null ? null : local + File.separator + "Microsoft" + File.separator + "Windows" + File.separator + "Fonts"
		}).filter(s -> s != null).toArray(String[]::new);
	}

	private static Set<String> availableFamilies() {
		if (families == null) {
			families = new HashSet<>();
			try {
				for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()) {
					families.add(f.toLowerCase(Locale.ROOT));
				}
			} catch (Throwable t) {
				CheatProgram.LOGGER.warn("Could not list system fonts", t);
			}
		}
		return families;
	}
}
