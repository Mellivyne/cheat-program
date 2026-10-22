package com.cheatprogram.module.visual;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;

/** Applied in GameRendererMixin by skipping the hurt camera tilt. */
public class NoHurtCam extends Module {
	public NoHurtCam() {
		super("nohurtcam", Category.VISUAL);
	}

	public static boolean active() {
		return ModuleManager.on(NoHurtCam.class);
	}
}
