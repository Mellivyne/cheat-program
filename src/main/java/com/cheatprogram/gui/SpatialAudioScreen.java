package com.cheatprogram.gui;

import com.cheatprogram.audio.AcousticMaterials;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.module.visual.AppCast;
import com.cheatprogram.module.visual.SpatialAudio;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The same panel typography, scrolling and glass as the main control panel. */
public final class SpatialAudioScreen extends GuiBase {
    private final Screen parent;
    private final SpatialAudio module;
    private final Scroller settingsScroll=new Scroller(),materialsScroll=new Scroller();
    private boolean routingGuide;
    public SpatialAudioScreen(Screen parent,SpatialAudio module){super(Lang.t("module.spatialaudio"));this.parent=parent;this.module=module;}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        beginFrame();if(!preparePanelBackground(graphics,partial)){endFrame();return;}R2D.begin(graphics);
        try{
            float h=layoutPanel(990,730);drawPanelSurface(graphics,990,h,22);
            c.textC(BOLD,Lang.t("module.spatialaudio"),24,31,16,Theme.TEXT);
            iconButton("spatial.close",Icon.CLOSE,960,29,14,Theme.TEXT_2,this::onClose);
            c.textC(REG,c.ellipsize(REG,Lang.t("ui.spatial.subtitle"),9,935),25,54,9,Theme.TEXT_3);
            button("spatial.bake",Lang.t("ui.spatial.rebake"),Icon.REFRESH,24,74,140,30,false,module::rebake);
            button("spatial.visual",Lang.t("ui.spatial.visualize"),Icon.EYE,176,74,148,30,module.visualizing(),()->module.visualize(!module.visualizing()));
            button("spatial.enable",Lang.t(module.isEnabled()?"ui.spatial.disable":"ui.spatial.enable"),Icon.POWER,336,74,170,30,module.isEnabled(),()->{module.toggle();Notifications.moduleToggled(module);});
            c.textC(REG,c.ellipsize(REG,module.processingStatus(),9,420),530,88,9,Theme.TEXT_2);
            float top=121,bottom=h-54,available=Math.max(40,bottom-top);
            settingsScroll.update(dt);hit(24,top,482,available).scroll(settingsScroll::scroll);c.clip(24,top,482,available);pushHitClip(24,top,482,available);
            try{
                float y=top-settingsScroll.get();
                c.textC(MED,Lang.t("ui.spatial.source"),28,y+12,12,Theme.TEXT);y+=27;
                button("spatial.screen",c.ellipsize(REG,sourceChoice(),10.5f,445),Icon.LAYOUT,24,y,474,31,false,this::nextScreen);y+=41;
                for(String line:c.wrap(REG,module.sourceLabel(),8.5f,466)){c.textC(REG,line,28,y+10,8.5f,Theme.TEXT_3);y+=13;}y+=6;
                c.textC(REG,c.ellipsize(REG,module.captureStatus(),9,466),28,y+11,9,Theme.TEXT_2);y+=22;
                if(module.usesCast()){c.textC(REG,c.ellipsize(REG,module.isolationStatus(),9,466),28,y+11,9,Theme.TEXT_2);y+=22;}
                c.textC(REG,c.ellipsize(REG,module.backendStatus(),8.5f,466),28,y+11,8.5f,Theme.TEXT_3);y+=22;
                if(module.usesCast()){for(String line:c.wrap(REG,module.denoiseStatus(),8.5f,466)){c.textC(REG,line,28,y+11,8.5f,Theme.TEXT_3);y+=14;}y+=8;}
                if(module.processingStatusKey().equals("ui.spatial.runtime.unisolated")){for(String line:c.wrap(REG,Lang.t("ui.spatial.runtime.retryHint"),8.5f,466)){c.textC(REG,line,28,y+11,8.5f,Theme.TEXT_3);y+=14;}y+=8;}
                float level=Math.max(0,Math.min(1,module.captureLevel()));
                c.rrect(28,y,466,3,1.5f,0x22ffffff);c.rrect(28,y,Math.max(1,466*level),3,1.5f,0xccffffff);y+=23;
                for(String line:c.wrap(REG,module.metrics(),9,466)){c.textC(REG,line,28,y+11,9,Theme.TEXT_2);y+=15;}
                for(String line:c.wrap(REG,module.audibility(),9,466)){c.textC(REG,line,28,y+11,9,Theme.TEXT_2);y+=15;}
                c.textC(REG,Lang.t("ui.spatial.probeProgress",Math.round(module.progress()*100)),28,y+12,8.5f,Theme.TEXT_3);y+=30;
                button("spatial.qualityPreset",Lang.t("ui.spatial.qualityPreset"),Icon.SPARKLE,24,y,474,30,false,module::applyRealisticDefaults);y+=40;
                for(Setting<?> s:module.settings()){
                    if(!s.isVisible())continue;
                    if(s instanceof BoolSetting value){button("spatial.option."+s.id,s.name(),value.on()?Icon.CHECK:Icon.CLOSE,24,y,474,30,value.on(),()->value.set(!value.on()));y+=40;}
                    else if(s instanceof ModeSetting value){button("spatial.option."+s.id,s.name()+" · "+value.display(),Icon.CHEVRON_RIGHT,24,y,474,30,false,()->value.set(value.modes.get((value.modes.indexOf(value.get())+1)%value.modes.size())));y+=40;}
                    else if(s instanceof NumberSetting value){c.textC(REG,s.name(),28,y+12,10.5f,Theme.TEXT_2);c.textRight(REG,value.format(),494,y+12,10.5f,Theme.TEXT);slider(value,32,y+35,458);y+=59;}
                }
                if(module.nvidiaRequested()){
                    button("spatial.nvidia.folder",Lang.t("ui.spatial.nvidia.folder"),Icon.FOLDER,24,y,474,30,false,module::openNvidiaDirectory);y+=40;
                    button("spatial.nvidia.docs",Lang.t("ui.spatial.nvidia.docs"),Icon.LINK,24,y,474,30,false,()->net.minecraft.Util.getPlatform().openUri("https://docs.nvidia.com/maxine/afx/latest/WindowsAFXSDK/InstallTheAFXSDK.html"));y+=40;
                    for(String line:c.wrap(REG,Lang.t("ui.spatial.nvidia.hint"),8.5f,466)){c.textC(REG,line,28,y+11,8.5f,Theme.TEXT_3);y+=14;}y+=8;
                }
                for(String key:List.of("ui.spatial.captureHint","ui.spatial.cacheHint","ui.spatial.approximation")){
                    for(String line:c.wrap(REG,Lang.t(key),8.5f,466)){c.textC(REG,line,28,y+11,8.5f,Theme.TEXT_3);y+=14;}y+=8;
                }
                settingsScroll.max=Math.max(0,y+10-(top-settingsScroll.get())-available);
            }finally{popHitClip();c.unclip();}panelScrollBar("spatial.settings.scroll",settingsScroll,510,top,available);
            if(routingGuide)routingPane(530,top,436,available);else materialPane(530,top,436,available);
            button("spatial.back",Lang.t("ui.back"),Icon.BACK,24,h-39,100,27,false,this::onClose);
            button("spatial.environment",Lang.t("ui.spatial.env.open"),Icon.WRENCH,136,h-39,200,27,false,()->mc.setScreen(new SpatialAudioEnvironmentScreen(this,module)));
            button("spatial.routing",Lang.t("ui.spatial.routing"),Icon.GEAR,348,h-39,148,27,routingGuide,()->routingGuide=!routingGuide);
            button("spatial.reconnect",Lang.t("ui.spatial.reconnect"),Icon.REFRESH,508,h-39,188,27,false,module::reconnect);
            button("spatial.world",Lang.t("ui.spatial.showInWorld"),Icon.EYE,714,h-39,252,27,false,()->{module.visualize(true);if(!module.isEnabled()){module.toggle();Notifications.moduleToggled(module);}mc.setScreen(null);});
        }finally{R2D.end();endFrame();}
    }
    private String sourceChoice(){return module.backgroundAudio()?Lang.t("ui.spatial.chooseApp"):module.screenIndex()<0?Lang.t("ui.spatial.selectedCast"):Lang.t("ui.spatial.castNumber",module.screenIndex()+1);}
    private void nextScreen(){
        if(module.backgroundAudio()){mc.setScreen(new SpatialAudioSourceScreen(this,module));return;}
        List<Integer> values=new ArrayList<>();values.add(-1);for(AppCast.AudioSource item:module.availableSources())values.add(item.index());
        int next=(values.indexOf(module.screenIndex())+1)%values.size();module.screenIndex(values.get(next));
    }
    private void materialPane(float x,float y,float w,float h){
        c.rrect(x,y,w,h,14,0x18000000);c.textC(MED,Lang.t("ui.spatial.materials"),x+14,y+22,12,Theme.TEXT);
        float hintY=y+44;for(String line:c.wrap(REG,Lang.t("ui.spatial.materialHint"),8.5f,w-28)){c.textC(REG,line,x+14,hintY,8.5f,Theme.TEXT_3);hintY+=14;}
        float top=hintY+12,available=Math.max(20,y+h-top-10);
        materialsScroll.update(dt);hit(x+8,top,w-16,available).scroll(materialsScroll::scroll);c.clip(x+8,top,w-16,available);pushHitClip(x+8,top,w-16,available);
        try{
            float py=top-materialsScroll.get();
            for(var profile:AcousticMaterials.profiles()){
                c.rrect(x+12,py,w-28,68,10,0x10000000);
                c.textC(MED,Lang.t("ui.spatial.material."+profile.category()),x+24,py+18,11,Theme.TEXT);
                String values=String.format(Locale.ROOT,"%.2f  /  %.2f  /  %.2f",profile.lowAbsorption(),profile.midAbsorption(),profile.highAbsorption());
                c.textC(REG,Lang.t("ui.spatial.absorptionBands",values),x+24,py+38,8.5f,Theme.TEXT_2);
                c.textC(REG,Lang.t("ui.spatial.scatterValue",String.format(Locale.ROOT,"%.2f",profile.scattering())),x+24,py+54,8.5f,Theme.TEXT_3);py+=78;
            }
            materialsScroll.max=Math.max(0,py-(top-materialsScroll.get())-available);
        }finally{popHitClip();c.unclip();}panelScrollBar("spatial.material.scroll",materialsScroll,x+w-8,top,available);
    }
    private void routingPane(float x,float y,float w,float h){
        c.rrect(x,y,w,h,14,0x18000000);c.textC(MED,Lang.t("ui.spatial.routing"),x+14,y+22,12,Theme.TEXT);
        float py=y+49;
        for(String line:c.wrap(REG,Lang.t("ui.spatial.routingHint"),10.5f,w-28)){c.textC(REG,line,x+14,py,10.5f,Theme.TEXT_2);py+=19;}
        py+=18;
        button("spatial.windowsMixer",Lang.t("ui.spatial.windowsMixer"),Icon.GEAR,x+14,py,w-28,31,false,()->net.minecraft.Util.getPlatform().openUri("ms-settings:apps-volume"));py+=43;
        button("spatial.virtualDevice",Lang.t("ui.spatial.virtualDevice"),Icon.GLOBE,x+14,py,w-28,31,false,()->net.minecraft.Util.getPlatform().openUri("https://vb-audio.com/Cable/"));
    }
    @Override public boolean mouseClicked(double x,double y,int button){dispatchClick(x,y,button);return true;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return dispatchScroll(x,y,vertical);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}return false;}
    @Override public void onClose(){mc.setScreen(parent);}
}
