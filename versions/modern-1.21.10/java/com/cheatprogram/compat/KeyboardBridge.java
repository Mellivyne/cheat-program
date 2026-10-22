package com.cheatprogram.compat;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.glfw.GLFW;
public final class KeyboardBridge {
    private KeyboardBridge(){}
    public static boolean isKeyDown(long window,int key){return GLFW.glfwGetKey(window,key)==GLFW.GLFW_PRESS;}
    public static boolean isKeyDown(Window window,int key){return isKeyDown(window.handle(),key);}
    public static InputConstants.Key getKey(int key,int scancode){return InputConstants.getKey(new KeyEvent(key,scancode,0));}
}
