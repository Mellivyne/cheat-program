package com.cheatprogram.gui;

import com.cheatprogram.render.WorldControlPanel;
import com.cheatprogram.render.R2D;
import com.cheatprogram.render.Fonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import com.cheatprogram.util.Keys;
import net.minecraft.client.KeyMapping;

/** Input only: the page itself is painted onto its fixed world surface. */
public final class WorldPanelInputScreen extends com.cheatprogram.compat.CompatScreen {
    public WorldPanelInputScreen(){super(Component.literal("3D Control Panel"));}
    @Override protected void init(){WorldControlPanel.beginCursor();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float partial){ }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        WorldControlPanel.updateCursor();refreshMovement();
        R2D.begin(graphics);
        try{String text=WorldControlPanel.help();float size=Fonts.pixelSize(12),width=R2D.textWidth(Fonts.Face.REGULAR,text,size);
            R2D.text(Fonts.Face.REGULAR,text,Math.max(8,(R2D.width()-width)/2),R2D.height()-16,size,0xddffffff);
        }finally{R2D.end();}
    }
    private double[] point(){WorldControlPanel.updateCursor();return WorldControlPanel.guiPoint(WorldControlPanel.pointer());}
    private KeyMapping[] movement(){var o=Minecraft.getInstance().options;return new KeyMapping[]{o.keyUp,o.keyDown,o.keyLeft,o.keyRight,o.keyJump,o.keyShift,o.keySprint};}
    private void refreshMovement(){boolean allowed=Minecraft.getInstance().isWindowActive()&&WorldControlPanel.page()!=null&&!WorldControlPanel.page().capturesWorldKeyboard();for(KeyMapping mapping:movement())mapping.setDown(allowed&&Keys.physical(mapping));}
    private boolean movementKey(int key,int scan){for(KeyMapping mapping:movement())if(mapping.matches(new net.minecraft.client.input.KeyEvent(key,scan,0)))return true;return false;}
    @Override public boolean mouseClicked(double x,double y,int button){
        double[] p=point();ClickGuiScreen gui=WorldControlPanel.page();
        if(p!=null&&gui!=null)gui.mouseClicked(p[0],p[1],button);refreshMovement();
        return true;
    }
    @Override public boolean mouseReleased(double x,double y,int button){double[] p=point();ClickGuiScreen gui=WorldControlPanel.page();if(gui!=null)gui.mouseReleased(p==null?-1000:p[0],p==null?-1000:p[1],button);return true;}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){
        double[] p=point();ClickGuiScreen gui=WorldControlPanel.page();if(p!=null&&gui!=null)gui.mouseDragged(p[0],p[1],button,dx,dy);return true;
    }
    @Override public boolean mouseScrolled(double x,double y,double sx,double sy){
        double[] p=point();ClickGuiScreen gui=WorldControlPanel.page();if(p!=null&&gui!=null)gui.mouseScrolled(p[0],p[1],sx,sy);return true;
    }
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==GLFW.GLFW_KEY_ESCAPE){WorldControlPanel.leave();return true;}
        refreshMovement();if(WorldControlPanel.page()!=null&&!WorldControlPanel.page().capturesWorldKeyboard()&&movementKey(key,scan))return true;
        ClickGuiScreen gui=WorldControlPanel.page();return gui!=null&&gui.keyPressed(key,scan,modifiers);
    }
    @Override public boolean keyReleased(int key,int scan,int modifiers){refreshMovement();ClickGuiScreen gui=WorldControlPanel.page();return gui!=null&&gui.keyReleased(key,scan,modifiers);}
    @Override public boolean charTyped(char c,int modifiers){ClickGuiScreen gui=WorldControlPanel.page();return gui!=null&&gui.isTyping()&&gui.charTyped(c,modifiers);}
    @Override public void onClose(){WorldControlPanel.leave();}
    @Override public void removed(){WorldControlPanel.endCursor();WorldControlPanel.releaseInput();}
}
