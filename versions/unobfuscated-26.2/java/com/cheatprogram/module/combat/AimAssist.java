package com.cheatprogram.module.combat;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.Render3D;
import com.cheatprogram.util.Targets;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

public class AimAssist extends Module {
	private final NumberSetting speed = add(new NumberSetting("speed", 5, 1, 20, 0.5));
	private final NumberSetting range = add(new NumberSetting("range", 4.5, 2, 8, 0.1));
	private final NumberSetting fov = add(new NumberSetting("fov", 90, 10, 360, 5, "°"));
	private final BoolSetting clickOnly = add(new BoolSetting("clickOnly", true));
	private final BoolSetting vertical = add(new BoolSetting("vertical", false));
	private long lastFrame = System.nanoTime();

	public AimAssist() {
		super("aimassist", Category.COMBAT);
	}

	/** Runs every frame (from the world render hook) so the aim moves smoothly. */
	@Override
	public void onRender3D(Render3D r3d) {
		long now = System.nanoTime();
		float dt = Math.min(0.05f, (now - lastFrame) / 1_000_000_000f);
		lastFrame = now;
		if (mc.gui.screen() != null || mc.player == null) return;
		if (clickOnly.on() && !mc.options.keyAttack.isDown()) return;
		LivingEntity target = Targets.best(range.val(), fov.val(), "angle", false);
		if (target == null) return;
		float[] rot = Targets.rotationTo(Targets.aimPoint(target));
		float dyaw = Mth.wrapDegrees(rot[0] - mc.player.getYRot());
		float dpitch = vertical.on() ? rot[1] - mc.player.getXRot() : 0f;
		float k = Math.min(1f, speed.floatVal() * dt * 1.6f);
		mc.player.turn(dyaw * k / 0.15, dpitch * k / 0.15);
	}
}
