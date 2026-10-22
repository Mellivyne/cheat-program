package com.cheatprogram.module.visual;

import com.cheatprogram.media.TypewriterText;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.media.player.MusicPlayer;
import com.cheatprogram.media.player.MusicModels;
import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.SungLyrics;
import com.cheatprogram.render.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Timed captions are actual oriented geometry, never a screen-space HUD caption. */
public final class FloatingSubtitles extends Module {
    public final BoolSetting statusText=add(new BoolSetting("statusText",false));
    public final NumberSetting timeOffset=add(new NumberSetting("timeOffset",0,-10,10,.05,"s"));
    public final ModeSetting anchor=add(new ModeSetting("anchor","lyrics_phrase","lyrics_phrase","lyrics_follow","lyrics_view","lyrics_fixed","lyrics_screen"));
    public final BoolSetting translation=add(new BoolSetting("translation",false));
    public final NumberSetting translationSide=add(new NumberSetting("translationSide",0,-16,16,.05,"m")).visibleWhen(translation::on);
    public final NumberSetting translationHeight=add(new NumberSetting("translationHeight",-.65,-8,8,.05,"m")).visibleWhen(translation::on);
    public final NumberSetting translationDepth=add(new NumberSetting("translationDepth",.01,-4,4,.01,"m")).visibleWhen(translation::on);
    public final NumberSetting translationScale=add(new NumberSetting("translationScale",75,25,150,1,"%")).visibleWhen(translation::on);
    public final NumberSetting distance=add(new NumberSetting("distance",3.5,.5,48,.1,"m"));
    public final NumberSetting height=add(new NumberSetting("height",.15,-8,12,.05,"m"));
    public final NumberSetting side=add(new NumberSetting("side",0,-16,16,.05,"m"));
    public final NumberSetting yaw=add(new NumberSetting("yaw",0,-180,180,1,"\u00b0"));
    public final NumberSetting pitch=add(new NumberSetting("pitch",0,-90,90,1,"\u00b0"));
    public final NumberSetting roll=add(new NumberSetting("roll",0,-180,180,1,"\u00b0"));
    public final NumberSetting spread=add(new NumberSetting("spread",20,0,120,1,"\u00b0")).visibleWhen(()->false);
    public final BoolSetting facePlayer=add(new BoolSetting("facePlayer",false));
    public final NumberSetting smoothing=add(new NumberSetting("smoothing",.22,0,2,.02,"s"));
    public final NumberSetting letterHeight=add(new NumberSetting("letterHeight",.42,.06,2,.01,"m"));
    public final NumberSetting maxWidth=add(new NumberSetting("maxWidth",10,1,32,.1,"m"));
    public final NumberSetting lineGap=add(new NumberSetting("lineGap",.16,0,2,.02,"m"));
    public final BoolSetting splitLines=add(new BoolSetting("splitLines",true));
    public final NumberSetting maxChars=add(new NumberSetting("maxChars",32,8,100,1)).visibleWhen(splitLines::on);
    public final ModeSetting font=add(new ModeSetting("font","lyrics_global_font","lyrics_global_font","lyrics_minecraft_font"));
    public final ModeSetting palette=add(new ModeSetting("palette","palette_white","palette_white","palette_rainbow","palette_trans"));
    public final NumberSetting colorPeriod=add(new NumberSetting("colorPeriod",8,1,60,.5,"s")).visibleWhen(()->!palette.is("palette_white"));
    public final NumberSetting opacity=add(new NumberSetting("opacity",100,5,100,1,"%"));
    public final NumberSetting secondaryOpacity=add(new NumberSetting("secondaryOpacity",35,0,100,1,"%"));
    public final BoolSetting edgeGlow=add(new BoolSetting("edgeGlow",true));
    public final NumberSetting glow=add(new NumberSetting("glow",18,0,100,1,"%")).visibleWhen(edgeGlow::on);
    public final NumberSetting glowRadius=add(new NumberSetting("glowRadius",2.2,0,10,.1,"px")).visibleWhen(edgeGlow::on);
    public final BoolSetting throughWalls=add(new BoolSetting("throughWalls",true));
    public final NumberSetting visibleRange=add(new NumberSetting("visibleRange",96,4,192,1,"m"));
    public final ModeSetting animation=add(new ModeSetting("animation","lyrics_float","lyrics_float","lyrics_fade","lyrics_slide","lyrics_spring","lyrics_word","lyrics_typewriter","lyrics_shake","lyrics_wave","lyrics_bounce","lyrics_spin","lyrics_none"));
    public final NumberSetting shakeAmount=add(new NumberSetting("shakeAmount",.04,0,.3,.01,"m")).visibleWhen(()->SubtitleMotion.glyphsMove(animation.get()));
    public final NumberSetting shakeSpeed=add(new NumberSetting("shakeSpeed",8,.5,20,.5,"Hz")).visibleWhen(()->SubtitleMotion.glyphsMove(animation.get()));
    public final NumberSetting typeSpeed=add(new NumberSetting("typeSpeed",12,.5,80,.5)).visibleWhen(()->animation.is("lyrics_typewriter"));
    public final BoolSetting typeCursor=add(new BoolSetting("typeCursor",true)).visibleWhen(()->animation.is("lyrics_typewriter"));
    public final NumberSetting transition=add(new NumberSetting("transition",.45,.05,3,.05,"s")).visibleWhen(()->!animation.is("lyrics_none"));
    public final NumberSetting floatAmount=add(new NumberSetting("floatAmount",.04,0,.6,.01,"m")).visibleWhen(()->animation.is("lyrics_float"));
    public final NumberSetting floatPeriod=add(new NumberSetting("floatPeriod",2.8,.3,12,.1,"s")).visibleWhen(()->animation.is("lyrics_float")&&floatAmount.val()>0);
    public final BoolSetting hidePaused=add(new BoolSetting("hidePaused",false));
    public final BoolSetting showTitle=add(new BoolSetting("showTitle",false));
    // Retain old profile fields without letting them reveal previous or upcoming sentences.
    public final NumberSetting lineCount=add(new NumberSetting("lineCount",1,1,4,1)).visibleWhen(()->false);
    // Legacy preview fields remain readable, but unsung playback text is never drawn.
    public final BoolSetting futurePreview=add(new BoolSetting("futurePreview",false)).visibleWhen(()->false);
    public final NumberSetting previewLines=add(new NumberSetting("previewLines",1,0,3,1)).visibleWhen(()->false);
    public final NumberSetting previewOpacity=add(new NumberSetting("previewOpacity",25,5,75,1,"%")).visibleWhen(()->false);
    private final NumberSetting layoutDefaultsVersion=add(new NumberSetting("layoutDefaultsVersion",1,1,1,1)).visibleWhen(()->false);

