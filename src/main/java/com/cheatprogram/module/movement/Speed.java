package com.cheatprogram.module.movement;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.module.world.Scaffold;

public class Speed extends Module {
	private final NumberSetting multiplier = add(new NumberSetting("multiplier", 1.4, 1.0, 10.0, 0.05, "x"));
	private final BoolSetting autoJump = add(new BoolSetting("autoJump", false));

	public Speed() {
		super("speed", Category.MOVEMENT);
	}

	@Override
	public String suffix() {
		return multiplier.format();
	}

	@Override
	public void onTick() {
		if (!controlsReady()) return;
		var p = mc.player;
		boolean moving = Math.abs(p.input.forwardImpulse) > 0.01f || Math.abs(p.input.leftImpulse) > 0.01f;
		if (autoJump.on() && !Scaffold.ownsJumpCycle() && !AirWalk.ownsMotion() && moving && p.onGround() && !p.isShiftKeyDown() && !p.isInWater() && !mc.options.keyJump.isDown()) {
			p.jumpFromGround();
		}
	}

	/** A single multiplier shared by ground and airborne walking; never multiply modules together. */
	public static float scale(float speed) {
		return speed * effectiveMultiplier();
	}

	public static float effectiveMultiplier() {
		if (!controlsReady()) return 1f;
		Speed s = ModuleManager.get(Speed.class);
		float movement = s != null && s.isEnabled() ? s.multiplier.floatVal() : 1f;
		return Math.min(10f, Math.max(AirWalk.speedMultiplier(),Math.max(movement, Scaffold.accelerationMultiplier())));
	}

	/** Freecam/AI and flying modules keep their existing movement ownership and speeds. */
	private static boolean controlsReady() {
		return mc.player != null && mc.level != null && mc.screen == null && mc.getOverlay() == null
				&& !mc.isPaused() && mc.isWindowActive() && mc.player.isAlive() && !mc.player.isSpectator()
				&& !mc.player.isPassenger() && !mc.player.getAbilities().flying && !mc.player.isFallFlying()
				&& !AiController.isRunning() && !Freecam.isActive() && !FlatFlight.ownsMotion();
	}
}
