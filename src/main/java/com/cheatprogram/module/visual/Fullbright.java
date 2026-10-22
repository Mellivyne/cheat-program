package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;

/** Applied in LightTextureMixin, which forces the night vision factor of the lightmap. */
public class Fullbright extends Module {
	public Fullbright() {
		super("fullbright", Category.VISUAL);
	}

	public static boolean active() {
		return ModuleManager.on(Fullbright.class);
	}
}
