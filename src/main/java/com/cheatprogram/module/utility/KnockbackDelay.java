package com.cheatprogram.module.utility;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.core.setting.RangeSetting;
import net.minecraft.world.phys.Vec3;

public class KnockbackDelay extends Module {
	private final NumberSetting chance = add(new NumberSetting("chance", 100, 0, 100, 1, "%"));
	private final RangeSetting airDelay = add(new RangeSetting("airDelay", 300, 500, 0, 1000, 10, "ms"));
	private final RangeSetting groundDelay = add(new RangeSetting("groundDelay", 300, 500, 0, 1000, 10, "ms"));
	private Vec3 pending;
	private long applyAt;

	public KnockbackDelay() {
		super("kbdelay", Category.UTILITY);
	}

	/** Holds back knockback for the local player. Returns true when the motion was queued. */
	public static boolean intercept(Vec3 motion) {
		KnockbackDelay kd = ModuleManager.get(KnockbackDelay.class);
		if (kd == null || !kd.isEnabled() || mc.player == null || Math.random() * 100 >= kd.chance.val()) return false;
		double delay = mc.player.onGround() ? kd.groundDelay.random() : kd.airDelay.random();
		kd.pending = motion;
		kd.applyAt = System.currentTimeMillis() + (long) delay;
		return true;
	}

	@Override
	public void onTick() {
		if (pending != null && System.currentTimeMillis() >= applyAt) release();
	}

	@Override
	public void onDisable() {
		if (pending != null && mc.player != null) release();
		pending = null;
	}

	private void release() {
		mc.player.lerpMotion(pending.x, pending.y, pending.z);
		pending = null;
	}
}
