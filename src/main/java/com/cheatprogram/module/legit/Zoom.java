package com.cheatprogram.module.legit;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.util.Anim;
import org.lwjgl.glfw.GLFW;

public class Zoom extends Module {
	private final NumberSetting factor = add(new NumberSetting("factor", 4, 1.5, 10, 0.5, "x"));
	private final BoolSetting smooth = add(new BoolSetting("smooth", true));
	private final Anim current = new Anim(1);
	private long lastCall = System.nanoTime();

	public Zoom() {
		super("zoom", Category.LEGIT, GLFW.GLFW_KEY_C);
		setHoldBind(true);
	}

	/** Applied to the field of view every frame; animates in and out even after the bind is released. */
	public static float apply(float fov) {
		Zoom zoom = ModuleManager.get(Zoom.class);
		if (zoom == null) return fov;
		long now = System.nanoTime();
		float dt = Math.min(0.1f, (now - zoom.lastCall) / 1_000_000_000f);
		zoom.lastCall = now;
		float target = zoom.isEnabled() ? zoom.factor.floatVal() : 1f;
		float value = zoom.smooth.on() ? zoom.current.to(target, dt, 14f) : target;
		if (!zoom.smooth.on()) zoom.current.snap(target);
		return value <= 1.0001f ? fov : fov / value;
	}
}
