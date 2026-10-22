package com.cheatprogram.gui;

import com.cheatprogram.core.Config;
import com.cheatprogram.core.ConfigReset;
import com.cheatprogram.core.Lang;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

/** Reviewable reset scope and restart notice, using the normal panel font and layout. */
public final class ConfigResetScreen extends GuiBase {
    private final Screen parent;
    private final Scroller scroll = new Scroller();
    private boolean acknowledged;
    public ConfigResetScreen(Screen parent) { super(Lang.t("ui.reset.title")); this.parent = parent; }
    @Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        beginFrame(); if(!preparePanelBackground(graphics,partialTick)){endFrame();return;}R2D.begin(graphics);
        try {
            float height=layoutPanel(720,480);drawPanelSurface(graphics,720,height,24);
            c.textC(BOLD,Lang.t("ui.reset.title"),24,29,16,Theme.TEXT);
            if(!ConfigReset.busy())iconButton("reset.close",Icon.CLOSE,692,29,16,Theme.TEXT_2,this::onClose);
            float top=66,bodyHeight=Math.max(80,height-132);scroll.update(dt);
            hit(20,top,680,bodyHeight).scroll(scroll::scroll);c.clip(20,top,680,bodyHeight);pushHitClip(20,top,680,bodyHeight);
            try {
                float y=top-scroll.get();
                y=paragraph(Lang.t("ui.reset.scope"),y,12,Theme.TEXT)+16;
                y=paragraph(ConfigReset.directory().toString(),y,10.5f,Theme.TEXT_2)+18;
                y=paragraph(Lang.t("ui.reset.contains"),y,11.5f,Theme.TEXT_2)+16;
                y=paragraph(Lang.t("ui.reset.preserve"),y,10,Theme.TEXT_3)+16;
                y=paragraph(Lang.t("ui.reset.restartHint"),y,11.5f,Theme.TEXT_2)+16;
                y=paragraph(Lang.t("ui.reset.recycleHint"),y,11,Theme.TEXT_2)+18;
                if(ConfigReset.state()==ConfigReset.State.READY||ConfigReset.state()==ConfigReset.State.FAILED&&!Config.restartRequired()){
                    c.textC(MED,Lang.t("ui.reset.acknowledge"),26,y+10,11.5f,Theme.TEXT);
                    toggle("reset.acknowledge",654,y-1,acknowledged,1f);
                    hit(646,y-8,50,36).left(()->acknowledged=!acknowledged);y+=36;
                }
                if(ConfigReset.state()!=ConfigReset.State.READY)y=paragraph(Lang.t(ConfigReset.statusKey()),y,12,Theme.TEXT)+12;
                if(Config.restartRequired())y=paragraph(Lang.t("ui.reset.restartReminder"),y,11.5f,Theme.TEXT_2)+12;
                scroll.max=Math.max(0,y+scroll.get()+12-top-bodyHeight);
            } finally {popHitClip();c.unclip();}
            panelScrollBar("reset.scroll",scroll,705,top,bodyHeight);
            if(!ConfigReset.busy())button("reset.cancel",Lang.t(Config.restartRequired()?"ui.reset.backGame":"ui.cancel"),Icon.BACK,24,height-44,200,28,false,this::onClose);
            if(acknowledged&&!Config.restartRequired()&&!ConfigReset.busy())
                button("reset.confirm",Lang.t("ui.reset.confirm"),Icon.TRASH,430,height-44,266,28,true,ConfigReset::initialize);
            else if(ConfigReset.busy())c.textMid(MED,Lang.t(ConfigReset.statusKey()),550,height-30,10.5f,Theme.TEXT_2);
        } finally {R2D.end();endFrame();}
    }
    private float paragraph(String text,float y,float size,int color){for(String line:c.wrap(REG,text,size,660)){c.textC(REG,line,26,y+8,size,color);y+=size+7;}return y;}
    @Override public boolean mouseClicked(double x,double y,int button){return dispatchClick(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return dispatchScroll(x,y,vertical);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}return true;}
    @Override public void onClose(){if(!ConfigReset.busy())mc.setScreen(Config.restartRequired()?null:parent);}
}
