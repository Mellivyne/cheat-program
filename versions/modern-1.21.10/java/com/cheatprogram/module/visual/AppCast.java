package com.cheatprogram.module.visual;

import com.cheatprogram.capture.AppCaptureEngine;
import com.cheatprogram.capture.AppCastWindowCompatibility;
import com.cheatprogram.core.*;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.gui.AppCastInputScreen;
import com.cheatprogram.render.*;
import com.google.gson.*;
import net.minecraft.client.KeyMapping;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** Independent live window planes. Capture, world placement and explicit held input are separate. */
public final class AppCast extends Module {
    private final TextSetting windowTitle=add(new TextSetting("windowTitle","",2048));
    private final TextSetting windowProcess=add(new TextSetting("windowProcess","",256));
    private final TextSetting screens=add(new TextSetting("screens","",1048576)).visibleWhen(()->false);
    private final NumberSetting interactionKey=add(new NumberSetting("interactionKey",GLFW.GLFW_KEY_RIGHT_ALT,-1,GLFW.GLFW_KEY_LAST,1)).visibleWhen(()->false);
    private final BoolSetting interaction=add(new BoolSetting("interaction",false));
    private final ModeSetting inputMode=add(new ModeSetting("inputMode","appcast_background_input","appcast_background_input","appcast_native_foreground")).visibleWhen(interaction::on);
    private final BoolSetting keyboardOnly=add(new BoolSetting("keyboardOnly",false)).visibleWhen(interaction::on);
    private final BoolSetting windowedLiveCompatible=add(new BoolSetting("windowedLiveCompatible",true));
    private final ModeSetting typing=add(new ModeSetting("typing","appcast_move_first","appcast_move_first","appcast_type_first")).visibleWhen(()->interaction.on()&&!keyboardOnly.on());
    private final BoolSetting nativePointer=add(new BoolSetting("nativePointer",true)).visibleWhen(interaction::on);
    private final NumberSetting pointerSpeed=add(new NumberSetting("pointerSpeed",1,.1,5,.05)).visibleWhen(()->interaction.on()&&!nativePointer.on());
    private final ModeSetting anchor=add(new ModeSetting("anchor","appcast_fixed","appcast_fixed","appcast_follow","appcast_view"));
    private final NumberSetting width=add(new NumberSetting("width",3,.25,24,.05,"m"));
    private final NumberSetting x=add(new NumberSetting("x",0,-8,8,.05,"m"));
    private final NumberSetting y=add(new NumberSetting("y",.3,-8,8,.05,"m"));
    private final NumberSetting z=add(new NumberSetting("z",3,-8,8,.05,"m"));
    private final NumberSetting yaw=add(new NumberSetting("yaw",180,-180,180,1,"°"));
    private final NumberSetting pitch=add(new NumberSetting("pitch",0,-180,180,1,"°"));
    private final NumberSetting roll=add(new NumberSetting("roll",0,-180,180,1,"°"));
    private final ModeSetting backend=add(new ModeSetting("backend","appcast_auto","appcast_auto","appcast_wgc","appcast_gdi"));
    private final BoolSetting nativeResolution=add(new BoolSetting("nativeResolution",true));
    private final NumberSetting resolution=add(new NumberSetting("resolution",8192,320,8192,16,"px")).visibleWhen(()->!nativeResolution.on());
    private final NumberSetting pixelLimit=add(new NumberSetting("pixelLimit",32,1,32,1,"MP")).visibleWhen(()->!nativeResolution.on());
    private final BoolSetting nativeFrameRate=add(new BoolSetting("nativeFrameRate",true));
    private final NumberSetting fps=add(new NumberSetting("fps",30,1,120,1)).visibleWhen(()->!nativeFrameRate.on());
    private final BoolSetting sharp=add(new BoolSetting("sharp",false));
    private final NumberSetting uploadBudget=add(new NumberSetting("uploadBudget",32,4,128,4,"MiB")).visibleWhen(()->!nativeFrameRate.on());
    private final NumberSetting opacity=add(new NumberSetting("opacity",100,10,100,5,"%"));
    private final NumberSetting brightness=add(new NumberSetting("brightness",100,10,200,5,"%"));
    private final BoolSetting throughWalls=add(new BoolSetting("throughWalls",false));
    private final BoolSetting border=add(new BoolSetting("border",false));
    private static final Gson GSON=new Gson();
    private final List<Slot> slots=new ArrayList<>();private int selectedIndex;private String stored="";private boolean loaded;
    private Object world;private Matrix4f inverseView,viewProjection;private AppCastPlane renderedPointerPlane;private Vec3 renderCamera;private long renderTime,lastExitPress;
    private boolean focusSampled,wasForeground;
    private static final class Slot {
        final String id=UUID.randomUUID().toString();final AppCaptureEngine capture=new AppCaptureEngine();final Map<String,JsonElement> values=new LinkedHashMap<>();
        AppCaptureEngine.Window selected;String title="",process="",sourceKind="window",sourceId="",request="",pending="",status="";long pendingSince,generation;boolean choosing;
        Vec3 base;float baseYaw;float aspect=16f/9f;
    }
    public record ScreenEntry(int index,String title,String status,boolean selected){ }
    public record AudioSource(int index,int pid,String label,Vec3 position,boolean active){ }
    /** Audio follows an explicitly chosen capture source, never the desktop/system mix. */
    public List<AudioSource> audioSources(){ensureLoaded();saveCurrent();List<AudioSource> result=new ArrayList<>();for(int i=0;i<slots.size();i++){Slot slot=slots.get(i);if(slot.selected!=null&&!slot.selected.desktop()&&slot.selected.pid()>0)result.add(new AudioSource(i,slot.selected.pid(),slot.selected.title(),center(slot,1),isEnabled()&&slot.capture.running()&&slot.capture.receivedFrames()>0&&slot.capture.rootProcessAlive()));}return List.copyOf(result);}
    public AudioSource audioSource(int index){int wanted=index<0?selectedIndex:index;return audioSources().stream().filter(source->source.index()==wanted).findFirst().orElse(null);}
    public AppCast(){super("appcast",Category.VISUAL);}
    private static AppCast instance(){return ModuleManager.get(AppCast.class);}
    public static void checkLifecycleAll(){AppCast module=instance();if(module!=null)module.checkLifecycle();}
    public static void shutdown(){AppCastWindowCompatibility.release();AppCast module=instance();if(module!=null){module.leaveInteraction();for(Slot slot:module.slots){slot.generation++;slot.capture.stop();slot.request="";slot.pending="";}}}
    public static boolean inputActive(){return mc.screen instanceof AppCastInputScreen;}
    public boolean interactionEnabled(){return interaction.on();}
    public void interaction(boolean value){interaction.set(value);if(!value)leaveInteraction();saveCurrent();}
    public int interactionKey(){return interactionKey.intVal();}
    public void interactionKey(int value){releaseInput();interactionKey.set((double)value);Config.markDirty();}
    public boolean keyboardOnly(){return keyboardOnly.on();}
    public void keyboardOnly(boolean value){releaseInput();KeyMapping.releaseAll();keyboardOnly.set(value);Config.markDirty();}
    public boolean moveFirst(){return typing.is("appcast_move_first");}
    public void moveFirst(boolean value){typing.set(value?"appcast_move_first":"appcast_type_first");Config.markDirty();}
    public double pointerSpeed(){return nativePointer.on()?1:pointerSpeed.val();}
    public ModeSetting inputModeSetting(){return inputMode;}
    /** Hold the chosen key; GUI key double press exits and opens the ordinary panel. */
    public static boolean key(int key,int action){AppCast module=instance();if(module==null||!module.isEnabled()||!inGame())return false;
        if(inputActive()&&key==ClientSettings.INSTANCE.guiKey){if(action==GLFW.GLFW_PRESS){long now=System.currentTimeMillis();if(module.lastExitPress>0&&now-module.lastExitPress<=400){module.leaveInteraction();mc.setScreen(new com.cheatprogram.gui.ClickGuiScreen());module.lastExitPress=0;}else module.lastExitPress=now;}return true;}
        if(key!=module.interactionKey()||!module.interactionEnabled())return false;
        if(action==GLFW.GLFW_RELEASE){if(inputActive()&&!module.keyboardOnly())module.leaveInteraction();return true;}
        if(action==GLFW.GLFW_PRESS){if(inputActive()&&module.keyboardOnly())module.leaveInteraction();else if(mc.screen==null)module.enterInteraction();}return true;
    }
    public void checkLifecycle(){
        if(!Config.persistenceAllowed()){focusSampled=false;AppCastWindowCompatibility.release();if(inputActive())leaveInteraction();for(Slot slot:slots)slot.capture.stop();return;}
        ensureLoaded();Object live=inGame()?mc.level:null;
        if(world!=live){world=live;focusSampled=false;leaveInteraction();for(Slot slot:slots){slot.generation++;slot.choosing=false;slot.capture.stop();slot.request="";slot.base=null;slot.status="";}inverseView=null;viewProjection=null;renderCamera=null;}
        if(live==null||!isEnabled()){focusSampled=false;AppCastWindowCompatibility.release();if(inputActive())leaveInteraction();for(Slot slot:slots)if(slot.capture.running()||!slot.request.isEmpty()){slot.capture.stop();slot.request="";}return;}
        boolean foreground=mc.isWindowActive()&&!mc.getWindow().isMinimized();
        boolean resumed=focusSampled&&!wasForeground&&foreground;focusSampled=true;wasForeground=foreground;
        if(inputActive()&&(!interaction.on()||!foreground))leaveInteraction();
        if(resumed){
            boolean running=slots.stream().anyMatch(slot->slot.selected!=null&&slot.capture.running());
            AppCastWindowCompatibility.refreshAfterResume(windowedLiveCompatible.on()&&running);
            // The capture belongs to the source, not to the game's foreground state.
            // Alt-Tab must not destroy a healthy session or its selected child page.
            // Source minimize/restore is handled by the native frame pool; process
            // failure is handled separately by the capture watchdog.
        }
    }
    @Override public void onDisable(){focusSampled=false;AppCastWindowCompatibility.release();leaveInteraction();for(Slot slot:slots){slot.generation++;slot.capture.stop();slot.request="";slot.choosing=false;}}
    @Override public void onEnable(){checkLifecycle();}
    @Override public void onTick(){
        checkLifecycle();if(!inGame()||!Config.persistenceAllowed())return;saveCurrent();
        for(Slot slot:slots){if(slot.base==null)placeAhead(slot);if(slot.selected==null){resolve(slot);continue;}
            boolean nativeSize=bool(slot,"nativeResolution",true);
            int edge=nativeSize?8192:number(slot,"resolution",8192),pixels=nativeSize?33554432:number(slot,"pixelLimit",32)*1048576,rate=bool(slot,"nativeFrameRate",true)?0:number(slot,"fps",30);
            boolean allow=interaction.on()&&!slot.selected.desktop();String backend=mode(slot,"backend","appcast_auto");
            String signature=slot.selected.sourceKey()+":"+edge+":"+pixels+":"+rate+":"+allow+":"+backend+":"+nativeSize;
            slot.capture.sharp(bool(slot,"sharp",false));
            slot.capture.uploadBudget(number(slot,"uploadBudget",32));
            slot.capture.nativeFrameRate(bool(slot,"nativeFrameRate",true));
            if(!signature.equals(slot.request)){long now=System.nanoTime();if(!signature.equals(slot.pending)){slot.pending=signature;slot.pendingSince=now;}
                if(slot.request.isEmpty()||now-slot.pendingSince>=350_000_000L){slot.request=signature;slot.status="";slot.capture.start(slot.selected,edge,rate,bool(slot,"nativeResolution",true),pixels,allow,backend);}}
        }
        AppCastWindowCompatibility.update(windowedLiveCompatible.on()&&slots.stream().anyMatch(slot->slot.capture.running()));
    }
    private void resolve(Slot slot){if(slot.choosing||slot.title.isBlank())return;slot.choosing=true;long ticket=slot.generation;
        AppCaptureEngine.windows().whenComplete((windows,error)->mc.execute(()->{if(slot.generation!=ticket||!isEnabled()||!inGame()||!Config.persistenceAllowed())return;slot.choosing=false;
            if(error!=null){slot.status="ui.appcast.listFailed";slot.choosing=true;return;}slot.selected=windows.stream().filter(w->sameSavedSource(slot,w)).findFirst().orElse(null);
            if(slot.selected==null){slot.status="ui.appcast.sourceMissing";slot.choosing=true;}}));}
    private static boolean sameSavedSource(Slot slot,AppCaptureEngine.Window window){return slot.sourceKind.equals("monitor")?window.desktop()&&!slot.sourceId.isBlank()&&slot.sourceId.equals(window.sourceId()):!window.desktop()&&window.title().equals(slot.title)&&window.process().equalsIgnoreCase(slot.process);}
    private boolean perScreen(Setting<?> setting){return !List.of("screens","interactionKey","interaction","inputMode","keyboardOnly","windowedLiveCompatible","typing","nativePointer","pointerSpeed","windowTitle","windowProcess").contains(setting.id);}
    private void ensureLoaded(){
        if(loaded&&stored.equals(screens.get()))return;leaveInteraction();for(Slot slot:slots)slot.capture.stop();slots.clear();
        try{if(!screens.get().isBlank()){JsonArray entries=JsonParser.parseString(screens.get()).getAsJsonArray();for(JsonElement element:entries){if(slots.size()>=128)break;JsonObject object=element.getAsJsonObject();Slot slot=new Slot();slot.title=object.has("title")?object.get("title").getAsString():"";slot.process=object.has("process")?object.get("process").getAsString():"";slot.sourceKind=object.has("sourceKind")&&object.get("sourceKind").getAsString().equals("monitor")?"monitor":"window";slot.sourceId=object.has("sourceId")?object.get("sourceId").getAsString():"";
            if(object.has("settings"))for(var entry:object.getAsJsonObject("settings").entrySet())slot.values.put(entry.getKey(),entry.getValue().deepCopy());slots.add(slot);}}}catch(RuntimeException ignored){slots.clear();}
        if(slots.isEmpty()){Slot initial=new Slot();initial.title=windowTitle.get();initial.process=windowProcess.get();for(Setting<?> setting:settings())if(perScreen(setting))initial.values.put(setting.id,setting.save());slots.add(initial);}
        selectedIndex=Math.max(0,Math.min(selectedIndex,slots.size()-1));loaded=true;stored=screens.get();loadCurrent();
    }
    private Slot current(){ensureLoaded();return slots.get(Math.max(0,Math.min(selectedIndex,slots.size()-1)));}
    private void loadCurrent(){Slot slot=slots.get(selectedIndex);for(Setting<?> setting:settings())if(perScreen(setting)&&slot.values.containsKey(setting.id))try{setting.load(slot.values.get(setting.id));}catch(RuntimeException ignored){}windowTitle.set(slot.title);windowProcess.set(slot.process);}
    private void saveCurrent(){if(!loaded||slots.isEmpty()||!Config.persistenceAllowed())return;Slot slot=slots.get(selectedIndex);boolean changed=!slot.title.equals(windowTitle.get())||!slot.process.equals(windowProcess.get());slot.title=windowTitle.get();slot.process=windowProcess.get();
        if(slot.base!=null&&!mode(slot,"anchor","appcast_fixed").equals(anchor.get()))placeAhead(slot);
        for(Setting<?> setting:settings())if(perScreen(setting)){JsonElement value=setting.save();if(!value.equals(slot.values.get(setting.id))){slot.values.put(setting.id,value);changed=true;}}
        if(changed)saveScreens();}
    private void saveScreens(){if(!Config.persistenceAllowed())return;JsonArray array=new JsonArray();for(Slot slot:slots){JsonObject object=new JsonObject(),values=new JsonObject();object.addProperty("title",slot.title);object.addProperty("process",slot.process);object.addProperty("sourceKind",slot.sourceKind);object.addProperty("sourceId",slot.sourceId);slot.values.forEach((id,value)->values.add(id,value.deepCopy()));object.add("settings",values);array.add(object);}
        String next=GSON.toJson(array);if(!next.equals(stored)){stored=next;screens.set(next);Config.markDirty();}}
    public List<ScreenEntry> screenEntries(){ensureLoaded();List<ScreenEntry> result=new ArrayList<>();for(int i=0;i<slots.size();i++){Slot slot=slots.get(i);result.add(new ScreenEntry(i,slot.title.isBlank()?Lang.t("ui.appcast.unassigned"):slot.title,statusKey(slot),i==selectedIndex));}return result;}
    public int selectedIndex(){return selectedIndex;}
    public void selectScreen(int index){ensureLoaded();if(index<0||index>=slots.size()||index==selectedIndex)return;leaveInteraction();saveCurrent();current().capture.cancelWindowFollow();selectedIndex=index;loadCurrent();Config.markDirty();}
    public void addScreen(){ensureLoaded();leaveInteraction();saveCurrent();Slot slot=new Slot();for(Setting<?> setting:settings())if(perScreen(setting))slot.values.put(setting.id,setting.save());slot.values.put("x",new JsonPrimitive(0));slot.values.put("y",new JsonPrimitive(.3));slot.values.put("z",new JsonPrimitive(3));slots.add(slot);selectedIndex=slots.size()-1;loadCurrent();placeAhead(slot);saveScreens();}
    public void deleteScreen(){ensureLoaded();leaveInteraction();Slot removed=slots.remove(selectedIndex);removed.generation++;removed.capture.stop();if(slots.isEmpty())slots.add(new Slot());selectedIndex=Math.min(selectedIndex,slots.size()-1);loadCurrent();saveCurrent();saveScreens();}
    public void selectWindow(AppCaptureEngine.Window window){assignWindow(window,false);}
    /** Switching views of the same app keeps the screen's world placement and size. */
    public void switchWindow(AppCaptureEngine.Window window){assignWindow(window,true);}
    private void assignWindow(AppCaptureEngine.Window window,boolean preservePlacement){Objects.requireNonNull(window);Slot slot=current();AppCaptureEngine.Window active=slot.capture.currentWindow();if(active==null)active=slot.selected;if(active!=null&&active.sourceKey().equals(window.sourceKey()))return;leaveInteraction();saveCurrent();slot.generation++;slot.capture.stop();slot.selected=window;slot.choosing=false;slot.request="";slot.pending="";slot.status="";windowTitle.set(window.title());windowProcess.set(window.process());slot.title=window.title();slot.process=window.process();slot.sourceKind=window.sourceKind();slot.sourceId=window.sourceId();if(!preservePlacement||slot.base==null)placeAhead(slot);saveCurrent();saveScreens();}
    public String selectedProcess(){return current().process;}
    public boolean selectedDesktop(){return current().sourceKind.equals("monitor");}
    public boolean relatedWindow(AppCaptureEngine.Window window){return !selectedDesktop()&&!window.desktop()&&window.process().equalsIgnoreCase(selectedProcess());}
    public boolean selectedSource(AppCaptureEngine.Window window){Slot slot=current();AppCaptureEngine.Window selected=slot.capture.currentWindow();if(selected==null)selected=slot.selected;return selected!=null&&selected.sourceKey().equals(window.sourceKey());}
    public long selectedWindowHandle(){Slot slot=current();AppCaptureEngine.Window selected=slot.capture.currentWindow();if(selected==null)selected=slot.selected;return selected==null?0:selected.handle();}
    public void retry(){Slot slot=current();leaveInteraction();slot.generation++;slot.choosing=false;slot.request="";slot.status="";slot.capture.stop();}
    public String selectedLabel(){Slot slot=current();AppCaptureEngine.Window active=slot.capture.currentWindow();return active!=null?active.label():slot.selected==null?slot.title:slot.selected.label();}
    public String statusKey(){return statusKey(current());}
    private String statusKey(Slot slot){return !AppCaptureEngine.supported()?"ui.appcast.unsupported":!slot.status.isEmpty()?slot.status:slot.title.isBlank()?"ui.appcast.choose":slot.capture.statusKey();}
    public String inputStatusKey(){return selectedDesktop()?"ui.appcast.desktopViewOnly":current().capture.inputStatusKey();}
    public String liveStats(){Slot slot=current();AppCaptureEngine.Texture image=slot.capture.image();String dimensions=image==null?"—":image.width()+" × "+image.height();return String.format(Locale.ROOT,"%s · %s · %s %.1f / %s %.1f FPS · %s %d / %s %d",Lang.t(slot.capture.backendKey()),dimensions,Lang.t("ui.appcast.captureFps"),slot.capture.actualFps(),Lang.t("ui.appcast.displayFps"),slot.capture.displayFps(),Lang.t("ui.appcast.received"),slot.capture.receivedFrames(),Lang.t("ui.appcast.uploaded"),slot.capture.uploadedFrames());}
    public String unchangedStats(){return Lang.t("ui.appcast.sampleUnchanged")+String.format(Locale.ROOT," %.1f s",current().capture.unchangedMillis()/1000.0);}
    @Override public String suffix(){return Lang.t(statusKey())+" · "+slots.size();}
    public AppCaptureEngine.Texture image(){return image(current());}
    private AppCaptureEngine.Texture image(Slot slot){AppCaptureEngine.Texture image=slot.capture.image();if(image!=null)slot.aspect=image.width()/(float)image.height();return image;}
    public boolean follows(){return anchor.is("appcast_follow");}
    public ModeSetting anchorSetting(){return anchor;}
    public void cycleAnchor(){anchor.set(anchor.modes.get((anchor.modes.indexOf(anchor.get())+1)%anchor.modes.size()));placeAhead(current());saveCurrent();}
    public void follow(boolean value){anchor.set(value?"appcast_follow":"appcast_fixed");placeAhead(current());saveCurrent();}
    public Vec3 center(float partial){return center(current(),partial);}
    private Vec3 center(Slot slot,float partial){if(slot.base==null&&inGame())placeAhead(slot);Vec3 base=slot.base==null?Vec3.ZERO:slot.base;String kind=mode(slot,"anchor","appcast_fixed");if(kind.equals("appcast_follow")&&mc.player!=null)base=mc.player.getPosition(partial).add(0,mc.player.getEyeHeight(),0);
        double dx=decimal(slot,"x",0),dy=decimal(slot,"y",.3),dz=decimal(slot,"z",3),length=Math.sqrt(dx*dx+dy*dy+dz*dz);if(length>8){dx*=8/length;dy*=8/length;dz*=8/length;}
        if(kind.equals("appcast_view")&&inGame()){var camera=mc.gameRenderer.getMainCamera();return ViewSpaceAnchor.position(camera.getPosition(),new Vec3(camera.getLeftVector()).scale(-1),new Vec3(camera.getUpVector()),new Vec3(camera.getLookVector()),dx,dy,dz);}
        double rad=Math.toRadians(slot.baseYaw);return base.add(Math.cos(rad)*dx-Math.sin(rad)*dz,dy,Math.sin(rad)*dx+Math.cos(rad)*dz);}
    public void setCenter(Vec3 center){Slot slot=current();if(mode(slot,"anchor","appcast_fixed").equals("appcast_view")&&inGame()){var camera=mc.gameRenderer.getMainCamera();Vec3 delta=center.subtract(camera.getPosition());if(delta.length()>8)delta=delta.normalize().scale(8);x.set(delta.dot(new Vec3(camera.getLeftVector()).scale(-1)));y.set(delta.dot(new Vec3(camera.getUpVector())));z.set(delta.dot(new Vec3(camera.getLookVector())));saveCurrent();return;}Vec3 base=follows()&&mc.player!=null?mc.player.getEyePosition():slot.base;if(base==null){placeAhead(slot);base=slot.base;}Vec3 delta=center.subtract(base);if(delta.length()>8)delta=delta.normalize().scale(8);double rad=Math.toRadians(slot.baseYaw);x.set(Math.cos(rad)*delta.x+Math.sin(rad)*delta.z);y.set(delta.y);z.set(-Math.sin(rad)*delta.x+Math.cos(rad)*delta.z);saveCurrent();}
    private void placeAhead(Slot slot){if(!inGame())return;slot.base=mc.player.getEyePosition();slot.baseYaw=mc.player.getYRot();slot.values.put("x",new JsonPrimitive(0));slot.values.put("y",new JsonPrimitive(.3));slot.values.put("z",new JsonPrimitive(3));slot.values.put("yaw",new JsonPrimitive(wrap(180+slot.baseYaw)));if(slot==slots.get(selectedIndex)){x.set(0d);y.set(.3d);z.set(3d);yaw.set(wrap(180+slot.baseYaw));}}
    public void placeInFront(){placeAhead(current());saveCurrent();}
    public AppCastPlane plane(float partial){saveCurrent();return plane(current(),partial);}
    public AppCastPlane planeForScreen(int index,float partial){ensureLoaded();saveCurrent();int wanted=index==-1?selectedIndex:index;return wanted<0||wanted>=slots.size()?null:plane(slots.get(wanted),partial);}
    private AppCastPlane plane(Slot slot,float partial){float width=(float)decimal(slot,"width",3),angle=(float)decimal(slot,"yaw",180),tilt=(float)decimal(slot,"pitch",0);if(mode(slot,"anchor","appcast_fixed").equals("appcast_view")&&inGame()){var camera=mc.gameRenderer.getMainCamera();angle=(float)wrap(180+camera.getYRot()+wrap(angle-(180+slot.baseYaw)));tilt=(float)wrap(-camera.getXRot()+tilt);}return new AppCastPlane(center(slot,partial),width,width/slot.aspect,angle,tilt,(float)decimal(slot,"roll",0));}
    public void resize(double factor){width.set(width.val()*factor);saveCurrent();}
    public void rotate(double dx,double dy,boolean rolling){if(rolling)roll.set(wrap(roll.val()+dx));else{yaw.set(wrap(yaw.val()+dx));pitch.set(wrap(pitch.val()+dy));}saveCurrent();}
    public void moveDepth(double amount){AppCastPlane plane=plane(1);setCenter(plane.center().add(plane.normal().scale(amount)));}
    public Vec3 renderCamera(){return renderCamera;}
    public double[] pointerPosition(double u,double v){if(viewProjection==null||renderCamera==null||renderedPointerPlane==null||System.nanoTime()-renderTime>500_000_000L)return null;Vec3 point=renderedPointerPlane.point(u,v).subtract(renderCamera);Vector4f projected=new Vector4f((float)point.x,(float)point.y,(float)point.z,1).mul(viewProjection);if(projected.w<=.00001)return null;return new double[]{(projected.x/projected.w*.5+.5)*mc.getWindow().getWidth(),(.5-projected.y/projected.w*.5)*mc.getWindow().getHeight()};}
    public Vec3 screenRay(double guiX,double guiY){return screenRayFraction(guiX/Math.max(1,mc.getWindow().getGuiScaledWidth()),guiY/Math.max(1,mc.getWindow().getGuiScaledHeight()));}
    private Vec3 screenRayFraction(double sx,double sy){if(inverseView==null||renderCamera==null||System.nanoTime()-renderTime>500_000_000L)return null;Vector4f near=new Vector4f((float)sx*2-1,1-(float)sy*2,-1,1).mul(inverseView),far=new Vector4f((float)sx*2-1,1-(float)sy*2,1,1).mul(inverseView);if(Math.abs(near.w)<.000001||Math.abs(far.w)<.000001)return null;return new Vec3(far.x/far.w-near.x/near.w,far.y/far.w-near.y/near.w,far.z/far.w-near.z/near.w).normalize();}
    public AppCastPlane.Hit aimHit(){return hit(current(),screenRayFraction(.5,.5));}
    private AppCastPlane.Hit hit(Slot slot,Vec3 ray){if(ray==null||renderCamera==null||mc.level==null||mc.player==null)return null;AppCastPlane plane=plane(slot,1);AppCastPlane.Hit hit=plane.pick(renderCamera,ray,true);if(hit==null||plane.normal().dot(ray)>-.00001)return null;Vec3 target=plane.point(hit.u(),hit.v());var block=mc.level.clip(new ClipContext(renderCamera,target,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));if(block.getType()!=HitResult.Type.MISS&&block.getLocation().distanceToSqr(renderCamera)+.02<target.distanceToSqr(renderCamera))return null;return hit;}
    public void enterInteraction(){if(!interaction.on()||!isEnabled()||!inGame()||mc.screen!=null)return;ensureLoaded();saveCurrent();Slot chosen=null;AppCastPlane.Hit best=null;Vec3 ray=screenRayFraction(.5,.5);for(Slot slot:slots){AppCastPlane.Hit hit=hit(slot,ray);if(hit!=null&&(best==null||hit.distance()<best.distance())){best=hit;chosen=slot;}}
        if(chosen==null)return;selectScreen(slots.indexOf(chosen));if(chosen.capture.image()==null||chosen.selected==null||chosen.selected.desktop())return;KeyMapping.releaseAll();
        if(inputMode.is("appcast_native_foreground")){releaseInput();AppCaptureEngine.Window active=chosen.capture.currentWindow();if(!com.cheatprogram.capture.AppInputFocus.activateSelected(active==null?chosen.selected:active))Notifications.push(Lang.t("ui.appcast.title"),Lang.t("ui.appcast.nativeActivationFailed"),false);return;}
        mc.setScreen(new AppCastInputScreen(this,best.u(),best.v()));}
    public void leaveInteraction(){releaseInput();if(inputActive())mc.setScreen(null);KeyMapping.releaseAll();}
    public void releaseInput(){for(Slot slot:slots)slot.capture.releaseInput();}
    public int clientWidth(){return current().capture.cachedClientWidth();}
    public int clientHeight(){return current().capture.cachedClientHeight();}
    public boolean inputMove(double u,double v){return !selectedDesktop()&&current().capture.move(u,v);}
    public boolean inputDrag(double u,double v){return !selectedDesktop()&&current().capture.drag(u,v);}
    public boolean inputButton(int button,boolean down,double u,double v){return !selectedDesktop()&&current().capture.button(button,down,u,v);}
    public boolean inputScroll(double x,double y,double u,double v){return !selectedDesktop()&&current().capture.scroll(x,y,u,v);}
    public boolean inputKey(int key,boolean down,boolean repeat){return !selectedDesktop()&&current().capture.key(key,down,repeat);}
    public boolean inputChar(char c){return !selectedDesktop()&&current().capture.character(c);}
    public boolean inputText(String text){return !selectedDesktop()&&current().capture.text(text);}
    public boolean inputStillValid(){return isEnabled()&&interaction.on()&&inGame()&&mc.isWindowActive()&&Config.persistenceAllowed()&&!selectedDesktop()&&current().capture.running();}
    private static double wrap(double angle){angle%=360;if(angle>180)angle-=360;if(angle< -180)angle+=360;return angle;}
    private static double decimal(Slot slot,String key,double fallback){try{return slot.values.containsKey(key)?slot.values.get(key).getAsDouble():fallback;}catch(RuntimeException ignored){return fallback;}}
    private static int number(Slot slot,String key,int fallback){return(int)Math.round(decimal(slot,key,fallback));}
    private static boolean bool(Slot slot,String key,boolean fallback){try{return slot.values.containsKey(key)?slot.values.get(key).getAsBoolean():fallback;}catch(RuntimeException ignored){return fallback;}}
    private static String mode(Slot slot,String key,String fallback){try{return slot.values.containsKey(key)?slot.values.get(key).getAsString():fallback;}catch(RuntimeException ignored){return fallback;}}
    @Override public void onRender3D(Render3D renderer){if(!inGame()||world!=mc.level||RearMirrorRenderer.isRendering())return;ensureLoaded();saveCurrent();viewProjection=renderer.projectionMatrix().mul(renderer.viewMatrix());inverseView=new Matrix4f(viewProjection).invert();renderCamera=renderer.camera;renderTime=System.nanoTime();
        RenderStateSnapshot state=new RenderStateSnapshot();try{com.cheatprogram.compat.gl.Framebuffers.bindWrite(mc.getMainRenderTarget(), true);for(Slot slot:slots){AppCaptureEngine.Texture texture=image(slot);AppCastPlane plane=plane(slot,renderer.partialTick);if(slot==slots.get(selectedIndex))renderedPointerPlane=plane;plane.draw(renderer,texture==null?null:texture.id(),(float)decimal(slot,"opacity",100)/100,bool(slot,"throughWalls",false),bool(slot,"border",false)||mc.screen instanceof com.cheatprogram.gui.AppCastEditorScreen,(float)decimal(slot,"brightness",100)/100);
            }}
        finally{state.restore();}}
}
