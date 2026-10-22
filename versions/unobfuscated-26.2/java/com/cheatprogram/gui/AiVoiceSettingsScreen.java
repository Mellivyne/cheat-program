package com.cheatprogram.gui;

import com.cheatprogram.ai.AiPlayers;
import com.cheatprogram.ai.voice.SpeechVoices;
import com.cheatprogram.ai.voice.VoiceProfile;
import com.cheatprogram.ai.voice.VoiceSettings;
import com.cheatprogram.ai.voice.VoiceEnvironment;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.SpeechEmotion;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Per-character voice controls plus shared local runtime setup. No synthesis happens on open. */
public final class AiVoiceSettingsScreen extends GuiBase {
    private static final float W=720,H=452,TOP=108;
    private float panelHeight=H,bodyBottom=H-106;
    private static final ExecutorService FILES=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"AI voice files");t.setDaemon(true);return t;});
    private final Screen parent;
    private final UUID actorId;
    private VoiceProfile voice;
    private final Scroller scroll=new Scroller(),catalogScroll=new Scroller();
    private final TextField voiceID=new TextField(256),language=new TextField(32),preview=new TextField();
    private final TextField python=new TextField(2048),model=new TextField(2048),voices=new TextField(2048),vocab=new TextField(2048);
    private final TextField ownModel=new TextField(2048),ownVoices=new TextField(2048),ownVocab=new TextField(2048);
    private final Map<String,TextField> emotionFields=new LinkedHashMap<>();
    private final NumberSetting speed=new NumberSetting("voiceSpeed",1,.5,2,.05,"x"),pitch=new NumberSetting("voicePitch",0,-12,12,.5),volume=new NumberSetting("voiceVolume",100,0,200,5,"%"),strength=new NumberSetting("voiceStrength",100,0,200,5,"%"),threads=new NumberSetting("voiceThreads",4,1,32,1);
    private int tab;
    private String defaultDevice,previewEmotion="neutral",status="";
    private boolean catalogOpen,catalogLoading,closed;
    private boolean installing;
    private boolean checkingEnvironment;
    private VoiceEnvironment.Result environmentResult;
    private List<String> catalog=List.of();
    private long request;
    public AiVoiceSettingsScreen(Screen parent,UUID id){
        super(Lang.t("ui.voice.title"));this.parent=parent;actorId=id;
        AiPlayers.Profile p=current();voice=p==null?new VoiceProfile():VoiceProfile.load(p.voice().save());
        for(String emotion:SpeechEmotion.VALUES)emotionFields.put(emotion,new TextField(256));
        loadVoice();
        VoiceSettings s=VoiceSettings.INSTANCE;python.setText(s.pythonExecutable);model.setText(s.modelPath);voices.setText(s.voicesPath);vocab.setText(s.vocabConfigPath);threads.set((double)s.threads);defaultDevice=s.device;
        for(TextField field:List.of(python,model,voices,vocab,ownModel,ownVoices,ownVocab))field.onChange=ignored->environmentResult=null;
        preview.setText(Lang.t("ui.voice.previewDefault",p==null?"AI":p.name()));
    }
    private AiPlayers.Profile current(){return AiPlayers.profiles().stream().filter(p->p.id().equals(actorId)).findFirst().orElse(null);}
    private void loadVoice(){
        voiceID.setText(voice.voiceID);language.setText(voice.language);ownModel.setText(voice.modelPath);ownVoices.setText(voice.voicePackPath);ownVocab.setText(voice.vocabConfigPath);
        speed.set(voice.speed);pitch.set(voice.pitch);volume.set(voice.volume*100);strength.set(voice.strength*100);
        for(var entry:emotionFields.entrySet())entry.getValue().setText(voice.emotionVoices.getOrDefault(entry.getKey(),""));
    }
    private void edits(){
        voice.voiceID=voiceID.text.strip();voice.language=language.text.strip();voice.speed=speed.val();voice.pitch=pitch.val();voice.volume=volume.fraction();voice.strength=strength.fraction();
        voice.modelPath=ownModel.text.strip();voice.voicePackPath=ownVoices.text.strip();voice.vocabConfigPath=ownVocab.text.strip();voice.emotionVoices.clear();
        emotionFields.forEach((emotion,field)->{if(!field.text.isBlank())voice.emotionVoices.put(emotion,field.text.strip());});
    }
    private void commit(){
        edits();if(!SpeechVoices.installing()){VoiceSettings s=VoiceSettings.INSTANCE;s.pythonExecutable=python.text.strip();s.modelPath=model.text.strip();s.voicesPath=voices.text.strip();s.vocabConfigPath=vocab.text.strip();s.device=defaultDevice;s.threads=threads.intVal();s.saveToDisk();}
        AiPlayers.Profile p=current();if(p!=null&&!p.voice().save().equals(voice.save()))AiPlayers.updateVoice(actorId,voice);
    }
    @Override public void render(GuiGraphicsExtractor graphics,int mx,int my,float partialTick){
        beginFrame();if(!preparePanelBackground(graphics,partialTick)){endFrame();return;}R2D.begin(graphics);
        try{
            panelHeight=layoutPanel(W,H);bodyBottom=panelHeight-106;
            drawPanelSurface(graphics,W,panelHeight,26);
            AiPlayers.Profile actor=current();
            c.textC(BOLD,c.ellipsize(BOLD,Lang.t("ui.voice.title")+" · "+(actor==null?"AI":actor.name()),16,W-85),24,28,16,Theme.TEXT);
            iconButton("voice.close",Icon.CLOSE,W-28,28,16,Theme.TEXT_2,this::onClose);
            String[] tabs={"ui.voice.tab.voice","ui.voice.tab.engine","ui.voice.tab.emotion"};
            for(int i=0;i<tabs.length;i++){final int index=i;button("voice.tab."+i,Lang.t(tabs[i]),null,24+i*154,55,144,29,tab==i,()->{tab=index;focused=null;scroll.target=0;scroll.value.snap(0);});}
            c.textC(REG,Lang.t("ui.voice.autoSave"),24,97,8.5f,Theme.TEXT_3);
            hit(16,TOP,W-32,bodyBottom-TOP).scroll(scroll::scroll);c.clip(16,TOP,W-32,bodyBottom-TOP);pushHitClip(16,TOP,W-32,bodyBottom-TOP);
            float y=TOP+6-scroll.get(),start=y;
            y=tab==0?voicePage(y):tab==1?enginePage(y):emotionPage(y);
            popHitClip();c.unclip();scroll.max=Math.max(0,y-start-(bodyBottom-TOP));scroll.update(dt);
            panelScrollBar("voice.form",scroll,W-14,TOP,bodyBottom-TOP);
            String message=status.isEmpty()?SpeechVoices.status(actorId):status;
            if(message.isEmpty())message=SpeechVoices.diagnostics();
            List<String> details=c.wrap(REG,message,9.5f,W-48);for(int i=0;i<Math.min(3,details.size());i++)c.textC(REG,details.get(i),24,panelHeight-89+i*14,9.5f,Theme.TEXT_3);
            float footer=panelHeight-42;
            button("voice.back",Lang.t("ui.back"),Icon.BACK,24,footer,88,28,false,this::onClose);
            button("voice.stop",Lang.t("ui.voice.stop"),Icon.POWER,120,footer,154,28,false,()->SpeechVoices.stop(actorId));
            button("voice.export",Lang.t("ui.voice.export"),Icon.SAVE,306,footer,116,28,false,this::exportVoice);
            button("voice.import",Lang.t("ui.voice.import"),Icon.FOLDER,430,footer,116,28,false,this::importVoice);
            button("voice.save",Lang.t("ui.aip.save"),Icon.SAVE,554,footer,142,28,true,()->{commit();status=Lang.t("ui.voice.saved");});
            if(catalogOpen)catalogOverlay();
        }finally{R2D.end();endFrame();}
    }
    private float voicePage(float y){
        y=toggleLine("ui.voice.enabled",voice.enabled,()->voice.enabled=!voice.enabled,y);
        y=devices("ui.voice.device",voice.device,List.of("inherit","cpu","cuda"),value->{voice.device=value;environmentResult=null;},y);
        y=textLine("ui.voice.voiceID",voiceID,"zf_001",y,136,()->readVoices());
        y=textLine("ui.voice.language",language,"zh / en-us / en-gb / ja",y,0,null);
        y=number("ui.voice.speed",speed,y);y=number("ui.voice.pitch",pitch,y);y=number("ui.voice.volume",volume,y);
        y=toggleLine("ui.voice.emotionEnabled",voice.emotionEnabled,()->voice.emotionEnabled=!voice.emotionEnabled,y);
        AiPlayers.Profile actor=current();if(actor!=null)y=notes(Lang.t("ui.voice.currentEmotion",Lang.t("voice.emotion."+actor.emotion())),y);
        y=textLine("ui.voice.previewText",preview,Lang.t("ui.voice.previewHint"),y,136,()->{commit();VoiceProfile test=VoiceProfile.load(voice.save());test.enabled=true;SpeechVoices.speak(actorId,test,preview.text,previewEmotion,.7);status=Lang.t("ui.voice.queued");});
        y=notes(Lang.t("ui.voice.localOnly"),y);
        return y+16;
    }
    private float enginePage(float y){
        y=notes(Lang.t("ui.voice.engineHint"),y);
        button("voice.environment.detect",Lang.t(checkingEnvironment?"ui.voice.env.checking":"ui.voice.env.detect"),Icon.REFRESH,28,y,302,29,false,this::checkEnvironment);
        button("voice.environment.folder",Lang.t("ui.voice.env.openPacks"),Icon.FOLDER,340,y,W-368,29,false,()->{try{SpeechVoices.setup();net.minecraft.util.Util.getPlatform().openPath(VoiceEnvironment.packs());}catch(Exception e){status=Lang.t("ui.voice.fileError");}});y+=42;
        if(environmentResult!=null){
            y=notes(environmentResult.summary(),y);
            for(String detail:environmentResult.details())y=notes(detail,y)-4;
        }
        button("voice.environment.oneClick",Lang.t(SpeechVoices.installing()?"ui.voice.env.installing":"ui.voice.env.oneClick"),null,28,y,420,30,true,()->install(false));
        if(SpeechVoices.installing())button("voice.environment.cancel",Lang.t("ui.voice.env.cancel"),null,458,y,W-486,30,false,()->{SpeechVoices.cancelInstallation();status=Lang.t("ui.voice.env.cancelling");});y+=43;
        y=notes(Lang.t("ui.voice.env.folder",VoiceSettings.directory().toAbsolutePath().normalize()),y);
        y=notes(Lang.t("ui.voice.env.packFolder",VoiceEnvironment.packs()),y);
        y=notes(Lang.t("ui.voice.env.hint"),y);
        button("voice.setup",Lang.t("ui.voice.prepare"),Icon.FOLDER,28,y,292,28,false,()->{try{SpeechVoices.setup();status=Lang.t("ui.voice.setupReady",VoiceSettings.directory());net.minecraft.util.Util.getPlatform().openPath(VoiceSettings.directory());}catch(Exception e){status=Lang.t("ui.voice.fileError");}});
        button("voice.install.models",Lang.t("ui.voice.env.withPack"),null,330,y,W-358,28,false,()->install(true));y+=44;
        y=notes(Lang.t("ui.voice.installHint"),y);
        y=pathLine("ui.voice.python",python,"*.exe",y);
        y=devices("ui.voice.defaultDevice",defaultDevice,List.of("cpu","cuda"),value->{defaultDevice=value;environmentResult=null;},y);
        y=number("ui.voice.threads",threads,y);
        y=pathLine("ui.voice.model",model,"*.onnx",y);y=pathLine("ui.voice.voices",voices,"*.bin;*.npz",y);y=pathLine("ui.voice.vocab",vocab,"*.json",y);
        y=notes(Lang.t("ui.voice.overrideHint"),y);
        y=pathLine("ui.voice.ownModel",ownModel,"*.onnx",y);y=pathLine("ui.voice.ownVoices",ownVoices,"*.bin;*.npz",y);y=pathLine("ui.voice.ownVocab",ownVocab,"*.json",y);
        return y+16;
    }
    private float emotionPage(float y){
        y=toggleLine("ui.voice.emotionEnabled",voice.emotionEnabled,()->voice.emotionEnabled=!voice.emotionEnabled,y);
        y=notes(Lang.t("ui.voice.emotionHint"),y);y=number("ui.voice.strength",strength,y);
        for(String emotion:SpeechEmotion.VALUES)y=textLine("voice.emotion."+emotion,emotionFields.get(emotion),Lang.t("ui.voice.inheritVoice"),y,0,null);
        y=devices("ui.voice.previewEmotion",previewEmotion,SpeechEmotion.VALUES,value->previewEmotion=value,y);
        return y+16;
    }
    private float toggleLine(String key,boolean on,Runnable action,float y){c.textC(REG,Lang.t(key),28,y+15,11,Theme.TEXT_2);toggle(key,W-76,y+7,on,1);hit(24,y,W-48,32).left(action);return y+42;}
    private float textLine(String key,TextField field,String hint,float y,float actionWidth,Runnable action){
        c.textC(REG,Lang.t(key),28,y+9,10.5f,Theme.TEXT_2);textField(field,hint,null,28,y+23,W-56-(actionWidth>0?actionWidth+8:0),29);
        if(action!=null)button(key+".action",Lang.t(key.equals("ui.voice.voiceID")?"ui.voice.list":key.equals("ui.voice.previewText")?"ui.voice.preview":"ui.aip.browse"),null,W-28-actionWidth,y+23,actionWidth,29,false,action);
        return y+67;
    }
    private float pathLine(String key,TextField field,String filters,float y){return textLine(key,field,Lang.t("ui.voice.pathHint"),y,88,()->choose(false,filters,path->{field.setText(path.toString());environmentResult=null;}));}
    private float number(String key,NumberSetting setting,float y){c.textC(REG,Lang.t(key),28,y+12,10.5f,Theme.TEXT_2);c.textRight(MED,setting.format(),W-28,y+12,10,Theme.TEXT);slider(setting,34,y+32,W-68);return y+56;}
    private float devices(String key,String selected,List<String> options,Consumer<String> choose,float y){
        c.textC(REG,Lang.t(key),28,y+10,10.5f,Theme.TEXT_2);float width=(W-56-options.size()*5)/options.size();
        for(int i=0;i<options.size();i++){String value=options.get(i);String label=SpeechEmotion.VALUES.contains(value)?Lang.t("voice.emotion."+value):Lang.t("voice.device."+value);button(key+"."+value,label,null,28+i*(width+5),y+25,width,28,selected.equals(value),()->choose.accept(value));}
        return y+69;
    }
    private float notes(String text,float y){for(String line:c.wrap(REG,text,9,W-56)){c.textC(REG,line,28,y+10,9,Theme.TEXT_3);y+=15;}return y+12;}
    private void readVoices(){
        commit();focused=null;catalogLoading=true;catalogOpen=true;catalog=List.of();long token=++request;
        SpeechVoices.listVoices(VoiceProfile.load(voice.save())).whenComplete((list,error)->mc.execute(()->{
            if(closed||token!=request)return;catalogLoading=false;
            if(error==null){catalog=List.copyOf(list);status=Lang.t("ui.voice.voiceCount",catalog.size());}
            else{
                catalogOpen=false;String detail=SpeechVoices.lastError();status=detail.isBlank()?Lang.t("ui.voice.catalogError"):detail;
                tab=1;focused=null;scroll.target=0;scroll.value.snap(0);
            }
        }));
    }
    private void install(boolean download){
        if(installing||SpeechVoices.installing())return;commit();installing=true;environmentResult=null;status=Lang.t("ui.voice.env.installing");
        SpeechVoices.installDefault(defaultDevice,download).whenComplete((message,error)->mc.execute(()->{
            installing=false;if(closed)return;
            status=error==null?message:Lang.t("ui.voice.env.installError",SpeechVoices.lastError());
            if(error==null){VoiceSettings s=VoiceSettings.INSTANCE;python.setText(s.pythonExecutable);model.setText(s.modelPath);voices.setText(s.voicesPath);vocab.setText(s.vocabConfigPath);defaultDevice=s.device;}
            if(error==null)checkEnvironment();
        }));
    }
    private void checkEnvironment(){
        if(checkingEnvironment||SpeechVoices.installing())return;
        commit();String signature=environmentSignature();checkingEnvironment=true;status=Lang.t("ui.voice.env.checking");
        VoiceEnvironment.check(VoiceProfile.load(voice.save())).whenComplete((result,error)->mc.execute(()->{
            checkingEnvironment=false;if(closed)return;
            if(!signature.equals(environmentSignature())){environmentResult=null;status=Lang.t("ui.voice.env.changed");return;}
            if(error!=null){status=Lang.t("ui.voice.env.checkFailed");return;}
            environmentResult=result;status=result.summary();
            VoiceSettings s=VoiceSettings.INSTANCE;model.setText(s.modelPath);voices.setText(s.voicesPath);
        }));
    }
    private String environmentSignature(){return String.join("\u0000",python.text,model.text,voices.text,vocab.text,ownModel.text,ownVoices.text,ownVocab.text,defaultDevice,voice.device);}
    private void catalogOverlay(){
        float w=490,h=Math.min(340,panelHeight-44),x=(W-w)/2,y=(panelHeight-h)/2;
        c.rrect(0,0,W,panelHeight,0,Anim.black(.18f));hit(0,0,W,panelHeight).click(b->catalogOpen=false).scroll(a->{});
        c.lens(x,y,w,h,18,Theme.lift(.12f),Theme.sheetGlass(c.scale));hit(x,y,w,h).click(b->{}).scroll(catalogScroll::scroll);
        c.textC(BOLD,Lang.t("ui.voice.chooseVoice"),x+18,y+22,13,Theme.TEXT);iconButton("voices.close",Icon.CLOSE,x+w-24,y+22,13,Theme.TEXT_2,()->catalogOpen=false);
        float top=y+47,bottom=y+h-12;catalogScroll.max=Math.max(0,catalog.size()*30-(bottom-top));catalogScroll.update(dt);
        c.clip(x+10,top,w-20,bottom-top);pushHitClip(x+10,top,w-20,bottom-top);
        if(catalogLoading||catalog.isEmpty())c.textC(REG,Lang.t(catalogLoading?"ui.voice.loading":"ui.voice.empty"),x+18,top+20,10,Theme.TEXT_3);
        float row=top-catalogScroll.get();for(String id:catalog){if(row+28>=top&&row<bottom)button("voice.id."+id,id,null,x+14,row,w-28,27,voiceID.text.equals(id),()->{voiceID.setText(id);catalogOpen=false;});row+=30;}
        popHitClip();c.unclip();
    }
    private void importVoice(){choose(false,"*.json",file->FILES.execute(()->{
        try{if(Files.size(file)>1024*1024)throw new java.io.IOException("Voice preset is too large");JsonObject json=JsonParser.parseString(Files.readString(file,StandardCharsets.UTF_8)).getAsJsonObject();VoiceProfile imported=VoiceProfile.load(json.has("voice")?json.getAsJsonObject("voice"):json);
            imported.modelPath=relative(file,imported.modelPath);imported.voicePackPath=relative(file,imported.voicePackPath);imported.vocabConfigPath=relative(file,imported.vocabConfigPath);
            mc.execute(()->{if(!closed){voice=imported;loadVoice();status=Lang.t("ui.voice.imported");}});
        }catch(Exception ignored){mc.execute(()->{if(!closed)status=Lang.t("ui.voice.fileError");});}
    }));}
    private static String relative(Path file,String value){if(value.isBlank())return value;Path path=Path.of(value);return path.isAbsolute()?value:file.toAbsolutePath().getParent().resolve(path).normalize().toString();}
    private void exportVoice(){edits();JsonObject snapshot=voice.save();choose(true,"*.json",file->FILES.execute(()->{
        try{Path target=file.toString().toLowerCase(Locale.ROOT).endsWith(".json")?file:Path.of(file+".json");synchronized(com.cheatprogram.core.Config.persistenceLock()){if(!com.cheatprogram.core.Config.persistenceAllowed())return;Files.writeString(target,new GsonBuilder().setPrettyPrinting().create().toJson(snapshot),StandardCharsets.UTF_8);}mc.execute(()->{if(!closed)status=Lang.t("ui.voice.exported");});}
        catch(Exception ignored){mc.execute(()->{if(!closed)status=Lang.t("ui.voice.fileError");});}
    }));}
    private void choose(boolean save,String filter,Consumer<Path> callback){
        FILES.execute(()->{try(MemoryStack stack=MemoryStack.stackPush()){
            var filters=stack.mallocPointer(filter.split(";").length);for(String part:filter.split(";"))filters.put(stack.UTF8(part));filters.flip();
            String selected=save?TinyFileDialogs.tinyfd_saveFileDialog(Lang.t("ui.voice.export"),"voice-profile.json",filters,"JSON")
                    :TinyFileDialogs.tinyfd_openFileDialog(Lang.t("ui.aip.browse"),"",filters,filter,false);
            if(selected!=null)mc.execute(()->{if(!closed)callback.accept(Path.of(selected));});
        }catch(Exception ignored){mc.execute(()->{if(!closed)status=Lang.t("ui.voice.fileError");});}});
    }
    @Override public boolean mouseClicked(double x,double y,int button){if(!dispatchClick(x,y,button))focused=null;return true;}
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){return dispatchScroll(x,y,vertical);}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==GLFW.GLFW_KEY_ESCAPE){if(catalogOpen){catalogOpen=false;return true;}onClose();return true;}if(catalogOpen)return true;if(focused!=null){focused.keyPressed(key,mods);return true;}return false;}
    @Override public boolean charTyped(char chr,int mods){return catalogOpen||focused!=null&&focused.charTyped(chr);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){commit();closed=true;request++;mc.gui.setScreen(parent);}
    @Override public void removed(){if(!closed){commit();closed=true;request++;}super.removed();}
}
