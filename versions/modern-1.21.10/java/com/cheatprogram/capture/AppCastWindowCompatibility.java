package com.cheatprogram.capture;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/**
 * Keeps a captured Chromium window from being treated as fully covered by Minecraft.
 * Only Minecraft's own window is changed, through GLFW's public opacity API.
 *
 * GLFW implements this API for its Win32 OpenGL windows:
 * https://github.com/glfw/glfw/blob/3.4/src/win32_window.c
 * Chromium excludes layered windows with alpha below 255 from opaque occluders:
 * https://chromium.googlesource.com/chromium/src/+/main/ui/gfx/win/hwnd_util.cc
 * This is a compatibility workaround, not a guarantee that every source app renders
 * in the background. No source window, foreground state, or browser setting changes.
 * Call both methods only on Minecraft's main thread, before GLFW is terminated.
 */
public final class AppCastWindowCompatibility {
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("windows");
    private static final float COMPATIBLE_OPACITY = 254f / 255f;
    private static final float EPSILON = .00001f;
    private static long attemptedWindow, ownedWindow;
    private static float originalOpacity, appliedOpacity;

    private AppCastWindowCompatibility() { }

    /** Reapply our own compatibility state once after Minecraft returns from the background. */
    public static void refreshAfterResume(boolean requested) {
        update(requested);
    }

    public static void update(boolean requested) {
        if (!WINDOWS) return;
        long window = currentWindow();
        if (!requested || window == 0) {
            release();
            return;
        }
        if (attemptedWindow == window && ownedWindow == 0) return;
        try {
            if (GLFW.glfwGetWindowMonitor(window) != 0) {
                release();
                return;
            }
            if (ownedWindow != 0 && ownedWindow != window) release();
            // Includes unsupported calls and windows already made transparent by
            // another mod: retry only after leaving/re-entering this mode.
            if (attemptedWindow == window) {
                if (ownedWindow != window) return;
                float current = GLFW.glfwGetWindowOpacity(window);
                if (!Float.isFinite(current) || Math.abs(current - appliedOpacity) <= EPSILON) return;
                // F11 can recreate/reset Win32 styles on the same GLFW window.
                // Repair a reset to opaque, but respect another mod's opacity.
                if (Math.abs(current - 1f) > EPSILON) { ownedWindow = 0; return; }
                ownedWindow = 0;
            }
            attemptedWindow = window;
            float previous = GLFW.glfwGetWindowOpacity(window);
            if (!Float.isFinite(previous) || Math.abs(previous - 1f) > EPSILON) return;
            originalOpacity = previous;
            ownedWindow = window;
            appliedOpacity = COMPATIBLE_OPACITY;
            GLFW.glfwSetWindowOpacity(window, COMPATIBLE_OPACITY);
            float actual = GLFW.glfwGetWindowOpacity(window);
            if (!Float.isFinite(actual) || Math.abs(actual - COMPATIBLE_OPACITY) > EPSILON) {
                restoreFailedAttempt(window);
                return;
            }
            appliedOpacity = actual;
        } catch (LinkageError | RuntimeException unsupported) {
            // GLFW may report platform failures through Minecraft's error callback.
            // Roll back once and do not retry or log on every render/tick.
            restoreFailedAttempt(window);
            attemptedWindow = window;
        }
    }

    public static void release() {
        long window = ownedWindow;
        ownedWindow = 0;
        attemptedWindow = 0;
        if (!WINDOWS || window == 0 || currentWindow() != window) return;
        try {
            // Respect an opacity change another mod made after our adjustment.
            float current = GLFW.glfwGetWindowOpacity(window);
            if (Float.isFinite(current) && Math.abs(current - appliedOpacity) <= EPSILON)
                GLFW.glfwSetWindowOpacity(window, originalOpacity);
        } catch (LinkageError | RuntimeException ignored) { }
    }

    private static void restoreFailedAttempt(long window) {
        if (ownedWindow != window || window == 0) return;
        ownedWindow = 0;
        if (currentWindow() != window) return;
        try { GLFW.glfwSetWindowOpacity(window, originalOpacity); }
        catch (LinkageError | RuntimeException ignored) { }
    }

    private static long currentWindow() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null || minecraft.getWindow() == null ? 0 : minecraft.getWindow().handle();
    }
}
