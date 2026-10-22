package com.cheatprogram.compat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Converts modern input records into the existing custom widget event contract. */
public abstract class CompatScreen extends Screen {
    protected CompatScreen(Component title){super(title);}
    @Override public boolean mouseClicked(MouseButtonEvent e,boolean doubleClick){return mouseClicked(e.x(),e.y(),e.button());}
    @Override public boolean mouseReleased(MouseButtonEvent e){return mouseReleased(e.x(),e.y(),e.button());}
    @Override public boolean mouseDragged(MouseButtonEvent e,double dx,double dy){return mouseDragged(e.x(),e.y(),e.button(),dx,dy);}
    @Override public boolean keyPressed(KeyEvent e){return keyPressed(e.key(),e.scancode(),e.modifiers());}
    @Override public boolean keyReleased(KeyEvent e){return keyReleased(e.key(),e.scancode(),e.modifiers());}
    @Override public boolean charTyped(CharacterEvent e){boolean accepted=false;for(char c:Character.toChars(e.codepoint()))accepted|=charTyped(c,e.modifiers());return accepted;}
    public boolean mouseClicked(double x,double y,int button){return super.mouseClicked(mouse(x,y,button),false);}
    public boolean mouseReleased(double x,double y,int button){return super.mouseReleased(mouse(x,y,button));}
    public boolean mouseDragged(double x,double y,int button,double dx,double dy){return super.mouseDragged(mouse(x,y,button),dx,dy);}
    public boolean keyPressed(int key,int scan,int mods){return super.keyPressed(new KeyEvent(key,scan,mods));}
    public boolean keyReleased(int key,int scan,int mods){return super.keyReleased(new KeyEvent(key,scan,mods));}
    public boolean charTyped(char c,int mods){return super.charTyped(new CharacterEvent(c,mods));}
    private static MouseButtonEvent mouse(double x,double y,int button){return new MouseButtonEvent(x,y,new MouseButtonInfo(button,modifiers()));}
    public static boolean hasShiftDown(){return down(GLFW.GLFW_KEY_LEFT_SHIFT)||down(GLFW.GLFW_KEY_RIGHT_SHIFT);}
    public static boolean hasControlDown(){return down(GLFW.GLFW_KEY_LEFT_CONTROL)||down(GLFW.GLFW_KEY_RIGHT_CONTROL);}
    public static boolean hasAltDown(){return down(GLFW.GLFW_KEY_LEFT_ALT)||down(GLFW.GLFW_KEY_RIGHT_ALT);}
    public static int modifiers(){return (hasShiftDown()?GLFW.GLFW_MOD_SHIFT:0)|(hasControlDown()?GLFW.GLFW_MOD_CONTROL:0)|(hasAltDown()?GLFW.GLFW_MOD_ALT:0);}
    private static boolean down(int key){return GLFW.glfwGetKey(Minecraft.getInstance().getWindow().handle(),key)==GLFW.GLFW_PRESS;}
    public static boolean isSelectAll(int key,int mods){return new KeyEvent(key,0,mods).isSelectAll();}
    public static boolean isCopy(int key,int mods){return new KeyEvent(key,0,mods).isCopy();}
    public static boolean isCut(int key,int mods){return new KeyEvent(key,0,mods).isCut();}
    public static boolean isPaste(int key,int mods){return new KeyEvent(key,0,mods).isPaste();}
}
