package com.cheatprogram.module.utility;

import com.cheatprogram.core.Category;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.module.inventory.*;
import com.cheatprogram.module.world.BuildingBlueprints;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.ai.AiController;
import com.cheatprogram.module.visual.Freecam;
import com.cheatprogram.gui.TaskQueueScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import com.cheatprogram.util.InventoryAutomation;
import java.util.*;

/** Executes actual workers and restores their previous flags/settings after completion or stopping. */
public final class TaskQueue extends Module {
    public final TextSetting plan=add(new TextSetting("plan","equip, clean, loot, refill",4096));
    private final NumberSetting timeout=add(new NumberSetting("timeout",120,5,3600,5,"s"));
    private final NumberSetting waitSeconds=add(new NumberSetting("waitSeconds",2,.1,120,.1,"s"));
    private final NumberSetting health=add(new NumberSetting("health",8,0,20,1));
    private final BoolSetting pauseManual=add(new BoolSetting("pauseManual",true));
    private final BoolSetting autoResume=add(new BoolSetting("autoResume",false));
    private final Map<Module,Boolean> previous=new LinkedHashMap<>();
    private final Map<BoolSetting,Boolean> savedSettings=new LinkedHashMap<>();
    private volatile List<String> steps=List.of();
    private volatile int cursor;
    private int stable,tick;
    private volatile boolean running,paused,entered,ownsBuilding;
    private String state="ui.queue.ready";
    private Object connection,level;
    private long stageMillis,frozenAt,completedSince;
    public TaskQueue(){super("taskqueue",Category.UTILITY);}
    public boolean running(){return running;}
    public boolean paused(){return paused;}
    public String status(){return Lang.t(state)+(running?" · "+(cursor+1)+" / "+steps.size()+" · "+Lang.t("queue.step."+steps.get(cursor)):"");}
    public void open(Screen parent){mc.gui.setScreen(new TaskQueueScreen(parent,this));}
    public void start(){
        stop(false);if(!inGame()){state="ui.queue.noWorld";return;}
        var requested=Arrays.stream(plan.get().toLowerCase(Locale.ROOT).split("[,;，；\\s]+" )).filter(s->!s.isBlank()).toList();
        if(requested.isEmpty()||requested.size()>128||requested.stream().anyMatch(s->!Set.of("equip","clean","loot","refill","build","wait").contains(s))){state="ui.queue.invalid";return;}
        BuildingBlueprints buildings=ModuleManager.get(BuildingBlueprints.class);
        if(requested.contains("build")&&(buildings==null||buildings.busy()||buildings.loaded()==null)){state="ui.queue.needBlueprint";return;}
        for(String id:List.of("autoarmor","invcleaner","cheststealer","refill")){
            Module module=ModuleManager.byId(id);if(module!=null)previous.put(module,module.isEnabled());
        }
        ownsBuilding=requested.contains("build");
        if(ownsBuilding)previous.put(buildings,buildings.isEnabled());
        steps=requested;cursor=stable=tick=0;running=true;paused=entered=false;
        connection=mc.getConnection();level=mc.level;stageMillis=System.currentTimeMillis();frozenAt=0;state="ui.queue.running";
        // Queue owns these four workers temporarily, preventing them from running ahead of the current step.
        inventoryWorkers(null);
        if(!isEnabled())setEnabled(true);
    }
    public void togglePause(){
        if(!running)return;
        if(paused){paused=false;state="ui.queue.running";}
        else pause("ui.queue.paused");
    }
    private void freeze(long now){if(frozenAt==0)frozenAt=now;}
    private void thaw(long now){if(frozenAt!=0){stageMillis+=now-frozenAt;frozenAt=0;}}
    private void pause(String reason){
        paused=true;stable=0;completedSince=0;freeze(System.currentTimeMillis());state=reason;
        // A close request is processed outside worker ticks; cancel it when pausing too.
        for(Module worker:previous.keySet())InventoryAutomation.cancelCloseOwnInventory(worker);
    }
    public void stop(){stop(true);}
    private void stop(boolean report){
        if(running&&entered&&cursor<steps.size()&&steps.get(cursor).equals("build"))ModuleManager.get(BuildingBlueprints.class).cancel();
        running=paused=entered=false;ownsBuilding=false;frozenAt=completedSince=0;
        for(Module worker:previous.keySet())InventoryAutomation.cancelCloseOwnInventory(worker);
        for(var entry:savedSettings.entrySet())entry.getKey().set(entry.getValue());savedSettings.clear();
        for(var entry:previous.entrySet())entry.getKey().setEnabled(entry.getValue());previous.clear();
        if(report)state="ui.queue.stopped";
    }
    public void checkLifecycle(){if(running&&(!inGame()||connection!=mc.getConnection()||level!=mc.level)){stop(false);state="ui.queue.worldChanged";}}
    @Override public void onDisable(){stop();}
    private void inventoryWorkers(String active){for(String id:List.of("autoarmor","invcleaner","cheststealer","refill")){Module module=ModuleManager.byId(id);if(module!=null)module.setEnabled(id.equals(active));}}
    private void setBool(Module module,String id,boolean value){
        for(var setting:module.settings())if(setting.id.equals(id)&&setting instanceof BoolSetting flag){savedSettings.putIfAbsent(flag,flag.on());flag.set(value);}
    }
    public static boolean mayTick(Module module){
        TaskQueue queue=ModuleManager.get(TaskQueue.class);
        if(queue==null||!queue.running||!queue.previous.containsKey(module))return true;
        if(queue.paused||!queue.entered)return false;
        String active=switch(queue.steps.get(queue.cursor)){
            case "equip"->"autoarmor";case "clean"->"invcleaner";case "loot"->"cheststealer";
            case "refill"->"refill";case "build"->"buildingblueprints";default->"";
        };
        return module.id.equals(active);
    }
    public static boolean buildingPaused(){
        TaskQueue queue=ModuleManager.get(TaskQueue.class);
        if(queue==null||!queue.running||!queue.ownsBuilding)return false;
        int index=queue.cursor;List<String> snapshot=queue.steps;
        return !queue.entered||queue.paused||index<0||index>=snapshot.size()||!snapshot.get(index).equals("build");
    }
    private boolean usableWorkerScreen(){
        if(mc.gui.screen()==null)return true;
        if(!entered)return false;
        String step=steps.get(cursor);
        if(step.equals("loot"))return mc.gui.screen() instanceof AbstractContainerScreen<?>;
        return Set.of("equip","clean","refill").contains(step)
                &&(mc.gui.screen() instanceof InventoryScreen||mc.gui.screen() instanceof CreativeModeInventoryScreen);
    }
    private boolean manual(){return mc.options.keyUp.isDown()||mc.options.keyDown.isDown()||mc.options.keyLeft.isDown()||mc.options.keyRight.isDown()||mc.options.keyJump.isDown()||mc.options.keyAttack.isDown()||mc.options.keyUse.isDown();}
    @Override public void onTick(){
        checkLifecycle();if(!running)return;tick++;
        boolean danger=!mc.player.isAlive()||mc.player.getHealth()<=health.val()||AiController.isRunning()||Freecam.isActive();
        boolean manual=pauseManual.on()&&manual();
        if(danger||manual){pause(danger?"ui.queue.danger":"ui.queue.manual");return;}
        if(paused){if(autoResume.on()&&!state.equals("ui.queue.paused"))togglePause();else return;}
        long now=System.currentTimeMillis();
        if(!usableWorkerScreen()||mc.isPaused()||!mc.isWindowActive()){freeze(now);return;}
        if(entered&&Set.of("equip","clean","refill").contains(steps.get(cursor))&&!InventoryAutomation.canUseOwnInventory()){
            freeze(now);return;
        }
        thaw(now);
        if(now-stageMillis>timeout.val()*1000){stop(false);state="ui.queue.timeout";return;}
        String step=steps.get(cursor);
		if(entered&&(step.equals("equip")&&ModuleManager.get(AutoArmor.class).refusedWork()
				||step.equals("clean")&&ModuleManager.get(InvCleaner.class).refusedWork()
				||step.equals("loot")&&ModuleManager.get(ChestStealer.class).refusedWork()
				||step.equals("refill")&&ModuleManager.get(Refill.class).refusedWork())){
			stop(false);state="ui.queue.workerFailed";return;
		}
        if(!entered){
            entered=true;stable=0;completedSince=0;stageMillis=now;
            String worker=switch(step){case "equip"->"autoarmor";case "clean"->"invcleaner";case "loot"->"cheststealer";case "refill"->"refill";default->null;};
            inventoryWorkers(worker);
            if(worker!=null){Module module=ModuleManager.byId(worker);setBool(module,"autoClose",true);setBool(module,"autoDiscover",true);}
            if(step.equals("build")){
                BuildingBlueprints buildings=ModuleManager.get(BuildingBlueprints.class);buildings.setEnabled(true);buildings.paste();
            }
            return;
        }
        boolean done=switch(step){
            case "equip"->!ModuleManager.get(AutoArmor.class).hasPendingWork();
            case "clean"->!ModuleManager.get(InvCleaner.class).hasPendingWork();
            case "loot"->ModuleManager.get(ChestStealer.class).queueCycleIdle();
            case "refill"->!ModuleManager.get(Refill.class).hasPendingWork();
            case "build"->!ModuleManager.get(BuildingBlueprints.class).busy();
            default->now-stageMillis>=waitSeconds.val()*1000;
        };
        if(!done){stable=0;completedSince=0;return;}
        if(completedSince==0)completedSince=now;
        long quiet=250;
        if(mc.getSingleplayerServer()==null&&mc.getConnection()!=null){
            var info=mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if(info!=null)quiet=Math.max(quiet,Math.min(5000,info.getLatency()*2L+150));
        }
        if(++stable<5||now-completedSince<quiet)return;
        if(step.equals("refill")&&ModuleManager.get(Refill.class).refusedWork()
                ||step.equals("clean")&&!ModuleManager.get(InvCleaner.class).previewEntries().isEmpty()){
            stop(false);state="ui.queue.workerFailed";return;
        }
        if(step.equals("build")&&!ModuleManager.get(BuildingBlueprints.class).lastPasteSucceeded()){
            stop(false);state="ui.queue.buildFailed";return;
        }
        inventoryWorkers(null);cursor++;entered=false;stable=0;completedSince=0;stageMillis=now;
        if(cursor>=steps.size()){stop(false);state="ui.queue.complete";}
    }
    @Override public void onRender2D(HudContext hud){if(running)hud.c.textMid(Fonts.Face.REGULAR,status(),hud.width/2,hud.height-67,10,paused?0xffffddb0:0xffe5faff);}
}
