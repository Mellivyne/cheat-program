package com.cheatprogram.gui;

import com.cheatprogram.compat.gl.BufferUploader;

import com.cheatprogram.capture.AppCaptureEngine;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.util.KeyNames;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.module.visual.AppCast;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.compat.gl.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import com.cheatprogram.compat.gl.CoreShaders;
import org.lwjgl.glfw.GLFW;
import java.util.List;
import java.util.Locale;

/** Window selection is explicit, local and observation-only. */
public final class AppCastScreen extends GuiBase {
    private final Screen parent;private final AppCast module;
    private final TextField search=new TextField(128);
    private final Scroller windowsScroll=new Scroller(),settingsScroll=new Scroller();
    private List<AppCaptureEngine.Window> windows=List.of();
    private boolean refreshing,closed,binding,relatedOnly;private long generation,nextRefresh;private String status="";
    public AppCastScreen(Screen parent,AppCast module){super(Lang.t("ui.appcast.title"));this.parent=parent;this.module=module;refresh();}
    private void refresh(){refresh(true);}
    private void refresh(boolean manual){
        if(refreshing||!AppCaptureEngine.supported())return;refreshing=true;status="";long ticket=++generation;
        nextRefresh=System.nanoTime()+2_000_000_000L;
        AppCaptureEngine.windows().whenComplete((found,error)->mc.execute(()->{if(closed||ticket!=generation)return;refreshing=false;if(error!=null){status="ui.appcast.listFailed";return;}windows=found;if(manual)windowsScroll.target=0;}));
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial){
        beginFrame();if(!preparePanelBackground(graphics,partial)){endFrame();return;}R2D.begin(graphics);
        try{
            if(!refreshing&&System.nanoTime()>nextRefresh)refresh(false);
            float h=layoutPanel(880,700);drawPanelSurface(graphics,880,h,22);
            c.textC(BOLD,Lang.t("ui.appcast.title"),24,29,16,Theme.TEXT);
            iconButton("cast.close",Icon.CLOSE,852,29,14,Theme.TEXT_2,this::onClose);
            c.textC(REG,c.ellipsize(REG,Lang.t("ui.appcast.readOnly"),8.5f,826),26,54,8.5f,Theme.TEXT_3);
            textField(search,Lang.t("ui.appcast.search"),Icon.SEARCH,24,74,304,30);
            button("cast.refresh",Lang.t(refreshing?"ui.appcast.loading":"ui.appcast.refresh"),Icon.REFRESH,340,74,104,30,false,this::refresh);
            List<AppCast.ScreenEntry> castScreens=module.screenEntries();
            button("cast.previous","",Icon.CHEVRON_LEFT,468,74,32,30,false,()->module.selectScreen((module.selectedIndex()+castScreens.size()-1)%castScreens.size()));
            button("cast.current",Lang.t("ui.appcast.screen")+" "+(module.selectedIndex()+1)+" / "+castScreens.size(),Icon.LAYOUT,506,74,190,30,true,()->module.selectScreen((module.selectedIndex()+1)%castScreens.size()));
            button("cast.add",Lang.t("ui.appcast.addScreen"),Icon.PLUS,704,74,72,30,false,module::addScreen);
            button("cast.delete",Lang.t("ui.appcast.deleteScreen"),Icon.TRASH,784,74,72,30,false,module::deleteScreen);
            button("cast.views",Lang.t(relatedOnly?"ui.appcast.relatedViews":"ui.appcast.allWindows"),Icon.LAYOUT,24,112,420,28,relatedOnly,()->{relatedOnly=!relatedOnly;windowsScroll.target=0;refresh(false);});
            float top=150,bottom=h-62,available=Math.max(40,bottom-top);
            windowsScroll.update(dt);hit(24,top,420,available).scroll(windowsScroll::scroll);c.clip(24,top,420,available);pushHitClip(24,top,420,available);
            int count=0;
            try{
                String filter=search.text.strip().toLowerCase(Locale.ROOT);
                for(AppCaptureEngine.Window window:windows){if(!window.label().toLowerCase(Locale.ROOT).contains(filter)||relatedOnly&&!module.relatedWindow(window))continue;
                    float y=top+count++*54-windowsScroll.get();if(y+50<top||y>bottom)continue;
                    boolean selected=module.selectedSource(window);
                    button("cast.window."+window.sourceKey(),c.ellipsize(MED,window.title(),11,392),null,24,y,420,30,selected,()->{module.switchWindow(window);if(window.desktop())relatedOnly=false;});
                    String source=Lang.t(window.desktop()?"ui.appcast.desktopSource":"ui.appcast.windowSource")+" · "+window.process()+(window.desktop()?"":" · "+window.pid());
                    c.textC(REG,c.ellipsize(REG,source+(window.minimized()?" · "+Lang.t("ui.appcast.minimizedShort"):""),8.5f,396),36,y+41,8.5f,Theme.TEXT_3);
                }
                if(count==0)c.textC(REG,Lang.t(refreshing?"ui.appcast.loading":"ui.appcast.noWindows"),36,top+25,10.5f,Theme.TEXT_3);
            }finally{popHitClip();c.unclip();}windowsScroll.max=Math.max(0,count*54-available);panelScrollBar("cast.windows.scroll",windowsScroll,449,top,available);
            float right=468,w=388,previewH=Math.min(180,Math.max(80,available*.35f));
            c.rrect(right,top,w,previewH,8,0xb3101318);
            AppCaptureEngine.Texture texture=module.image();
            if(texture!=null){float ratio=texture.width()/(float)texture.height(),pw=Math.min(w,previewH*ratio),ph=pw/ratio;preview(graphics,texture,right+(w-pw)/2,top+(previewH-ph)/2,pw,ph);R2D.applyState();}
            else c.textMid(REG,c.ellipsize(REG,Lang.t(module.statusKey()),10,w-24),right+w/2,top+previewH/2,10,Theme.TEXT_2);
            float paneTop=top+previewH+12,paneH=Math.max(24,bottom-paneTop);
            settingsScroll.update(dt);hit(right,paneTop,w,paneH).scroll(settingsScroll::scroll);c.clip(right,paneTop,w,paneH);pushHitClip(right,paneTop,w,paneH);
            try{
                float y=paneTop-settingsScroll.get();
                c.textC(REG,c.ellipsize(REG,module.selectedLabel(),11,w-12),right+4,y+13,11,Theme.TEXT);y+=28;
                List<AppCaptureEngine.Window> related=windows.stream().filter(module::relatedWindow).toList();
                int view=-1;for(int i=0;i<related.size();i++)if(module.selectedSource(related.get(i)))view=i;
                final int selectedView=view;
                if(!module.selectedDesktop()){
                button("cast.view.previous","",Icon.CHEVRON_LEFT,right,y,32,27,false,()->{if(!related.isEmpty())module.switchWindow(related.get(selectedView<0?related.size()-1:(selectedView+related.size()-1)%related.size()));});
                button("cast.view.count",Lang.t("ui.appcast.appView")+" "+(view+1)+" / "+related.size(),Icon.LAYOUT,right+40,y,w-80,27,relatedOnly,()->{relatedOnly=true;refresh(false);});
                button("cast.view.next","",Icon.CHEVRON_RIGHT,right+w-32,y,32,27,false,()->{if(!related.isEmpty())module.switchWindow(related.get((selectedView+1)%related.size()));});y+=37;}
                c.textC(REG,c.ellipsize(REG,Lang.t(status.isEmpty()?module.statusKey():status),9,w-12),right+4,y+12,9,Theme.TEXT_2);y+=30;
                for(String line:c.wrap(REG,module.liveStats(),8.5f,w-12)){c.textC(REG,line,right+4,y+11,8.5f,Theme.TEXT_3);y+=14;}
                c.textC(REG,c.ellipsize(REG,module.unchangedStats(),8.5f,w-12),right+4,y+11,8.5f,Theme.TEXT_3);y+=24;
                button("cast.follow",module.anchorSetting().name()+" · "+module.anchorSetting().display(),Icon.NAVIGATION,right,y,w,29,!module.anchorSetting().is("appcast_fixed"),module::cycleAnchor);y+=40;
                ModeSetting backend=(ModeSetting)module.settings().stream().filter(s->s.id.equals("backend")).findFirst().orElseThrow();
                button("cast.backend",backend.name()+" · "+backend.display(),Icon.EYE,right,y,w,29,false,()->backend.set(backend.modes.get((backend.modes.indexOf(backend.get())+1)%backend.modes.size())));y+=38;
                for(String id:List.of("nativeResolution","nativeFrameRate","sharp","windowedLiveCompatible")){BoolSetting value=(BoolSetting)module.settings().stream().filter(s->s.id.equals(id)).findFirst().orElseThrow();button("cast.option."+id,value.name(),value.on()?Icon.CHECK:Icon.CLOSE,right,y,w,29,value.on(),()->value.set(!value.on()));y+=38;if(id.equals("windowedLiveCompatible")){for(String line:c.wrap(REG,Lang.t("setting.appcast.windowedLiveCompatible.hint"),8.5f,w-12)){c.textC(REG,line,right+4,y+11,8.5f,Theme.TEXT_3);y+=14;}y+=8;}}
                if(module.selectedDesktop()){
                    for(String line:c.wrap(REG,Lang.t("ui.appcast.desktopViewOnly"),8.5f,w-12)){c.textC(REG,line,right+4,y+11,8.5f,Theme.TEXT_3);y+=14;}y+=8;
                }else{
                button("cast.interaction",Lang.t("ui.appcast.allowInput"),Icon.KEYBOARD,right,y,w,29,module.interactionEnabled(),()->module.interaction(!module.interactionEnabled()));y+=38;
                if(module.interactionEnabled()){
                    ModeSetting inputMode=module.inputModeSetting();
                    button("cast.inputMode",inputMode.name()+" · "+inputMode.display(),Icon.KEYBOARD,right,y,w,29,false,()->inputMode.set(inputMode.modes.get((inputMode.modes.indexOf(inputMode.get())+1)%inputMode.modes.size())));y+=38;
                    if(inputMode.is("appcast_native_foreground")){for(String line:c.wrap(REG,Lang.t("ui.appcast.nativeInputHint"),8.5f,w-12)){c.textC(REG,line,right+4,y+11,8.5f,Theme.TEXT_3);y+=14;}y+=8;}
                }
                button("cast.inputKey",Lang.t(binding?"ui.keybind.waiting":module.keyboardOnly()?"ui.appcast.toggleKey":"ui.appcast.holdKey")+(binding?"":" · "+KeyNames.name(module.interactionKey())),Icon.KEYBOARD,right,y,w,29,binding,()->{binding=!binding;focused=null;drag=null;status="";});y+=38;
                button("cast.keyboardOnly",Lang.t("setting.appcast.keyboardOnly"),Icon.KEYBOARD,right,y,w,29,module.keyboardOnly(),()->module.keyboardOnly(!module.keyboardOnly()));y+=38;
                if(module.keyboardOnly()){
                    for(String line:c.wrap(REG,Lang.t("ui.appcast.keyboardOnlyHint"),8.5f,w-12)){c.textC(REG,line,right+4,y+11,8.5f,Theme.TEXT_3);y+=14;}y+=8;
                }else{
                    button("cast.typing",Lang.t(module.moveFirst()?"mode.appcast_move_first":"mode.appcast_type_first"),Icon.USER,right,y,w,29,module.moveFirst(),()->module.moveFirst(!module.moveFirst()));y+=38;
                }
                }
                for(String id:List.of("width","x","y","z","yaw","pitch","roll","fps","resolution","pixelLimit","uploadBudget","opacity","brightness","pointerSpeed")){
                    NumberSetting value=(NumberSetting)module.settings().stream().filter(s->s.id.equals(id)).findFirst().orElseThrow();
                    if(!value.isVisible()||module.selectedDesktop()&&id.equals("pointerSpeed"))continue;
                    c.textC(REG,value.name(),right+4,y+12,10.5f,Theme.TEXT_2);c.textRight(REG,value.format(),right+w-4,y+12,10.5f,Theme.TEXT);slider(value,right+8,y+35,w-16);y+=58;
                }
                for(String key:List.of("ui.appcast.viewHint","ui.appcast.nativeHint","ui.appcast.inputHint","ui.appcast.scrollHint","ui.appcast.captureHint"))for(String line:c.wrap(REG,Lang.t(key),8.5f,w-12)){c.textC(REG,line,right+4,y+12,8.5f,Theme.TEXT_3);y+=14;}
                settingsScroll.max=Math.max(0,y+14-(paneTop-settingsScroll.get())-paneH);
            }finally{popHitClip();c.unclip();}panelScrollBar("cast.settings.scroll",settingsScroll,862,paneTop,paneH);
            button("cast.back",Lang.t("ui.back"),Icon.BACK,24,h-43,96,28,false,this::onClose);
            button("cast.retry",Lang.t("ui.appcast.retry"),Icon.REFRESH,132,h-43,130,28,false,module::retry);
            button("cast.edit",Lang.t("ui.appcast.edit"),Icon.CUBE,468,h-43,186,28,false,()->{if(mc.level!=null){if(!module.isEnabled())module.setEnabled(true);mc.setScreen(new AppCastEditorScreen(this,module));}});
            button("cast.enable",Lang.t(module.isEnabled()?"ui.appcast.disable":"ui.appcast.enable"),Icon.POWER,666,h-43,190,28,module.isEnabled(),()->{module.toggle();Notifications.moduleToggled(module);});
        }finally{R2D.end();endFrame();}
    }
    private void preview(GuiGraphics graphics,AppCaptureEngine.Texture image,float x,float y,float w,float h){
        float scale=(float)mc.getWindow().getGuiScale();var pose=com.cheatprogram.compat.GuiBridge.pose(graphics);
        RenderSystem.setShader(CoreShaders.POSITION_TEX_COLOR);RenderSystem.setShaderTexture(0,image.id());RenderSystem.setShaderColor(1,1,1,1);
        BufferBuilder b=Tesselator.getInstance().begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_TEX_COLOR);
        b.addVertex(pose,c.px(x)/scale,c.py(y)/scale,0).setUv(0,0).setColor(-1);b.addVertex(pose,c.px(x)/scale,c.py(y+h)/scale,0).setUv(0,1).setColor(-1);
        b.addVertex(pose,c.px(x+w)/scale,c.py(y+h)/scale,0).setUv(1,1).setColor(-1);b.addVertex(pose,c.px(x+w)/scale,c.py(y)/scale,0).setUv(1,0).setColor(-1);BufferUploader.drawWithShader(b.buildOrThrow());
    }
    @Override public boolean mouseClicked(double x,double y,int button){if(!dispatchClick(x,y,button))focused=null;return true;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return dispatchScroll(x,y,vertical);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(binding){if(key==ClientSettings.INSTANCE.guiKey||key==GLFW.GLFW_KEY_RIGHT_SHIFT||key==GLFW.GLFW_KEY_LEFT_CONTROL){status="ui.appcast.reservedKey";return true;}binding=false;status="";module.interactionKey(key==GLFW.GLFW_KEY_ESCAPE?-1:key);return true;}if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}return focused!=null&&focused.keyPressed(key,mods);}
    @Override public boolean charTyped(char ch,int mods){return focused!=null&&focused.charTyped(ch);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){closed=true;generation++;mc.setScreen(parent);}
    @Override public void removed(){closed=true;generation++;super.removed();}
    @Override protected void init(){closed=false;super.init();if(windows.isEmpty()){refreshing=false;refresh();}}
}
