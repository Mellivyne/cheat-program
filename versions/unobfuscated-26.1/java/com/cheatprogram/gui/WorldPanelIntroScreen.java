package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.render.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** The first entry is explicit; a short delay prevents accidental confirmation. */
public final class WorldPanelIntroScreen extends GuiBase {
    private final Screen parent;
    private final long started=System.nanoTime();
    public WorldPanelIntroScreen(Screen parent){super("3D Control Panel");this.parent=parent;}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphicsExtractor g,int x,int y,float partial){
        beginFrame();R2D.begin(g);
        try{float w=540,h=layoutPanel(w,240);drawPanelSurface(g,w,h,22);
            c.textC(BOLD,Lang.t("ui.worldpanel.firstTitle"),24,30,15,Theme.TEXT);
            float ty=66;for(String line:c.wrap(REG,Lang.t("ui.worldpanel.exitHint",com.cheatprogram.util.KeyNames.name(com.cheatprogram.core.ClientSettings.INSTANCE.guiKey)),15,w-48)){c.textC(MED,line,24,ty,15,Theme.TEXT);ty+=24;}
            for(String line:c.wrap(REG,Lang.t("ui.worldpanel.firstBody"),10.5f,w-48)){c.textC(REG,line,24,ty+12,10.5f,Theme.TEXT_2);ty+=18;}
            button("worldpanel.cancel",Lang.t("ui.cancel"),Icon.CLOSE,24,h-48,130,30,false,this::onClose);
            long remaining=Math.max(0,3_000_000_000L-(System.nanoTime()-started));
            String confirm=Lang.t("ui.confirm")+(remaining>0?" ("+(int)Math.ceil(remaining/1_000_000_000.0)+"s)":"");
            R2D.pushAlpha(remaining==0?1:.4f);
            try{button("worldpanel.confirm",confirm,Icon.CHECK,w-154,h-48,130,30,true,()->{if(System.nanoTime()-started>=3_000_000_000L)WorldControlPanel.acknowledgeAndPreview(parent);});}finally{R2D.popAlpha();}
        }finally{R2D.end();endFrame();}
    }
    @Override public boolean mouseClicked(double x,double y,int button){return dispatchClick(x,y,button);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}return super.keyPressed(key,scan,mods);}
    @Override public void onClose(){WorldControlPanel.cancelPlacement(parent);}
}
