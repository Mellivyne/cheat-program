package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.ModeSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.HaloModelLibrary;
import com.cheatprogram.render.HaloModelRenderer;
import com.cheatprogram.render.Render3D;
import net.minecraft.util.Mth;

/** Local character halos read from a separately installed resource archive. */
public final class BlueArchiveHalo extends Module {
	private final ModeSetting character = add(new ModeSetting("character", "halo_hoshino", "halo_hoshino", "halo_shiroko", "halo_yuuka",
			"halo_hina", "halo_azusa", "halo_arisu", "halo_wakamo", "halo_mika", "halo_hihumi", "halo_serika"));
	private final ModeSetting style = add(new ModeSetting("style", "halo_original", "halo_original", "halo_relief", "halo_pixel"));
	private final NumberSetting thickness = add(new NumberSetting("thickness", 6, 1, 25, 1, "%")).visibleWhen(() -> !style.is("halo_original"));
	private final NumberSetting pixelResolution = add(new NumberSetting("pixelResolution", 32, 8, 64, 1)).visibleWhen(() -> style.is("halo_pixel"));
	private final NumberSetting size = add(new NumberSetting("size", 100, 25, 250, 5, "%"));
	private final NumberSetting height = add(new NumberSetting("height", 0, -.3, 1.5, .05));
	private final NumberSetting opacity = add(new NumberSetting("opacity", 100, 10, 100, 5, "%"));
	private final NumberSetting brightness = add(new NumberSetting("brightness", 100, 25, 200, 5, "%"));
	private final BoolSetting rotate = add(new BoolSetting("rotate", false));
	private final NumberSetting rotationSpeed = add(new NumberSetting("rotationSpeed", 20, -180, 180, 5, "°/s")).visibleWhen(rotate::on);
	private final BoolSetting modelAnimation = add(new BoolSetting("modelAnimation", true));
	private final BoolSetting showPreview = add(new BoolSetting("showPreview", true));
	private long lastFrame;
	private double elapsed;

	public BlueArchiveHalo() { super("bluearchivehalo", Category.VISUAL); }
	public String characterId() { return character.get().substring("halo_".length()); }
	public boolean isPreviewVisible() { return showPreview.on(); }
	public String previewStyle() { return style.get(); }
	public float previewThickness() { return thickness.floatVal() / 100; }
	public int previewPixelResolution() { return pixelResolution.intVal(); }
	public float previewSize() { return size.floatVal() / 100; }
	public float previewOpacity() { return opacity.floatVal() / 100; }
	public float previewBrightness() { return brightness.floatVal() / 100; }
	public double previewAnimationTime(double elapsed) { return modelAnimation.on() ? elapsed : 0; }
	public float previewRotation(double elapsed) { return rotate.on() ? (float)(elapsed * rotationSpeed.val() % 360) : 0; }
	@Override public String suffix() {
		String state = HaloModelLibrary.statusKey();
		return character.display() + " · " + style.display() + (state.equals("ui.halo.ready") ? "" : " · " + Lang.t(state));
	}
	@Override public void onEnable() { lastFrame = System.nanoTime(); elapsed = 0; HaloModelLibrary.retry(characterId()); }
	@Override public void onDisable() { lastFrame = 0; elapsed = 0; HaloModelRenderer.releaseMesh(); }
	@Override public void onRender3D(Render3D renderer) {
		long now = System.nanoTime();
		if (lastFrame != 0 && !mc.isPaused()) elapsed += Math.min(.15, Math.max(0, (now - lastFrame) / 1_000_000_000.0));
		lastFrame = now;
		if (!inGame() || !mc.player.isAlive() || mc.player.isSpectator() || mc.player.isSleeping() || mc.player.isInvisible()) return;
		var model = HaloModelLibrary.model(characterId());
		// A head-mounted object belongs above the body, never between first-person eyes and the crosshair.
		if (mc.options.getCameraType().isFirstPerson() && !Freecam.isActive()) return;
		if (model == null) return;
		float partial = renderer.partialTick;
		var head = mc.player.getPosition(partial).add(0, mc.player.getBbHeight() + height.val(), 0);
		float yaw = Mth.rotLerp(partial, mc.player.yHeadRotO, mc.player.yHeadRot);
		if (rotate.on()) yaw += (float)(elapsed * rotationSpeed.val() % 360);
		HaloModelRenderer.draw(renderer, model, head, yaw, size.floatVal() / 100, opacity.floatVal() / 100,
				brightness.floatVal() / 100, modelAnimation.on() ? elapsed : 0, style.get(), thickness.floatVal() / 100, (int)pixelResolution.val());
	}
}
