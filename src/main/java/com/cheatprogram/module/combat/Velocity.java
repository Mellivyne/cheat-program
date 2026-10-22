package com.cheatprogram.module.combat;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.BoolSetting;
import com.cheatprogram.core.setting.NumberSetting;
import net.minecraft.world.phys.Vec3;

public class Velocity extends Module {
	private final NumberSetting horizontal = add(new NumberSetting("horizontal", 90, 0, 100, 1, "%"));
	private final NumberSetting vertical = add(new NumberSetting("vertical", 100, 0, 100, 1, "%"));
	private final NumberSetting chance = add(new NumberSetting("chance", 100, 0, 100, 1, "%"));
	private final BoolSetting explosions = add(new BoolSetting("explosions", true));

	public Velocity() {
		super("velocity", Category.COMBAT);
	}

	@Override
	public String suffix() {
		return horizontal.intVal() + "% " + vertical.intVal() + "%";
	}

	/** Scales knockback received from the server; returns the input unchanged when inactive. */
	public static Vec3 apply(Vec3 motion) {
		Velocity v = ModuleManager.get(Velocity.class);
		if (v == null || !v.isEnabled() || Math.random() * 100 >= v.chance.val()) return motion;
		double h = v.horizontal.fraction();
		return new Vec3(motion.x * h, motion.y * v.vertical.fraction(), motion.z * h);
	}

	public static Vec3 applyExplosion(Vec3 push) {
		Velocity v = ModuleManager.get(Velocity.class);
		if (v == null || !v.isEnabled() || !v.explosions.on()) return push;
		double h = v.horizontal.fraction();
		return new Vec3(push.x * h, push.y * v.vertical.fraction(), push.z * h);
	}
}
