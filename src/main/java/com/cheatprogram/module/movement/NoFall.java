package com.cheatprogram.module.movement;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.util.FallProtection;

public class NoFall extends Module {
	public NoFall() {
		super("nofall", Category.MOVEMENT);
	}

	@Override public void onEnable(){FallProtection.refresh();}
	@Override public void onDisable(){FallProtection.refresh();}
	@Override public void onTick(){FallProtection.refresh();}
}
