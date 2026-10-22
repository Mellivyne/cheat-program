package com.cheatprogram.module.movement;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Module;

/** Behaviour lives in LocalPlayerMixin, which ignores item use while computing movement. */
public class NoSlow extends Module {
	public NoSlow() {
		super("noslow", Category.MOVEMENT);
	}
}
