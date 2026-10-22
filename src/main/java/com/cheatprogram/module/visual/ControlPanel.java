package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.TextSetting;
import com.cheatprogram.render.ControlPanelTextures;

/** Appearance shared by the control panel and its settings pages; world/HUD overlays remain independent. */
public final class ControlPanel extends Module {
	private final TextSetting imagePath = add(new TextSetting("imagePath", "", 2048));
	private final NumberSetting opacity = add(new NumberSetting("opacity", 65, 0, 100, 1, "%"));
	private final NumberSetting refraction = add(new NumberSetting("refraction", 100, 0, 200, 5, "%"));
	private final NumberSetting frost = add(new NumberSetting("frost", 15, 0, 100, 1, "%"));
	private final NumberSetting brightness = add(new NumberSetting("brightness", 75, 20, 120, 1, "%"));
	private final NumberSetting zoom = add(new NumberSetting("zoom", 100, 100, 250, 5, "%"));
	private final NumberSetting imageX = add(new NumberSetting("imageX", 0, -100, 100, 1, "%"));
	private final NumberSetting imageY = add(new NumberSetting("imageY", 0, -100, 100, 1, "%"));
	private final TextSetting cropSource = add(new TextSetting("cropSource", "", 2048).visibleWhen(() -> false));
	private final NumberSetting cropX = add(new NumberSetting("cropX", 0, 0, 1, 0).visibleWhen(() -> false));
	private final NumberSetting cropY = add(new NumberSetting("cropY", 0, 0, 1, 0).visibleWhen(() -> false));
	private final NumberSetting cropWidth = add(new NumberSetting("cropWidth", 1, 0, 1, 0).visibleWhen(() -> false));
	private final NumberSetting cropHeight = add(new NumberSetting("cropHeight", 1, 0, 1, 0).visibleWhen(() -> false));
	/** Selection in the original media's UV space; the original file is never rewritten. */
	public record Crop(double x, double y, double width, double height) {
		public static final Crop FULL = new Crop(0, 0, 1, 1);
		public Crop {
			width = Math.max(1d / 65536, Math.min(1, Double.isFinite(width) ? width : 1));
			height = Math.max(1d / 65536, Math.min(1, Double.isFinite(height) ? height : 1));
			x = Math.max(0, Math.min(1 - width, Double.isFinite(x) ? x : 0));
			y = Math.max(0, Math.min(1 - height, Double.isFinite(y) ? y : 0));
		}
	}
	public ControlPanel() { super("controlpanel", Category.VISUAL); }
	public String imagePath() { return imagePath.get(); }
	public void imagePath(String path) { applyMedia(path, Crop.FULL); }
	public Crop crop() {
		return imagePath.get().equals(cropSource.get())
				? new Crop(cropX.val(), cropY.val(), cropWidth.val(), cropHeight.val()) : Crop.FULL;
	}
	public void applyMedia(String path, Crop selection) {
		String next = path == null ? "" : path.strip();
		Crop crop = selection == null ? Crop.FULL : selection;
		imagePath.set(next); cropSource.set(next);
		cropX.set(crop.x()); cropY.set(crop.y()); cropWidth.set(crop.width()); cropHeight.set(crop.height());
		ControlPanelTextures.retry();
	}
	public float opacity() { return opacity.floatVal()/100; }
	public float refraction() { return refraction.floatVal()/100; }
	public float frost() { return frost.floatVal()/100; }
	public float brightness() { return brightness.floatVal()/100; }
	public float zoom() { return zoom.floatVal()/100; }
	public float imageX() { return imageX.floatVal()/100; }
	public float imageY() { return imageY.floatVal()/100; }
	@Override public void onDisable() { ControlPanelTextures.release(); }
	@Override public String suffix() { return Lang.t(ControlPanelTextures.statusKey(imagePath())); }
}
