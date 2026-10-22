package com.cheatprogram.module.visual;

import com.cheatprogram.audio.AcousticScene;
import com.cheatprogram.audio.AppAudioCapture;
import com.cheatprogram.audio.SpatialAudioBackend;
import com.cheatprogram.audio.SpatialAudioEnvironment;
import com.cheatprogram.audio.NvidiaAudioEffects;
import com.cheatprogram.capture.AppCaptureEngine;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.render.RearMirrorRenderer;
import com.cheatprogram.render.Render3D;
import com.mojang.blaze3d.systems.RenderSystem;
import com.cheatprogram.render.AcousticRayRenderer;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded acoustic probes are cached separately from continuous audio playback. */
public final class SpatialAudio extends Module {
    private final ModeSetting source=add(new ModeSetting("source","spatial_cast","spatial_cast","spatial_background") {
        @Override public void load(com.google.gson.JsonElement element){
            if(element!=null&&element.isJsonPrimitive()){
                String old=element.getAsString();
                if(old.equals("spatial_background_game")){value="spatial_background";return;}
                if(old.equals("spatial_game")||old.equals("spatial_both")){value="spatial_cast";return;}
            }
            super.load(element);
        }
    });
    private final NumberSetting screen=add(new NumberSetting("screen",-1,-1,127,1)).visibleWhen(()->false);
    private final BoolSetting muteOriginal=add(new BoolSetting("muteOriginal",true)).visibleWhen(this::usesCast);
    private final ModeSetting update=add(new ModeSetting("update","spatial_cached","spatial_cached","spatial_realtime","spatial_manual"));
    private final NumberSetting interval=add(upgraded("interval",.4,3,.05,30,.05,"s")).visibleWhen(()->update.is("spatial_cached"));
    private final NumberSetting rays=add(upgraded("rays",2048,2048,64,32768,64,""));
    private final NumberSetting bounces=add(upgraded("bounces",8,8,1,16,1,""));
    private final NumberSetting range=add(upgraded("range",48,32,4,160,1,"m"));
    private final NumberSetting budget=add(upgraded("budget",256,256,1,2048,1,""));
    private final NumberSetting frameBudget=add(new NumberSetting("frameBudget",2,.25,4,.25,"ms"));
    private final NumberSetting absorption=add(new NumberSetting("absorption",100,5,200,5,"%"));
    private final NumberSetting scattering=add(new NumberSetting("scattering",100,0,200,5,"%"));
    private final NumberSetting airLoss=add(new NumberSetting("airLoss",1,0,4,.1));
    private final NumberSetting wet=add(upgraded("wet",75,40,0,100,1,"%"));
    private final NumberSetting echo=add(upgraded("echo",60,35,0,100,1,"%"));
    private final NumberSetting volume=add(upgraded("volume",130,80,0,200,1,"%")).visibleWhen(this::usesCast);
    private final ModeSetting captureDenoise=add(new ModeSetting("captureDenoise","spatial_denoise_cpu","spatial_denoise_off","spatial_denoise_cpu","spatial_denoise_nvidia")).visibleWhen(this::usesCast);
    private final NumberSetting denoiseStrength=add(new NumberSetting("denoiseStrength",35,0,100,1,"%")).visibleWhen(()->usesCast()&&!captureDenoise.is("spatial_denoise_off"));
    private final NumberSetting audibleRange=add(new NumberSetting("audibleRange",64,4,192,1,"m"));
    private final BoolSetting backgroundFollow=add(new BoolSetting("backgroundFollow",true)).visibleWhen(this::backgroundAudio);
    private final NumberSetting backgroundDistance=add(new NumberSetting("backgroundDistance",3,0,8,.1,"m")).visibleWhen(this::backgroundAudio);
    private final NumberSetting backgroundHeight=add(new NumberSetting("backgroundHeight",.3,-8,8,.1,"m")).visibleWhen(this::backgroundAudio);
    private final BoolSetting visualization=add(new BoolSetting("visualization",false));
    private final BoolSetting animatedWaves=add(new BoolSetting("animatedWaves",false)).visibleWhen(visualization::on);
    private final BoolSetting throughWalls=add(new BoolSetting("throughWalls",false)).visibleWhen(visualization::on);
    private final NumberSetting lineOpacity=add(new NumberSetting("lineOpacity",24,2,100,1,"%")).visibleWhen(visualization::on);
    private final NumberSetting lineLimit=add(upgraded("lineLimit",8192,8192,64,131072,64,"")).visibleWhen(visualization::on);
    private final NumberSetting signalDefaultsVersion=add(new NumberSetting("signalDefaultsVersion",3,3,3,1)).visibleWhen(()->false);
    private final AppAudioCapture capture=new AppAudioCapture();
    private final AcousticRayRenderer castRays=new AcousticRayRenderer(),gameRays=new AcousticRayRenderer();
    private Object world;
    private AcousticScene.Job gameJob,castJob;
    private AcousticScene.Response gameResponse,castResponse;
    private Vec3 gamePosition=Vec3.ZERO,castPosition=Vec3.ZERO;
    private long nextBake,lastConfigure,lastDirect;
    private boolean forceBake=true,stopped=true,routeRequested;
    private String probeSignature="",captureSignature="",streamIdentity="";
    private boolean visibilityRestored;
    private long nextBackgroundCheck;
    private boolean backgroundAlive;
    private String captureFailure="";
    private long captureRetryAfter;
    private int captureFailures,capturePid;
    private AppCast.AudioSource castSource;
    private AppCaptureEngine.Window backgroundWindow;
    private long backgroundStarted;
    private boolean backgroundLoaded;
    private Vec3 backgroundBase,backgroundDirection;
    private Object backgroundWorld;

