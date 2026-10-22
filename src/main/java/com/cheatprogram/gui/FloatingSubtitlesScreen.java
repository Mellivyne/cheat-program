package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.module.visual.FloatingSubtitles;
import com.cheatprogram.media.player.MusicPlayer;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Music-player lyrics and their world-space appearance. Source text retains the player's language. */
public final class FloatingSubtitlesScreen extends GuiBase {
    private final Screen parent;
    private final FloatingSubtitles module;
    private final Scroller options=new Scroller(),sourceScroll=new Scroller();
    public FloatingSubtitlesScreen(Screen parent,FloatingSubtitles module){super(Lang.t("module.floatingsubtitles"));this.parent=parent;this.module=module;}
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        beginFrame();if(!preparePanelBackground(graphics,partial)){endFrame();return;}R2D.begin(graphics);
        try{
            float h=layoutPanel(990,730);drawPanelSurface(graphics,990,h,22);
            c.textC(BOLD,Lang.t("module.floatingsubtitles"),24,31,16,Theme.TEXT);
            iconButton("lyrics.close",Icon.CLOSE,960,29,14,Theme.TEXT_2,this::onClose);
            c.textC(REG,c.ellipsize(REG,Lang.t("ui.music.captionHint"),9,936),25,54,9,Theme.TEXT_3);
            button("lyrics.enable",Lang.t(module.isEnabled()?"ui.lyrics.disable":"ui.lyrics.enable"),Icon.POWER,24,74,170,30,module.isEnabled(),()->{module.toggle();Notifications.moduleToggled(module);});
            button("lyrics.preview",Lang.t("ui.lyrics.preview"),Icon.EYE,206,74,170,30,false,()->{module.preview();mc.setScreen(null);});
            button("lyrics.recenter",Lang.t("ui.lyrics.recenter"),Icon.NAVIGATION,388,74,170,30,false,module::recenter);
            button("lyrics.reload",Lang.t("ui.lyrics.reload"),Icon.REFRESH,570,74,170,30,false,module::reload);
            c.textC(REG,c.ellipsize(REG,module.primaryStatus(),9,935),28,113,9,Theme.TEXT_2);
            float top=132,bottom=h-52,available=Math.max(40,bottom-top);
            options.update(dt);hit(24,top,474,available).scroll(options::scroll);c.clip(24,top,474,available);pushHitClip(24,top,474,available);
            try{
                float y=top-options.get();
                for(Setting<?> setting:module.settings()){
                    if(!setting.isVisible())continue;
                    if(setting instanceof BoolSetting b){button("lyrics.option."+setting.id,setting.name(),b.on()?Icon.CHECK:Icon.CLOSE,24,y,474,30,b.on(),()->b.set(!b.on()));y+=40;}
                    else if(setting instanceof ModeSetting m){button("lyrics.option."+setting.id,setting.name()+" · "+m.display(),Icon.CHEVRON_RIGHT,24,y,474,30,false,()->m.set(m.modes.get((m.modes.indexOf(m.get())+1)%m.modes.size())));y+=40;}
                    else if(setting instanceof NumberSetting n){
                        c.textC(REG,c.ellipsize(REG,setting.name(),10.5f,282),28,y+12,10.5f,Theme.TEXT_2);
                        c.textRight(REG,n.format(),432,y+12,10.5f,Theme.TEXT);
                        numberStep(n,-1,440,y+1);numberStep(n,1,469,y+1);
                        slider(n,32,y+35,458);y+=59;
                    }
                }
                options.max=Math.max(0,y+8-(top-options.get())-available);
            }finally{popHitClip();c.unclip();}panelScrollBar("lyrics.settings.scroll",options,510,top,available);
            c.rrect(530,top,436,available,14,0x18000000);
            sourceScroll.update(dt);hit(538,top+8,420,available-16).scroll(sourceScroll::scroll);c.clip(538,top+8,420,available-16);pushHitClip(538,top+8,420,available-16);
            try{
                float y=top+25-sourceScroll.get();
                c.textC(MED,Lang.t("ui.music.title"),546,y,12,Theme.TEXT);y+=23;
                button("lyrics.player",Lang.t("ui.music.open"),Icon.MUSIC,544,y,408,31,false,()->mc.setScreen(new MusicPlayerScreen(this,module)));y+=48;
                y=hint(Lang.t("ui.music.captionHint"),y,Theme.TEXT_2);
                y=hint(Lang.t("ui.lyrics.positionHint"),y+8,Theme.TEXT_3);
                y=hint(Lang.t("ui.lyrics.historyOnly"),y+8,Theme.TEXT_3);
                var state=module.snapshot();var playback=state.playback();
                y=hint(state.provider().label,y+15,Theme.TEXT);
                if(playback!=null&&playback.track()!=null)y=hint(playback.track().title()+" · "+playback.track().artist(),y+5,Theme.TEXT_2);
                y=hint(module.primaryStatus(),y+16,Theme.TEXT_2);
                if(state.lyrics()!=null&&playback!=null){double seconds=Math.max(0,playback.position()/1000.0+module.timeOffset.val()),duration=playback.durationMs()/1000.0;int at=state.lyrics().lineAt(seconds,duration);if(at>=0){var line=state.lyrics().lines().get(at);y=hint(Lang.t("ui.lyrics."+line.timing()),y+8,Theme.TEXT_3);String visible=module.futurePreview.on()?line.text():state.lyrics().currentUnit(at,seconds,duration);if(!visible.isEmpty())y=hint(visible,y+6,Theme.TEXT);}}
                if(module.translation.on()){y=hint(Lang.t(module.translationStatus()),y+8,Theme.TEXT_3);y=hint(Lang.t("ui.lyrics.translationTimingHint"),y+8,Theme.TEXT_3);}
                y=hint(Lang.t("ui.music.privacyDetails"),y+18,Theme.TEXT_3);
                sourceScroll.max=Math.max(0,y+10-(top+25-sourceScroll.get())-(available-35));
            }finally{popHitClip();c.unclip();}panelScrollBar("lyrics.source.scroll",sourceScroll,960,top+8,available-16);
            button("lyrics.back",Lang.t("ui.back"),Icon.BACK,24,h-39,100,27,false,this::onClose);
            c.textC(REG,c.ellipsize(REG,Lang.t("ui.lyrics.worldHint"),8.5f,820),140,h-25,8.5f,Theme.TEXT_3);
        }finally{R2D.end();endFrame();}
    }
    private void numberStep(NumberSetting setting,int direction,float x,float y){
        boolean hovered=over(x,y,23,23);c.rrect(x,y,23,23,6,hovered?0x28FFFFFF:0x12FFFFFF);
        c.textC(MED,direction<0?"−":"+",x+7,y+11,12,Theme.TEXT);
        hit(x,y,23,23).left(()->setting.set(setting.val()+direction*setting.step));
    }
    /** Follow the pointer directly while dragging; smoothing belongs to the world anchor. */
    @Override protected void slider(NumberSetting setting,float x,float y,float width){
        float value=(float)setting.normalized();boolean active=drag!=null&&draggingKey==setting;
        float shown=value;boolean hovered=over(x-4,y-9,width+8,18);
        float emphasis=hoverAnim(setting.id+"#lyricsSlider",hovered||active);
        c.rrect(x,y-1.5f,width,3,1.5f,Anim.white(.14f));c.rrect(x,y-1.5f,Math.max(3,width*shown),3,1.5f,Anim.white(.92f));
        float radius=5+emphasis,kx=x+width*shown;c.shadow(kx-radius,y-radius,radius*2,radius*2,radius,3,Anim.black(.45f));c.circle(kx,y,radius,0xFFFFFFFF);
        hit(x-6,y-10,width+12,20).click(button->{if(button==1){openNumberInput(setting);return;}if(button!=0)return;draggingKey=setting;drag=(px,py)->setting.setNormalized((px-x)/width);drag.drag(mx,my);});
        numberInputAccess(setting,x,y,width);
    }
    private float hint(String text,float y,int color){for(String line:c.wrap(REG,text,8.5f,398)){c.textC(REG,line,548,y+9,8.5f,color);y+=14;}return y+4;}
    @Override public boolean mouseClicked(double x,double y,int button){if(!dispatchClick(x,y,button))focused=null;return true;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return dispatchScroll(x,y,vertical);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}return false;}
    @Override public void onClose(){mc.setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
}
