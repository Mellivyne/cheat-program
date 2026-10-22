package com.cheatprogram.module.utility;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;

public class Panic extends Module {
	public Panic() {
		super("panic", Category.UTILITY);
	}

	@Override
	public boolean isOneShot() {
		return true;
	}

	@Override
	public void onEnable() {
		ModuleManager.disableAll();
		setEnabled(false);
	}
}
