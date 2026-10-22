package com.cheatprogram.capture;

import org.lwjgl.glfw.GLFW;

/** Explicit Windows virtual-key mapping; no system-wide key injection. */
public final class AppInputKeys {
    private AppInputKeys() { }
    public static int virtualKey(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z || key >= GLFW.GLFW_KEY_0 && key <= GLFW.GLFW_KEY_9) return key;
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F24) return 0x70 + key - GLFW.GLFW_KEY_F1;
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) return 0x60 + key - GLFW.GLFW_KEY_KP_0;
        return switch (key) {
            case GLFW.GLFW_KEY_SPACE -> 0x20;
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> 0x0D;
            case GLFW.GLFW_KEY_TAB -> 0x09;
            case GLFW.GLFW_KEY_BACKSPACE -> 0x08;
            case GLFW.GLFW_KEY_INSERT -> 0x2D;
            case GLFW.GLFW_KEY_DELETE -> 0x2E;
            case GLFW.GLFW_KEY_RIGHT -> 0x27;
            case GLFW.GLFW_KEY_LEFT -> 0x25;
            case GLFW.GLFW_KEY_DOWN -> 0x28;
            case GLFW.GLFW_KEY_UP -> 0x26;
            case GLFW.GLFW_KEY_PAGE_UP -> 0x21;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0x22;
            case GLFW.GLFW_KEY_HOME -> 0x24;
            case GLFW.GLFW_KEY_END -> 0x23;
            case GLFW.GLFW_KEY_CAPS_LOCK -> 0x14;
            case GLFW.GLFW_KEY_SCROLL_LOCK -> 0x91;
            case GLFW.GLFW_KEY_NUM_LOCK -> 0x90;
            case GLFW.GLFW_KEY_PAUSE -> 0x13;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> 0xA0;
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> 0xA1;
            case GLFW.GLFW_KEY_LEFT_CONTROL -> 0xA2;
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> 0xA3;
            case GLFW.GLFW_KEY_LEFT_ALT -> 0xA4;
            case GLFW.GLFW_KEY_RIGHT_ALT -> 0xA5;
            case GLFW.GLFW_KEY_APOSTROPHE -> 0xDE;
            case GLFW.GLFW_KEY_COMMA -> 0xBC;
            case GLFW.GLFW_KEY_MINUS -> 0xBD;
            case GLFW.GLFW_KEY_PERIOD -> 0xBE;
            case GLFW.GLFW_KEY_SLASH -> 0xBF;
            case GLFW.GLFW_KEY_SEMICOLON -> 0xBA;
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_EQUAL -> 0xBB;
            case GLFW.GLFW_KEY_LEFT_BRACKET -> 0xDB;
            case GLFW.GLFW_KEY_BACKSLASH -> 0xDC;
            case GLFW.GLFW_KEY_RIGHT_BRACKET -> 0xDD;
            case GLFW.GLFW_KEY_GRAVE_ACCENT -> 0xC0;
            case GLFW.GLFW_KEY_KP_DECIMAL -> 0x6E;
            case GLFW.GLFW_KEY_KP_DIVIDE -> 0x6F;
            case GLFW.GLFW_KEY_KP_MULTIPLY -> 0x6A;
            case GLFW.GLFW_KEY_KP_SUBTRACT -> 0x6D;
            case GLFW.GLFW_KEY_KP_ADD -> 0x6B;
            default -> 0;
        };
    }
}
