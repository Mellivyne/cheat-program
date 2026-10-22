package com.cheatprogram.module.legit;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.render.RearMirrorRenderer;

/** A second, read-only view of the world behind the active camera. */
public final class RearViewMirror extends Module {
	private final ModeSetting displayMode = add(new ModeSetting("displayMode", "mirror_flat", "mirror_flat", "mirror_3d"));
	private final NumberSetting mirrorWidth = add(new NumberSetting("mirrorWidth", 220, 80, 480, 1, "px"));
	private final NumberSetting mirrorHeight = add(new NumberSetting("mirrorHeight", 120, 50, 300, 1, "px"));
	private final NumberSetting positionX = add(new NumberSetting("positionX", 50, 0, 100, .1, "%"));
	private final NumberSetting positionY = add(new NumberSetting("positionY", 12, 0, 100, .1, "%"));
	private final NumberSetting tiltX = add(new NumberSetting("tiltX", -12, -55, 55, 1, "°")).visibleWhen(this::threeDimensional);
	private final NumberSetting tiltY = add(new NumberSetting("tiltY", 18, -55, 55, 1, "°")).visibleWhen(this::threeDimensional);
	private final NumberSetting refreshRate = add(new NumberSetting("refreshRate", 15, 5, 60, 1, "fps"));
	private final NumberSetting mirrorFov = add(new NumberSetting("mirrorFov", 90, 45, 120, 1, "°"));
	private final BoolSetting horizontalFlip = add(new BoolSetting("horizontalFlip", true));

	public RearViewMirror() { super("rearviewmirror", Category.LEGIT); }
	public boolean threeDimensional() { return displayMode.is("mirror_3d"); }
	public boolean horizontalFlip() { return horizontalFlip.on(); }
	public float width() { return mirrorWidth.floatVal(); }
	public float height() { return mirrorHeight.floatVal(); }
	public float tiltX() { return tiltX.floatVal(); }
	public float tiltY() { return tiltY.floatVal(); }
	public float fieldOfView() { return mirrorFov.floatVal(); }
	public int refreshRate() { return refreshRate.intVal(); }
	public void setThreeDimensional(boolean value) { displayMode.set(value ? "mirror_3d" : "mirror_flat"); }
	public void setTilt(float x, float y) { tiltX.set((double)x); tiltY.set((double)y); }
	public void resize(float width, float height) { mirrorWidth.set((double)width); mirrorHeight.set((double)height); }
	public float[] bounds(float screenWidth, float screenHeight) {
		float w = Math.min(width(), Math.max(32, screenWidth - 8)), h = Math.min(height(), Math.max(24, screenHeight - 8));
		return new float[]{4 + Math.max(0, screenWidth - w - 8) * positionX.floatVal() / 100,
				4 + Math.max(0, screenHeight - h - 8) * positionY.floatVal() / 100, w, h};
	}
	public void move(float x, float y, float screenWidth, float screenHeight) {
		float[] b = bounds(screenWidth, screenHeight);
		positionX.set((double)((x - 4) / Math.max(1, screenWidth - b[2] - 8) * 100));
		positionY.set((double)((y - 4) / Math.max(1, screenHeight - b[3] - 8) * 100));
	}
	public void resetLayout() {
		mirrorWidth.reset(); mirrorHeight.reset(); positionX.reset(); positionY.reset(); tiltX.reset(); tiltY.reset();
	}
	@Override public String suffix() { return displayMode.display(); }
	@Override public void onDisable() { RearMirrorRenderer.release(); }
	@Override public void onRender2D(HudContext ctx) { RearMirrorRenderer.draw(this, ctx); }
}
