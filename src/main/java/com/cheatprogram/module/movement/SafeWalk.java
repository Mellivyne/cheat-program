package com.cheatprogram.module.movement;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.world.Scaffold;

public class SafeWalk extends Module {
	public SafeWalk() {
		super("safewalk", Category.MOVEMENT);
	}

	/** Whether the local player should stop at block edges like when sneaking. */
	public static boolean active() {
		if (FlatFlight.ownsMotion()) return false;
		Scaffold scaffold = ModuleManager.get(Scaffold.class);
		if (scaffold != null && scaffold.isTellyAirborne()) return false;
		if (ModuleManager.on(SafeWalk.class)) return true;
		return scaffold != null && scaffold.isEnabled() && scaffold.safeWalk();
	}
}
