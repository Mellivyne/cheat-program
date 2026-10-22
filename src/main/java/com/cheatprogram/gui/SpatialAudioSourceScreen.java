package com.cheatprogram.gui;

import com.cheatprogram.capture.AppCaptureEngine;
import com.cheatprogram.core.Lang;
import com.cheatprogram.module.visual.SpatialAudio;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import java.util.List;
import java.util.Locale;

/** Selects one application for audio only; it does not start video capture or use system-wide audio. */
public final class SpatialAudioSourceScreen extends GuiBase {
    private final Screen parent;private final SpatialAudio module;
    private final TextField search=new TextField(128);private final Scroller scroll=new Scroller();
    private List<AppCaptureEngine.Window> windows=List.of();private boolean loading,closed;private long generation;private String status="";
    public SpatialAudioSourceScreen(Screen parent,SpatialAudio module){super(Lang.t("ui.spatial.chooseApp"));this.parent=parent;this.module=module;}
    @Override protected void init(){super.init();closed=false;refresh();}
    private void refresh(){if(loading)return;loading=true;status="";long ticket=++generation;
        AppCaptureEngine.windows().whenComplete((items,error)->mc.execute(()->{if(closed||ticket!=generation)return;loading=false;if(error!=null){status="ui.appcast.listFailed";return;}windows=items.stream().filter(item->!item.desktop()&&item.pid()>4).toList();scroll.target=0;}));}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        beginFrame();if(!preparePanelBackground(graphics,partial)){endFrame();return;}R2D.begin(graphics);
        try{float h=layoutPanel(820,650);drawPanelSurface(graphics,820,h,22);
            c.textC(BOLD,Lang.t("ui.spatial.chooseApp"),24,29,16,Theme.TEXT);
            iconButton("spatial.app.close",Icon.CLOSE,792,29,14,Theme.TEXT_2,this::onClose);
            textField(search,Lang.t("ui.spatial.appSearch"),Icon.SEARCH,24,62,600,31);
            button("spatial.app.refresh",Lang.t(loading?"ui.appcast.loading":"ui.spatial.refreshApps"),Icon.REFRESH,636,62,160,31,false,this::refresh);
            float top=110,available=h-170;scroll.update(dt);hit(24,top,772,available).scroll(scroll::scroll);c.clip(24,top,772,available);pushHitClip(24,top,772,available);
            int count=0;try{String filter=search.text.strip().toLowerCase(Locale.ROOT);var selected=module.selectedSource();
                for(var window:windows){if(!window.label().toLowerCase(Locale.ROOT).contains(filter))continue;
                    float y=top+count++*61-scroll.get();if(y+58<top||y>top+available)continue;
                    button("spatial.app."+window.handle(),c.ellipsize(MED,window.title(),11,740),Icon.MUSIC,24,y,772,34,selected!=null&&selected.pid()==window.pid(),()->{module.selectBackground(window);onClose();});
                    c.textC(REG,c.ellipsize(REG,window.process()+" · "+window.pid(),9,740),38,y+48,9,Theme.TEXT_3);
                }
                if(count==0)c.textC(REG,Lang.t(status.isEmpty()?(loading?"ui.appcast.loading":"ui.spatial.noApps"):status),36,top+24,10.5f,Theme.TEXT_3);
            }finally{popHitClip();c.unclip();}scroll.max=Math.max(0,count*61-available);panelScrollBar("spatial.apps.scroll",scroll,802,top,available);
            button("spatial.app.back",Lang.t("ui.back"),Icon.BACK,24,h-40,110,28,false,this::onClose);
            c.textRight(REG,Lang.t("ui.spatial.selectApp"),794,h-25,8.5f,Theme.TEXT_3);
        }finally{R2D.end();endFrame();}
    }
    @Override public boolean mouseClicked(double x,double y,int button){if(!dispatchClick(x,y,button))focused=null;return true;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return dispatchScroll(x,y,vertical);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}return focused!=null&&focused.keyPressed(key,mods);}
    @Override public boolean charTyped(char ch,int mods){return focused!=null&&focused.charTyped(ch);}
    @Override public void onClose(){closed=true;generation++;mc.setScreen(parent);}
    @Override public void removed(){closed=true;generation++;super.removed();}
    @Override public boolean isPauseScreen(){return false;}
}
