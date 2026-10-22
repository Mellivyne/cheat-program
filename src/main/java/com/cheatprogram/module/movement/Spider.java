package com.cheatprogram.module.movement;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.NumberSetting;
import net.minecraft.world.phys.Vec3;

public class Spider extends Module {
	private final NumberSetting speed = add(new NumberSetting("speed", 0.2, 0.1, 0.6, 0.05));

	public Spider() {
		super("spider", Category.MOVEMENT);
	}

	@Override
	public void onTick() {
		if (FlatFlight.ownsMotion()) return;
		var p = mc.player;
		boolean pushing = Math.abs(p.input.forwardImpulse) > 0.01f || Math.abs(p.input.leftImpulse) > 0.01f;
		if (p.horizontalCollision && pushing && !p.isShiftKeyDown()) {
			Vec3 v = p.getDeltaMovement();
			p.setDeltaMovement(v.x, Math.max(v.y, speed.val()), v.z);
			p.resetFallDistance();
		}
	}
}
