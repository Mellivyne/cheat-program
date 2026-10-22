package com.cheatprogram.util;

import com.cheatprogram.core.Lang;
import org.lwjgl.glfw.GLFW;

/** Short, readable names for key codes. Mouse buttons are stored as {@link #MOUSE_OFFSET} + button. */
public final class KeyNames {
	public static final int MOUSE_OFFSET = 1000;

	private KeyNames() {
	}

	public static boolean isMouse(int code) {
		return code >= MOUSE_OFFSET;
	}

	public static String name(int code) {
		if (code < 0) return Lang.t("ui.keybind.none");
		if (isMouse(code)) {
			int b = code - MOUSE_OFFSET;
			return switch (b) {
				case 0 -> "LMB";
				case 1 -> "RMB";
				case 2 -> "MMB";
				default -> "M" + (b + 1);
			};
		}
		return switch (code) {
			case GLFW.GLFW_KEY_RIGHT_SHIFT -> "Right Shift";
			case GLFW.GLFW_KEY_LEFT_SHIFT -> "Left Shift";
			case GLFW.GLFW_KEY_RIGHT_CONTROL -> "Right Ctrl";
			case GLFW.GLFW_KEY_LEFT_CONTROL -> "Left Ctrl";
			case GLFW.GLFW_KEY_RIGHT_ALT -> "Right Alt";
			case GLFW.GLFW_KEY_LEFT_ALT -> "Left Alt";
			case GLFW.GLFW_KEY_SPACE -> "Space";
			case GLFW.GLFW_KEY_TAB -> "Tab";
			case GLFW.GLFW_KEY_CAPS_LOCK -> "Caps";
			case GLFW.GLFW_KEY_ENTER -> "Enter";
			case GLFW.GLFW_KEY_BACKSPACE -> "Backspace";
			case GLFW.GLFW_KEY_INSERT -> "Insert";
			case GLFW.GLFW_KEY_DELETE -> "Delete";
			case GLFW.GLFW_KEY_HOME -> "Home";
			case GLFW.GLFW_KEY_END -> "End";
			case GLFW.GLFW_KEY_PAGE_UP -> "PgUp";
			case GLFW.GLFW_KEY_PAGE_DOWN -> "PgDn";
			case GLFW.GLFW_KEY_UP -> "Up";
			case GLFW.GLFW_KEY_DOWN -> "Down";
			case GLFW.GLFW_KEY_LEFT -> "Left";
			case GLFW.GLFW_KEY_RIGHT -> "Right";
			case GLFW.GLFW_KEY_GRAVE_ACCENT -> "`";
			default -> {
				if (code >= GLFW.GLFW_KEY_F1 && code <= GLFW.GLFW_KEY_F25) yield "F" + (code - GLFW.GLFW_KEY_F1 + 1);
				if (code >= GLFW.GLFW_KEY_KP_0 && code <= GLFW.GLFW_KEY_KP_9) yield "Num " + (code - GLFW.GLFW_KEY_KP_0);
				String n = GLFW.glfwGetKeyName(code, 0);
				yield n == null ? "Key " + code : n.toUpperCase(java.util.Locale.ROOT);
			}
		};
	}

	/** Compact form for small badges. */
	public static String shortName(int code) {
		String n = name(code);
		return switch (n) {
			case "Right Shift" -> "RShift";
			case "Left Shift" -> "LShift";
			case "Right Ctrl" -> "RCtrl";
			case "Left Ctrl" -> "LCtrl";
			case "Right Alt" -> "RAlt";
			case "Left Alt" -> "LAlt";
			default -> n;
		};
	}
}
