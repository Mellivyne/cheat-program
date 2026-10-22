package com.cheatprogram.gui;

import com.cheatprogram.ai.voice.*;
import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.hud.AiVoiceHud;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.KeyNames;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.DoubleConsumer;

/** Microphone settings are local preferences, independent from model credentials and shareable profiles. */
public final class AiVoiceInputScreen extends GuiBase {
    private static final ExecutorService FILES=Executors.newSingleThreadExecutor(job->{Thread t=new Thread(job,"CheatProgram-speech-picker");t.setDaemon(true);return t;});
    private final Screen parent;
    private final VoiceInputConfig cfg=AiVoiceControl.config();
    private final Scroller scroll=new Scroller();
    private final TextField python=new TextField(4096),model=new TextField(4096);
    private final NumberSetting seconds,silence,threshold,timeout;
    private boolean binding,closed,loading;
    private List<String> microphones=List.of();
    private String error="";
    public AiVoiceInputScreen(Screen parent){
        super(Lang.t("ui.voiceInput.title"));this.parent=parent;
        python.setText(cfg.python);model.setText(cfg.model);python.onChange=v->cfg.python=v;model.onChange=v->cfg.model=v;
        seconds=number("voiceMaxSeconds",cfg.maxSeconds,5,300,1,v->cfg.maxSeconds=(int)v);
        silence=number("voiceSilence",cfg.silenceMillis,400,5000,50,v->cfg.silenceMillis=(int)v);
        threshold=number("voiceThreshold",cfg.threshold*100,.1,30,.1,v->cfg.threshold=v/100);
        timeout=number("voiceTimeout",cfg.recognitionTimeout,15,900,5,v->cfg.recognitionTimeout=(int)v);
        refreshMicrophones();
    }
    private NumberSetting number(String id,double initial,double min,double max,double step,DoubleConsumer callback){return new NumberSetting(id,initial,min,max,step){@Override public void set(Double value){super.set(value);callback.accept(val());}};}
    private void refreshMicrophones(){loading=true;AiVoiceControl.microphones().whenComplete((list,err)->mc.execute(()->{if(closed)return;loading=false;if(err==null)microphones=list;else error="ui.voiceInput.micFailed";}));}
    @Override public void render(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partial){
        beginFrame();if(!preparePanelBackground(graphics,partial)){endFrame();return;}R2D.begin(graphics);
        try{
            float w=760,h=layoutPanel(w,710);drawPanelSurface(graphics,w,h,24);
            c.textC(BOLD,Lang.t("ui.voiceInput.title"),24,28,15,Theme.TEXT);
            AiVoiceHud.orb(c,w-67,29,15,AiVoiceControl.busy()?"listening":"thinking",AiVoiceControl.state().level(),System.nanoTime()/1_000_000_000.0);
            iconButton("voice.close",Icon.CLOSE,w-27,28,14,Theme.TEXT_2,this::onClose);
            float top=64,bottom=h-66;hit(20,top,w-40,Math.max(0,bottom-top)).scroll(scroll::scroll);
            c.clip(20,top,w-40,Math.max(0,bottom-top));pushHitClip(20,top,w-40,Math.max(0,bottom-top));
            float y=top-scroll.get(),start=y,x=26,rw=w-52;
            y=toggleRow("enabled","ui.voiceInput.enable",cfg.enabled,()->{cfg.enabled=!cfg.enabled;if(!cfg.enabled)AiVoiceControl.cancel();cfg.save();},x,y,rw);
            button("voice.bind",Lang.t("ui.voiceInput.bind")+" · "+(binding?Lang.t("ui.keybind.waiting"):KeyNames.name(cfg.key)),Icon.KEYBOARD,x,y,rw-112,30,binding,()->{binding=!binding;focused=null;});
            button("voice.unbind",Lang.t("ui.voiceInput.unbind"),Icon.TRASH,x+rw-100,y,100,30,false,()->{cfg.key=-1;binding=false;cfg.save();});y+=43;
            for(String line:c.wrap(REG,Lang.t("ui.voiceInput.useHint"),9,rw)){c.textC(REG,line,x,y,9,Theme.TEXT_3);y+=13;}y+=12;
            y=toggleRow("autoSend","ui.voiceInput.autoSend",cfg.autoSend,()->{cfg.autoSend=!cfg.autoSend;cfg.save();},x,y,rw);
            y=toggleRow("autoStop","ui.voiceInput.autoStop",cfg.autoStop,()->{cfg.autoStop=!cfg.autoStop;cfg.save();},x,y,rw);
            y=toggleRow("showHud","ui.voiceInput.showHud",cfg.showHud,()->{cfg.showHud=!cfg.showHud;cfg.save();},x,y,rw);
            button("voice.engine",Lang.t("ui.voiceInput.engine")+" · "+Lang.t(cfg.engine.equals("windows")?"ui.voiceInput.windows":"ui.voiceInput.whisper"),Icon.ACTIVITY,x,y,rw,30,false,()->{AiVoiceControl.cancel();cfg.engine=cfg.engine.equals("windows")?"whisper":"windows";cfg.save();});y+=43;
            String lang=Lang.mode("ai_language_"+cfg.language);
            button("voice.language",Lang.t("ui.voiceInput.language")+" · "+lang,Icon.LANGUAGE,x,y,rw,30,false,()->{int n=VoiceInputConfig.LANGUAGES.indexOf(cfg.language);cfg.language=VoiceInputConfig.LANGUAGES.get((n+1)%VoiceInputConfig.LANGUAGES.size());cfg.save();});y+=43;
            button("voice.mic",Lang.t("ui.voiceInput.microphone")+" · "+c.ellipsize(REG,cfg.microphone.isBlank()?Lang.t("ui.voiceInput.defaultMic"):cfg.microphone,10.5f,rw-190),null,x,y,rw-110,30,false,this::nextMicrophone);
            button("voice.mic.refresh",Lang.t(loading?"ui.voiceInput.loading":"ui.voiceInput.refresh"),Icon.REFRESH,x+rw-98,y,98,30,false,this::refreshMicrophones);y+=43;
            y=sliderRow("ui.voiceInput.maxSeconds",seconds,"s",x,y,rw);
            y=sliderRow("ui.voiceInput.silenceMillis",silence,"ms",x,y,rw);
            y=sliderRow("ui.voiceInput.threshold",threshold,"%",x,y,rw);
            y=sliderRow("ui.voiceInput.timeout",timeout,"s",x,y,rw);
            if(cfg.engine.equals("whisper")){
                c.textC(REG,Lang.t("ui.voiceInput.python"),x,y,11,Theme.TEXT_2);y+=14;textField(python,"python",Icon.FOLDER,x,y,rw,30);y+=47;
                c.textC(REG,Lang.t("ui.voiceInput.model"),x,y,11,Theme.TEXT_2);y+=14;textField(model,Lang.t("ui.voiceInput.modelHint"),Icon.FOLDER,x,y,rw-114,30);
                button("voice.model.browse",Lang.t("ui.voiceInput.browse"),Icon.FOLDER,x+rw-102,y,102,30,false,this::chooseModel);y+=43;
                button("voice.device",Lang.t("ui.voiceInput.device")+" · "+(cfg.device.equals("cuda")?"GPU · CUDA":"CPU"),null,x,y,rw,30,false,()->{cfg.device=cfg.device.equals("cpu")?"cuda":"cpu";cfg.save();});y+=43;
            }
            String hint=Lang.t(cfg.engine.equals("windows")?"ui.voiceInput.windowsHint":"ui.voiceInput.whisperHint");
            for(String line:c.wrap(REG,hint,9,rw)){c.textC(REG,line,x,y,9,Theme.TEXT_3);y+=14;}y+=12;
            for(String line:c.wrap(REG,Lang.t("ui.voiceInput.privacy"),9,rw)){c.textC(REG,line,x,y,9,Theme.TEXT_3);y+=14;}
            if(!error.isBlank()){y+=14;c.textC(REG,Lang.t(error),x,y,10,0xffffbbaa);y+=16;}
            var state=AiVoiceControl.state();y+=17;
            c.textC(MED,Lang.t(state.statusKey()),x,y,11,Theme.TEXT_2);y+=20;
            for(String line:c.wrap(REG,state.transcript(),10.5f,rw)){c.textC(REG,line,x,y,10.5f,Theme.TEXT_2);y+=15;}
            scroll.max=Math.max(0,y-start+20-(bottom-top));scroll.update(dt);popHitClip();c.unclip();panelScrollBar("voice.scroll",scroll,w-15,top,bottom-top);
            button("voice.back",Lang.t("ui.back"),Icon.BACK,24,h-44,100,28,false,this::onClose);
            button("voice.start",Lang.t(AiVoiceControl.busy()?"ui.voiceInput.cancel":"ui.voiceInput.start"),Icon.MUSIC,136,h-44,190,28,true,()->{cfg.save();if(AiVoiceControl.busy())AiVoiceControl.cancel();else{AiVoiceControl.start();if(AiVoiceControl.busy())mc.setScreen(null);}});
            button("voice.send",Lang.t("ui.voiceInput.sendTranscript"),Icon.CHEVRON_RIGHT,338,h-44,190,28,false,()->{AiVoiceControl.submitTranscript();if(com.cheatprogram.ai.AiController.isRunning())mc.setScreen(null);});
            button("voice.save",Lang.t("ui.voiceInput.save"),Icon.SAVE,w-130,h-44,106,28,false,cfg::save);
        }finally{R2D.end();endFrame();}
    }
    private float toggleRow(String id,String label,boolean value,Runnable action,float x,float y,float w){c.textC(REG,Lang.t(label),x,y+15,11,Theme.TEXT_2);toggle("voice."+id,x+w-30,y+8,value,1);hit(x,y,w,31).left(action);return y+42;}
    private float sliderRow(String label,NumberSetting number,String unit,float x,float y,float w){c.textC(REG,Lang.t(label),x,y+12,11,Theme.TEXT_2);c.textRight(REG,number.format()+" "+unit,x+w,y+12,10.5f,Theme.TEXT_2);slider(number,x+6,y+37,w-12);return y+58;}
    private void nextMicrophone(){ArrayList<String> all=new ArrayList<>();all.add("");all.addAll(microphones);int i=all.indexOf(cfg.microphone);cfg.microphone=all.get((i+1)%all.size());cfg.save();}
    private void chooseModel(){FILES.execute(()->{String chosen=TinyFileDialogs.tinyfd_selectFolderDialog(Lang.t("ui.voiceInput.model"),model.text);if(chosen!=null)mc.execute(()->{if(!closed){model.setText(chosen);cfg.model=chosen;cfg.save();}});});}
    @Override public boolean keyPressed(int key,int scan,int mods){
        if(binding){if(key==GLFW.GLFW_KEY_ESCAPE){binding=false;return true;}if(key==GLFW.GLFW_KEY_DELETE||key==GLFW.GLFW_KEY_BACKSPACE)cfg.key=-1;else if(key==ClientSettings.INSTANCE.guiKey){error="ui.voiceInput.reservedKey";return true;}else cfg.key=key;binding=false;cfg.save();return true;}
        if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}
        if(focused!=null&&focused.keyPressed(key,mods))return true;return super.keyPressed(key,scan,mods);
    }
    @Override public boolean charTyped(char chr,int mods){return focused!=null&&focused.charTyped(chr)||super.charTyped(chr,mods);}
    @Override public boolean mouseClicked(double x,double y,int button){if(binding&&button>1){cfg.key=KeyNames.MOUSE_OFFSET+button;binding=false;cfg.save();return true;}focused=null;return dispatchClick(x,y,button);}
	@Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return dispatchScroll(x,y,vertical);}
    @Override public void onClose(){cfg.save();mc.setScreen(parent);}
    @Override public void removed(){closed=true;cfg.save();super.removed();}
}
