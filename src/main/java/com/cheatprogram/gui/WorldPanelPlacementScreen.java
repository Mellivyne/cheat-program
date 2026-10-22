package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.*;
import com.cheatprogram.util.KeyNames;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** World-space placement is committed only by the confirm action. */
public final class WorldPanelPlacementScreen extends GuiBase {
    private final Screen parent;
    private final NumberSetting right=new NumberSetting("right",0,-8,8,.1),up=new NumberSetting("up",1,-8,8,.1),forward=new NumberSetting("forward",3,-8,8,.1);
    private boolean binding;
    public WorldPanelPlacementScreen(Screen parent){super("Place 3D Control Panel");this.parent=parent;WorldControlPanel.beginPreview();}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void renderBackground(GuiGraphics g,int x,int y,float partial){ }
    @Override public void render(GuiGraphics g,int x,int y,float partial){
        beginFrame();R2D.begin(g);
        try{
            float scale=Math.max(.45f,Math.min(R2D.width()/1060f,R2D.height()/760f)*(float)ClientSettings.INSTANCE.uiScale.fraction());
            float w=Math.min(760,(R2D.width()-20)/scale),h=222;
            c.set(scale,(R2D.width()-w*scale)/2,R2D.height()-h*scale-10);updateMouse();
            c.shadow(0,0,w,h,18,20,0x33101010);c.lens(0,0,w,h,18,Theme.lift(.12f),Theme.sheetGlass(scale));
            c.textC(BOLD,Lang.t("ui.worldpanel.previewTitle"),18,24,14,Theme.TEXT);
            c.textC(REG,Lang.t("ui.worldpanel.exitHint",com.cheatprogram.util.KeyNames.name(com.cheatprogram.core.ClientSettings.INSTANCE.guiKey)),18,46,10.5f,Theme.TEXT_2);
            float cw=(w-54)/3;row("ui.worldpanel.right",right,18,65,cw);row("ui.worldpanel.up",up,27+cw,65,cw);row("ui.worldpanel.forward",forward,36+cw*2,65,cw);
            WorldControlPanel.previewOffsets(right.val(),up.val(),forward.val());
            button("worldpanel.smaller","−",null,18,119,36,28,false,()->WorldControlPanel.resize(-1));
            button("worldpanel.larger","+",null,60,119,36,28,false,()->WorldControlPanel.resize(1));
            c.textC(REG,Lang.t("ui.worldpanel.sizeValue",String.format(java.util.Locale.ROOT,"%.1f",WorldControlPanel.settings().worldScreenWidth())),106,133,11,Theme.TEXT_2);
            button("worldpanel.bind",Lang.t("ui.worldpanel.bind")+" · "+(binding?Lang.t("ui.keybind.waiting"):KeyNames.name(WorldControlPanel.interactionKey())),Icon.KEYBOARD,w-310,119,292,28,binding,()->binding=!binding);
            button("worldpanel.reset",Lang.t("ui.worldpanel.resetPlace"),Icon.REFRESH,18,158,170,28,false,()->{right.set(0.0);up.set(1.0);forward.set(3.0);WorldControlPanel.beginPreview();});
            c.textC(REG,Lang.t("ui.worldpanel.placementHint"),202,172,9,Theme.TEXT_3);
            button("worldpanel.cancel",Lang.t("ui.cancel"),Icon.CLOSE,18,h-29,110,24,false,this::onClose);
            button("worldpanel.commit",Lang.t("ui.worldpanel.confirmPlace"),Icon.CHECK,w-164,h-29,146,24,true,WorldControlPanel::commitPlacement);
        }finally{R2D.end();endFrame();}
    }
    private void row(String label,NumberSetting s,float x,float y,float w){c.textC(REG,Lang.t(label)+" · "+s.format(),x,y+10,10.5f,Theme.TEXT_2);slider(s,x+4,y+30,w-8);}
    @Override public boolean mouseClicked(double x,double y,int button){return dispatchClick(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double sx,double sy){WorldControlPanel.resize(sy);return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(binding){if(key==GLFW.GLFW_KEY_ESCAPE){binding=false;return true;}if(key!=ClientSettings.INSTANCE.guiKey){WorldControlPanel.interactionKey(key);binding=false;}return true;}
        if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}return super.keyPressed(key,scan,mods);
    }
    @Override public void onClose(){WorldControlPanel.cancelPlacement(parent);}
}