    private boolean localLoaded;
    private String provider="";
    private JsonObject localData=new JsonObject();
    private int screen=-1;
    private Object world;
    private Vec3 followBase;
    private SubtitleMotion.Anchor placement;
    private float baseYaw,displayYaw;
    private float displayPitch;
    private List<WorldSubtitleRenderer.Line> translationLines=List.of();
    private Vec3 displayPosition;
    private String phrase="",unitKey="",placementKey="",sourceIdentity="";
    private String lineTimingNoticeTrack="";
    private long changed,lastFrame,previewUntil;
    private List<WorldSubtitleRenderer.Line> leaving=List.of();
    private final SungLyrics sungLyrics=new SungLyrics();
    private Vec3 leavingPosition;
    private float leavingYaw;

    public FloatingSubtitles(){super("floatingsubtitles",Category.VISUAL);}
    public MusicModels.Snapshot snapshot(){return MusicPlayer.INSTANCE.snapshot();}
    public void reload(){MusicPlayer.INSTANCE.refreshLiked();sourceIdentity="";recenter();}
    public void preview(){if(!isEnabled())setEnabled(true);recenter();previewUntil=System.nanoTime()+15_000_000_000L;}
    public void recenter(){placement=null;followBase=displayPosition=null;phrase=unitKey="";leaving=List.of();lastLines=List.of();sungLyrics.clear();lastFrame=0;}
    public void checkLifecycle(){if(!isEnabled()||!inGame()||!Config.persistenceAllowed()||world!=null&&world!=mc.level){world=null;recenter();previewUntil=0;}}
    public static void shutdown(){MusicPlayer.INSTANCE.shutdown();}
    @Override public void onDisable(){MusicPlayer.INSTANCE.setModuleEnabled(false);checkLifecycle();}
    @Override public void onEnable(){MusicPlayer.INSTANCE.setModuleEnabled(true);lineTimingNoticeTrack="";recenter();}
    @Override public String suffix(){var current=snapshot().playback();return current==null||current.track()==null?Lang.t("ui.music.title"):current.track().title();}
    @Override public void onTick(){
        if(!inGame()||!Config.persistenceAllowed())return;
        MusicPlayer.INSTANCE.translation(translation.on());
        if(world!=mc.level){world=mc.level;recenter();}loadLocal();
        // Offset sliders move the existing anchor. Only a placement-mode change captures a new pose.
        String nextPlacement=anchor.get()+":"+screen;if(!nextPlacement.equals(placementKey)){placementKey=nextPlacement;recenter();}
        var data=snapshot();var playback=data.playback();String next=playback==null||playback.track()==null?"":playback.track().key();
        if(!next.equals(sourceIdentity)){sourceIdentity=next;recenter();}
    }
    public String statusKey(){
        if(!isEnabled())return "ui.playerLyrics.disabled";
        var data=snapshot();var playback=data.playback();
        if(playback==null||playback.track()==null)return data.status();
        if(data.lyrics()==null||data.lyrics().isEmpty())return "ui.musicPlayer.noLyrics";
        if(!data.lyrics().synced())return "ui.musicPlayer.noTimedLyrics";
        return playback.status();
    }
    public String primaryStatus(){return Lang.t(statusKey());}
    public String translationStatus(){return translation.on()?MusicPlayer.INSTANCE.translationStatus():"ui.musicPlayer.translatedLyricsDisabled";}
    @Override public void onRender3D(Render3D r){
        if(!inGame()||RearMirrorRenderer.isRendering())return;
        MusicPlayer.INSTANCE.translation(translation.on());translationLines=List.of();
        long now=System.nanoTime();boolean preview=now<previewUntil;
        var data=snapshot();var playback=data.playback();LrcLyrics original=data.lyrics();
        if(!preview&&(playback==null||playback.track()==null||original==null||!original.synced())){
            if(statusText.on()){
                String key=statusKey();phraseAge("status:"+key,0,now);
                List<WorldSubtitleRenderer.Line> statusLines=new ArrayList<>();addLines(statusLines,Lang.t(key),.72f,.66f,0,-1);renderContent(r,now,statusLines,true);
            }
            return;
        }
        List<WorldSubtitleRenderer.Line> lines=new ArrayList<>();
        if(preview){
            double age=phraseAge("preview",0,now);addStyledLines(lines,"Cheat Program · "+Lang.t("ui.lyrics.preview"),1,1,0,-1,age,age);
        }else{
            double seconds=Math.max(0,playback.position()/1000.0+timeOffset.val());long previousObservation=sungLyrics.version();
            var observed=sungLyrics.sample(original,seconds,playback.durationMs()/1000.0,playback.playing(),now,playback.revision());
            if(previousObservation!=sungLyrics.version()){unitKey="";leaving=List.of();lastLines=List.of();}
            if(hidePaused.on()&&!playback.playing()&&!"ui.musicPlayer.buffering".equals(playback.status())&&!"ui.musicPlayer.loading".equals(playback.status()))return;
            int shown=observed.index();
            if(shown<0){unitKey="";leaving=List.of();lastLines=List.of();return;}
            var shownLine=original.lines().get(shown);
            boolean lineOnly=shownLine.words().isEmpty();
            String visible=lineOnly?shownLine.text():observed.visible();
            if(lineOnly)notifyLineTiming(playback.track().key());
            // Keep the sung prefix and complete sentence layout stable as new words start.
            // Motion restarts only for a new sentence; it cannot hide previously sung words.
            double age=phraseAge(playback.track().key()+":"+shown,shown,now,false);
            addVisibleLines(lines,visible,shownLine.text(),1,1,age);
            if(translation.on()){
                var translated=SungLyrics.translationLine(MusicPlayer.INSTANCE.translatedLyrics(),shownLine,visible,seconds);
                if(translated!=null){
                    boolean translatedLineOnly=translated.words().isEmpty();
                    if(translatedLineOnly)notifyLineTiming(playback.track().key());
                    List<WorldSubtitleRenderer.Line> translatedRows=new ArrayList<>();addVisibleLines(translatedRows,translatedLineOnly?translated.text():translated.visiblePrefix(seconds),translated.text(),1,(float)translationScale.fraction(),age);translationLines=List.copyOf(translatedRows);
                }
            }
            if(showTitle.on())lines.add(new WorldSubtitleRenderer.Line(playback.track().title()+" · "+playback.track().artist(),(float)secondaryOpacity.fraction()*.7f,.65f,0,-1));
        }
        renderContent(r,now,lines,false);
    }
    /** One informational popup per recording; no inferred word clock or repeated per-frame notices. */
    private void notifyLineTiming(String track){
        if(track.equals(lineTimingNoticeTrack))return;
        lineTimingNoticeTrack=track;
        Notifications.push(Icon.INFO,Lang.t("ui.lyrics.lineModeTitle"),Lang.t("ui.lyrics.lineModeDetail"));
    }
    private double phraseAge(String key,int seed,long now){
        return phraseAge(key,seed,now,true);
    }
    private double phraseAge(String key,int seed,long now,boolean retainPrevious){
        if(!retainPrevious){leaving=List.of();lastLines=List.of();}
        if(!key.equals(phrase)){
            if(retainPrevious&&displayPosition!=null&&!phrase.isEmpty()){leaving=lastLines;leavingPosition=displayPosition;leavingYaw=displayYaw;}
            phrase=key;changed=now;if(placement==null)anchorAtPlayer(seed);
        }
        if(placement==null)anchorAtPlayer(seed);return Math.max(0,(now-changed)/1e9);
    }
    private double unitAge(String key,long now){
        if(!key.equals(unitKey)){unitKey=key;changed=now;}
        return Math.max(0,(now-changed)/1e9);
    }
    private boolean renderContent(Render3D r,long now,List<WorldSubtitleRenderer.Line> raw,boolean status){
        var point=placement.target(distance.val(),side.val(),height.val());
        Vec3 target=new Vec3(point.x(),point.y(),point.z());float planeYaw=Float.NaN;
        boolean view=anchor.is("lyrics_view");displayPitch=(float)pitch.val();
        if(view){target=ViewSpaceAnchor.position(r.camera,new Vec3(r.cameraObject.getLeftVector()).scale(-1),new Vec3(r.cameraObject.getUpVector()),new Vec3(r.cameraObject.getLookVector()),side.val(),height.val(),distance.val());planeYaw=r.cameraObject.getYRot()+180;displayPitch=-r.cameraObject.getXRot()+(float)pitch.val();}
        if(anchor.is("lyrics_follow"))target=target.add(mc.player.getPosition(r.partialTick).subtract(followBase));
        if(anchor.is("lyrics_screen")){
            AppCast cast=ModuleManager.get(AppCast.class);AppCastPlane plane=cast!=null&&cast.isEnabled()?cast.planeForScreen(screen,r.partialTick):null;
            // Text placement belongs to the visual plane, including a view-only desktop; audio has its own source checks.
            if(plane!=null){target=plane.center().add(plane.up().scale(plane.height()/2+height.val()+letterHeight.val())).add(plane.right().scale(side.val()));planeYaw=plane.yaw();}
            else if(!status&&now>=previewUntil)return false;
        }
        float targetYaw=(Float.isFinite(planeYaw)?planeYaw:baseYaw)+(float)yaw.val();
        if(facePlayer.on()&&!view){Vec3 d=r.camera.subtract(target);targetYaw=(float)Math.toDegrees(Math.atan2(-d.x,d.z))+(float)yaw.val();}
        double dt=lastFrame==0?.016:Math.min(.1,Math.max(0,(now-lastFrame)/1e9));lastFrame=now;
        double amount=view?1:SubtitleMotion.smoothing(dt,smoothing.val());
        displayPosition=displayPosition==null?target:displayPosition.lerp(target,amount);
        displayYaw+=net.minecraft.util.Mth.wrapDegrees(targetYaw-displayYaw)*(float)amount;
        if(displayPosition.distanceToSqr(r.camera)>visibleRange.val()*visibleRange.val())return false;
        double age=Math.max(0,(now-changed)/1e9);float t=(float)Math.min(1,age/transition.val()),ease=t*t*(3-2*t);
        var motion=SubtitleMotion.frame(status?"lyrics_fade":animation.get(),age,transition.val());
        var glyphMotion=new WorldSubtitleRenderer.Motion(animation.get(),age,(float)(shakeAmount.val()/Math.max(.06,letterHeight.val())),(float)shakeSpeed.val());
        List<WorldSubtitleRenderer.Line> lines=raw.stream().map(l->new WorldSubtitleRenderer.Line(l.text(),l.alpha()*(float)opacity.fraction()*motion.alpha(),l.scale()*motion.scale(),l.rise()+motion.y(),l.progress(),l.layoutText(),motion.x(),motion.rotation(),status?WorldSubtitleRenderer.Motion.NONE:glyphMotion,l.futureOpacity())).toList();
        float bob=animation.is("lyrics_float")?(float)Math.sin(now/1e9/floatPeriod.val()*Math.PI*2)*(float)floatAmount.val():0;
        PaletteText.Style colors=new PaletteText.Style(palette.get(),PaletteText.flowingPhase(now/1e9,colorPeriod.val(),1,false,0));
        if(t<1&&!leaving.isEmpty()&&!animation.is("lyrics_none")&&leavingPosition!=null){
            List<WorldSubtitleRenderer.Line> old=leaving.stream().map(l->new WorldSubtitleRenderer.Line(l.text(),l.alpha()*(1-ease),l.scale(),l.rise()+ease*.2f,l.progress(),l.layoutText(),l.shift(),l.rotation(),l.motion(),l.futureOpacity())).toList();
            draw(r,view?displayPosition:leavingPosition,view?displayYaw:leavingYaw,old,colors);
        }
        Vec3 actual=displayPosition.add(0,bob,0);draw(r,actual,displayYaw,lines,colors);
        if(!translationLines.isEmpty()){
            var at=SubtitleMotion.relative(new SubtitleMotion.Point(actual.x,actual.y,actual.z),displayYaw,displayPitch,roll.val(),translationSide.val(),translationHeight.val(),translationDepth.val());
            List<WorldSubtitleRenderer.Line> translated=translationLines.stream().map(l->new WorldSubtitleRenderer.Line(l.text(),l.alpha()*(float)opacity.fraction()*motion.alpha(),l.scale()*motion.scale(),l.rise()+motion.y(),l.progress(),l.layoutText(),motion.x(),motion.rotation(),WorldSubtitleRenderer.Motion.NONE,l.futureOpacity())).toList();
            draw(r,new Vec3(at.x(),at.y(),at.z()),displayYaw,translated,colors);
        }
        lastLines=List.copyOf(lines);return true;
    }
    /** Playback timing controls text selection; visual animations never invent partial words. */
    private void addVisibleLines(List<WorldSubtitleRenderer.Line> out,String visible,String full,float alpha,float scale,double age){
        List<String> chunks=splitLines.on()?wrap(full,maxChars.intVal()):List.of(full);
        for(var row:SungLyrics.present(visible,chunks,false,0)){if(out.size()>=12)break;out.add(new WorldSubtitleRenderer.Line(row.text(),alpha,scale,0,row.progress(),row.layout(),0,0,WorldSubtitleRenderer.Motion.NONE,0));}
    }
    private void addStyledLines(List<WorldSubtitleRenderer.Line> out,String text,float alpha,float scale,float rise,float progress,double phraseAge,double cueAge){
        if(animation.is("lyrics_typewriter")){
            List<String> rows=splitLines.on()?wrap(text,maxChars.intVal()):List.of(text);double elapsed=cueAge;
            boolean cursorShown=typeCursor.on()&&System.nanoTime()/450_000_000L%2==0;
            for(String row:rows){
                if(out.size()>=12)break;var frame=TypewriterText.frame(row,elapsed,typeSpeed.val());
                String visible=frame.text()+(!frame.complete()&&elapsed>=0&&cursorShown?"_":"");
                out.add(new WorldSubtitleRenderer.Line(visible,alpha,scale,rise,progress,row+(typeCursor.on()?"_":"")));
                elapsed-=frame.totalUnits()/typeSpeed.val();
            }
        }else if(animation.is("lyrics_word")){
            List<String> rows=splitLines.on()?wrap(text,maxChars.intVal()):List.of(text);
            for(String row:rows){if(out.size()>=12)break;out.add(new WorldSubtitleRenderer.Line(SubtitleMotion.wordPrefix(row,phraseAge,transition.val()),alpha,scale,rise,progress,row));}
        }else addLines(out,text,alpha,scale,rise,progress);
    }
    private List<WorldSubtitleRenderer.Line> lastLines=List.of();
    private void draw(Render3D r,Vec3 at,float angle,List<WorldSubtitleRenderer.Line> lines,PaletteText.Style colors){WorldSubtitleRenderer.draw(r,at,angle,displayPitch,(float)roll.val(),lines,(float)letterHeight.val(),(float)maxWidth.val(),(float)lineGap.val(),throughWalls.on(),font.is("lyrics_minecraft_font"),edgeGlow.on()?(float)glow.fraction():0,(float)glowRadius.val(),colors);}
    private void addLines(List<WorldSubtitleRenderer.Line> out,String text,float alpha,float scale,float rise,float progress){
        if(out.size()>=12)return;
        List<String> chunks=splitLines.on()?wrap(text,maxChars.intVal()):List.of(text);
        int total=Math.max(1,text.length()),start=0;
        for(String chunk:chunks){if(out.size()>=12)break;float p=progress<0?-1:Math.max(0,Math.min(1,(progress*total-start)/Math.max(1,chunk.length())));out.add(new WorldSubtitleRenderer.Line(chunk,alpha,scale,rise,p));start+=chunk.length();}
    }
    private static List<String> wrap(String text,int max){List<String> result=new ArrayList<>();int start=0;while(start<text.length()&&result.size()<6){int end=start+TypewriterText.frame(text.substring(start),max,1).text().length();if(end<text.length()){int space=text.lastIndexOf(' ',end);if(space>start+max/3)end=space+1;}result.add(text.substring(start,end));start=end;}return result.isEmpty()?List.of(""):result;}
    private void anchorAtPlayer(int line){
        followBase=mc.player.position();float angle=mc.player.getYRot();
        Vec3 eye=mc.player.getEyePosition();placement=new SubtitleMotion.Anchor(eye.x,eye.y,eye.z,angle);baseYaw=angle+180;
        // Capture once; lyric line changes and parameter edits retain this placement.
        if(displayPosition==null){var p=placement.target(distance.val(),side.val(),height.val());displayPosition=new Vec3(p.x(),p.y(),p.z());displayYaw=baseYaw+(float)yaw.val();lastFrame=0;}
    }
    private Path localFile(){return Config.directory().resolve("floating-subtitles/local.json");}
    private void loadLocal(){if(localLoaded)return;localLoaded=true;if(!Config.persistenceAllowed())return;try{Path file=localFile();if(!Files.isRegularFile(file)||Files.size(file)>65536)return;localData=JsonParser.parseString(Files.readString(file,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();provider=get(localData,"provider");if(localData.has("screen"))screen=Math.max(-1,Math.min(127,localData.get("screen").getAsInt()));}catch(Exception ignored){}}
    private static String get(JsonObject j,String key){return j.has(key)&&j.get(key).isJsonPrimitive()?j.get(key).getAsString():"";}
    private void saveLocal(){synchronized(Config.persistenceLock()){if(!Config.persistenceAllowed())return;try{JsonObject j=localData.deepCopy();j.addProperty("provider",provider);Path file=localFile();Files.createDirectories(file.getParent());Path temp=file.resolveSibling("local.json.tmp");Files.writeString(temp,j.toString(),java.nio.charset.StandardCharsets.UTF_8);Files.move(temp,file,java.nio.file.StandardCopyOption.REPLACE_EXISTING);localData=j;}catch(Exception ignored){}}}
}
