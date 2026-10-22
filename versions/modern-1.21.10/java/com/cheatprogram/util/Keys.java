package com.cheatprogram.util;

import com.cheatprogram.mixin.KeyMappingAccessor;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Reads the physical state of key bindings, independent of what modules have forced. */
public final class Keys {
	private Keys() {
	}

	public static boolean physical(KeyMapping mapping) {
		InputConstants.Key key = ((KeyMappingAccessor) mapping).cheatprogram$getBoundKey();
		long window = Minecraft.getInstance().getWindow().handle();
		if (key.getType() == InputConstants.Type.MOUSE) return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
		if (key.getType() == InputConstants.Type.KEYSYM) return key.getValue() != InputConstants.UNKNOWN.getValue() && com.cheatprogram.compat.KeyboardBridge.isKeyDown(window, key.getValue());
		return false;
	}

	/** Puts a binding back to whatever the player is physically holding. */
	public static void restore(KeyMapping mapping) {
		mapping.setDown(physical(mapping));
	}
}