    public SpatialAudio(){super("spatialaudio",Category.VISUAL);}
    public boolean usesCast(){return true;}
    public boolean usesGame(){return false;}
    public boolean backgroundAudio(){return source.is("spatial_background");}
    public int screenIndex(){return screen.intVal();}
    public void screenIndex(int value){screen.set((double)Math.max(-1,Math.min(127,value)));resetStream();streamIdentity="";rebake();}
    public void rebake(){forceBake=true;gameJob=castJob=null;nextBake=0;}
    private void resetStream(){capture.stop();captureSignature="";SpatialAudioBackend.resetCastStream();}
    public void reconnect(){resetStream();streamIdentity="";captureFailure="";captureFailures=0;captureRetryAfter=0;SpatialAudioEnvironment.releaseRoute();SpatialAudioEnvironment.resumeAutomaticRouting();}
    public void ensureIsolatedCapture(){muteOriginal.set(true);if(!isEnabled())toggle();else SpatialAudioEnvironment.resumeAutomaticRouting();}
    public void applyRealisticDefaults(){for(var option:settings())if(option instanceof NumberSetting number&&List.of("interval","rays","bounces","range","budget","frameBudget","absorption","scattering","airLoss","wet","echo","volume","audibleRange","lineOpacity","lineLimit","denoiseStrength").contains(option.id))number.set(number.defaultValue);update.set("spatial_cached");captureDenoise.set("spatial_denoise_cpu");rebake();}
    public boolean nvidiaRequested(){return captureDenoise.is("spatial_denoise_nvidia");}
    public String denoiseStatus(){return Lang.t(capture.denoiseStatusKey())+(nvidiaRequested()?" · "+Lang.t(capture.denoiseDetailKey()):"");}
    public void openNvidiaDirectory(){try{Path directory=NvidiaAudioEffects.directory();Files.createDirectories(directory);net.minecraft.Util.getPlatform().openPath(directory);}catch(Exception ignored){}}
    public boolean visualizing(){return visualization.on();}
    public void visualize(boolean value){visualization.set(value);if(!value){castRays.close();gameRays.close();}}
    public AppCast.AudioSource selectedSource(){
        if(backgroundAudio()){loadBackground();AppCaptureEngine.Window item=backgroundWindow;if(item==null)return null;
            long now=System.nanoTime();if(now>=nextBackgroundCheck){nextBackgroundCheck=now+250_000_000L;backgroundAlive=ProcessHandle.of(item.pid()).filter(ProcessHandle::isAlive).flatMap(p->p.info().startInstant()).map(t->t.toEpochMilli()==backgroundStarted).orElse(false);}
            return new AppCast.AudioSource(-2,item.pid(),item.label(),backgroundPosition(),backgroundAlive);}
        AppCast cast=ModuleManager.get(AppCast.class);return cast==null?null:cast.audioSource(screen.intVal());
    }
    public void selectBackground(AppCaptureEngine.Window item){backgroundLoaded=true;backgroundWindow=item;backgroundStarted=ProcessHandle.of(item.pid()).flatMap(p->p.info().startInstant()).map(java.time.Instant::toEpochMilli).orElse(-1L);backgroundBase=backgroundDirection=null;nextBackgroundCheck=0;reconnect();rebake();
        synchronized(Config.persistenceLock()){if(!Config.persistenceAllowed())return;try{Path file=SpatialAudioEnvironment.directory().resolve("background-app.json");Files.createDirectories(file.getParent());JsonObject json=new JsonObject();json.addProperty("handle",item.handle());json.addProperty("pid",item.pid());json.addProperty("started",backgroundStarted);json.addProperty("process",item.process());json.addProperty("title",item.title());Files.writeString(file,json.toString());}catch(Exception ignored){}}
    }
    private void loadBackground(){if(backgroundLoaded)return;backgroundLoaded=true;if(!Config.persistenceAllowed())return;try{Path file=SpatialAudioEnvironment.directory().resolve("background-app.json");if(Files.isRegularFile(file)&&Files.size(file)<65536){JsonObject j=JsonParser.parseString(Files.readString(file)).getAsJsonObject();backgroundStarted=j.has("started")?j.get("started").getAsLong():-1;backgroundWindow=new AppCaptureEngine.Window(j.get("handle").getAsLong(),j.get("pid").getAsInt(),j.get("process").getAsString(),j.get("title").getAsString(),false);}}catch(Exception ignored){}}
    private Vec3 backgroundPosition(){if(!inGame())return Vec3.ZERO;if(backgroundWorld!=mc.level||backgroundBase==null){backgroundWorld=mc.level;backgroundBase=mc.player.position();Vec3 look=mc.player.getLookAngle();backgroundDirection=new Vec3(look.x,0,look.z).normalize();if(backgroundDirection.lengthSqr()<.1)backgroundDirection=new Vec3(0,0,1);}
        Vec3 base=backgroundFollow.on()?mc.player.position():backgroundBase;return base.add(backgroundDirection.scale(backgroundDistance.val())).add(0,mc.player.getEyeHeight()+backgroundHeight.val(),0);}
    private static NumberSetting upgraded(String id,double defaultNumber,double legacy,double min,double max,double step,String unit){return new NumberSetting(id,defaultNumber,min,max,step,unit);}
    public List<AppCast.AudioSource> availableSources(){AppCast cast=ModuleManager.get(AppCast.class);return cast==null?List.of():cast.audioSources();}
    public String sourceLabel(){AppCast.AudioSource selected=selectedSource();return selected==null?Lang.t("ui.spatial.noCast"):selected.label();}
    public String captureStatus(){if(!captureFailure.isEmpty())return Lang.t(captureFailure)+" · "+Lang.t(captureRetryAfter==Long.MAX_VALUE?"ui.spatialAudio.capturePaused":"ui.spatialAudio.captureRetry");AppCast.AudioSource selected=castSource;return Lang.t(isEnabled()&&usesCast()&&muteOriginal.on()&&selected!=null&&selected.active()&&!SpatialAudioEnvironment.routedFor(selected.pid())?SpatialAudioEnvironment.statusKey():capture.statusKey());}
    public String backendStatus(){return Lang.t(SpatialAudioBackend.status());}
    public float captureLevel(){return capture.level();}
    public AcousticScene.Response response(){return usesCast()&&castResponse!=null?castResponse:gameResponse;}
    public float progress(){int count=0;double sum=0;if(gameJob!=null){sum+=gameJob.progress();count++;}if(castJob!=null){sum+=castJob.progress();count++;}return count==0?1:(float)(sum/count);}
    public String processingStatusKey(){
        if(!isEnabled())return "ui.spatial.runtime.off";
        if(!SpatialAudioBackend.playbackReady()&&usesCast())return "ui.spatial.runtime.backend";
        if(usesCast()){
            AppCast.AudioSource selected=castSource;if(selected==null||!selected.active())return "ui.spatial.runtime.noSource";
            if(muteOriginal.on()&&!SpatialAudioEnvironment.routedFor(selected.pid()))return "ui.spatial.runtime.unisolated";
            if(!captureFailure.isEmpty())return "ui.spatial.runtime.failed";
            if(!SpatialAudioBackend.playingPcm())return "ui.spatial.runtime.waitingAudio";
            return muteOriginal.on()?"ui.spatial.runtime.isolated":"ui.spatial.runtime.mixed";
        }
        return SpatialAudioBackend.gameProcessingAvailable()?"ui.spatial.runtime.game":"ui.spatial.runtime.backend";
    }
    public String processingStatus(){return Lang.t(processingStatusKey());}
    public String isolationStatus(){return Lang.t("ui.spatial.runtime.sessions",SpatialAudioEnvironment.isolatedSessions(),SpatialAudioEnvironment.activeSessions());}
    @Override public String suffix(){return processingStatus();}
    public static void lifecycle(){SpatialAudio value=ModuleManager.get(SpatialAudio.class);if(value!=null)value.checkLifecycle();}
    public static void shutdown(){SpatialAudio value=ModuleManager.get(SpatialAudio.class);if(value!=null)value.stopResources();else SpatialAudioBackend.stop();}
    public void checkLifecycle(){
        if(!isEnabled()||!inGame()||!Config.persistenceAllowed()) {if(!stopped)stopResources();return;}
        if(!visibilityRestored){setDrawn(true);visibilityRestored=true;}
        if(world!=mc.level){stopResources();world=mc.level;forceBake=true;}
    }
    private void stopResources(){
        capture.stop();castRays.close();gameRays.close();SpatialAudioBackend.stop();SpatialAudioEnvironment.releaseRoute();routeRequested=false;gameJob=castJob=null;gameResponse=castResponse=null;
        captureSignature=probeSignature=streamIdentity="";castSource=null;nextBake=lastConfigure=lastDirect=0;forceBake=true;stopped=true;world=null;
        captureFailure="";captureRetryAfter=0;captureFailures=capturePid=0;
    }
    @Override public void onEnable(){setDrawn(true);visibilityRestored=true;forceBake=true;SpatialAudioEnvironment.resumeAutomaticRouting();checkLifecycle();}
    @Override public void onDisable(){visibilityRestored=false;stopResources();}
    @Override public void onTick(){
        checkLifecycle();if(!isEnabled()||!inGame()||!Config.persistenceAllowed())return;
        stopped=false;world=mc.level;
        capture.denoise(captureDenoise.is("spatial_denoise_nvidia")?"nvidia":captureDenoise.is("spatial_denoise_cpu")?"cpu":"off",denoiseStrength.fraction());
        if(!visualization.on()){castRays.close();gameRays.close();}
        Vec3 listener=mc.player.getEyePosition();
        castSource=usesCast()?selectedSource():null;
        boolean castActive=castSource!=null&&castSource.active();
        castPosition=castActive?castSource.position():listener;
        Vec3 lastGame=SpatialAudioBackend.lastGameSource();
        gamePosition=lastGame!=null&&lastGame.distanceToSqr(listener)<audibleRange.val()*audibleRange.val()?lastGame:listener;
        String nextProbe=source.get()+":"+screen.intVal()+":"+rays.intVal()+":"+bounces.intVal()+":"+range.val()+":"+airLoss.val()+":"+absorption.val()+":"+scattering.val();
        if(!nextProbe.equals(probeSignature)){probeSignature=nextProbe;rebake();}
        long now=System.nanoTime();
        int nextPid=castActive?castSource.pid():0;
        String identity=source.get()+":"+screen.intVal()+":"+nextPid+":"+muteOriginal.on()+":"+(castSource==null?-1:castSource.index());
        if(!identity.equals(streamIdentity)){
            resetStream();if(nextPid!=capturePid||!castActive||!muteOriginal.on()){SpatialAudioEnvironment.releaseRoute();routeRequested=false;}
            streamIdentity=identity;capturePid=nextPid;captureFailures=0;captureRetryAfter=0;captureFailure="";castJob=null;castResponse=null;forceBake=true;nextBake=0;
        }
        if(!captureSignature.isEmpty()&&!capture.running()){
            captureFailure=capture.statusKey();captureFailures++;captureRetryAfter=captureFailures>=3?Long.MAX_VALUE:now+captureFailures*10_000_000_000L;
            resetStream();SpatialAudioEnvironment.releaseRoute();routeRequested=false;
        }
        boolean captureAllowed=now>=captureRetryAfter;
        var settings=new AcousticScene.ProbeSettings(rays.intVal(),bounces.intVal(),range.val(),airLoss.val(),absorption.fraction(),scattering.fraction());
        if(!update.is("spatial_manual")&&((castResponse!=null&&(castResponse.source().distanceToSqr(castPosition)>.64||castResponse.listener().distanceToSqr(listener)>.64))||(gameResponse!=null&&(gameResponse.source().distanceToSqr(gamePosition)>.64||gameResponse.listener().distanceToSqr(listener)>.64)))){forceBake=true;nextBake=0;}
        boolean automatic=!update.is("spatial_manual")&&now>=nextBake;
        if((forceBake||automatic)&&gameJob==null&&castJob==null){
            if(usesGame())gameJob=AcousticScene.begin(mc.level,gamePosition,listener,settings);
            if(usesCast()&&castActive)castJob=AcousticScene.begin(mc.level,castPosition,listener,settings);
            forceBake=false;nextBake=now+(long)((update.is("spatial_realtime")?.1:interval.val())*1_000_000_000L);
        }
        int share=gameJob!=null&&castJob!=null?Math.max(1,budget.intVal()/2):budget.intVal();
        long pathBudget=(long)(frameBudget.val()*1_000_000)/(gameJob!=null&&castJob!=null?2:1);
        if(gameJob!=null){boolean complete=gameJob.step(share,pathBudget);gameResponse=gameJob.response();if(complete)gameJob=null;}
        if(castJob!=null){boolean complete=castJob.step(share,pathBudget);castResponse=castJob.response();if(complete)castJob=null;}
        if(now-lastDirect>=100_000_000L){
            if(usesGame())gameResponse=AcousticScene.refreshDirect(mc.level,gamePosition,listener,settings,gameResponse);
            if(usesCast()&&castActive)castResponse=AcousticScene.refreshDirect(mc.level,castPosition,listener,settings,castResponse);
            lastDirect=now;
        }
        if(now-lastConfigure>40_000_000L){
            SpatialAudioBackend.configure(usesGame(),usesCast()&&castActive&&captureAllowed,gameResponse,castResponse,wet.fraction(),echo.fraction(),volume.fraction(),audibleRange.val(),castPosition);
            lastConfigure=now;
        }
        if(castActive&&captureAllowed&&muteOriginal.on()&&SpatialAudioBackend.playbackReady()){SpatialAudioEnvironment.requestRoute(castSource.pid());routeRequested=true;}
        else if(routeRequested){resetStream();SpatialAudioEnvironment.releaseRoute();routeRequested=false;}
        boolean isolated=!muteOriginal.on()||castActive&&SpatialAudioEnvironment.routedFor(castSource.pid());
        String nextCapture=castActive&&captureAllowed&&isolated&&SpatialAudioBackend.playbackReady()?castSource.pid()+":"+muteOriginal.on():"";
        if(!nextCapture.equals(captureSignature)){
            resetStream();captureSignature=nextCapture;
            // Original audio is isolated by the verified route lease; session mute would silence capture.
            if(!nextCapture.isEmpty()){captureFailure="";capture.start(castSource.pid(),false,pcm->SpatialAudioBackend.pushPcm(pcm.samples()));}
        }
    }
    public String metrics(){
        AcousticScene.Response r=response();if(r==null)return Lang.t("ui.spatial.waitingProbe");
        return Lang.t("ui.spatial.metrics",String.format(Locale.ROOT,"%.1f",r.volume()),String.format(Locale.ROOT,"%.0f",r.enclosure()*100),String.format(Locale.ROOT,"%.2f",r.reverbSeconds()),String.format(Locale.ROOT,"%.0f",r.echoDelay()*1000));
    }
    public String audibility(){AcousticScene.Response r=response();return r==null?"":Lang.t("ui.spatial.audibility",Math.round(r.obstruction()*100),String.format(Locale.ROOT,"%.1f",r.estimatedAudibleDistance(audibleRange.val())));}
    @Override public void onRender3D(Render3D renderer){
        if(!visualization.on()||!inGame()||RearMirrorRenderer.isRendering())return;
        float[] oldColor=RenderSystem.getShaderColor().clone();
        renderer.begin();try{
            if(!throughWalls.on())RenderSystem.enableDepthTest();
            RenderSystem.lineWidth(1);
            int used=castRays.prepare(usesCast()?castResponse:null,lineLimit.intVal(),lineOpacity.fraction());
            gameRays.prepare(usesGame()?gameResponse:null,lineLimit.intVal()-used,lineOpacity.fraction());
            double phase=System.nanoTime()/1_000_000_000.0;
            castRays.draw(renderer,animatedWaves.on(),phase);gameRays.draw(renderer,animatedWaves.on(),phase);
        }finally{RenderSystem.setShaderColor(oldColor[0],oldColor[1],oldColor[2],oldColor[3]);renderer.end();}
    }
}
