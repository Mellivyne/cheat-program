package com.cheatprogram.module.utility;

import com.cheatprogram.CheatProgram;
import com.cheatprogram.core.Category;
import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Module;
import com.cheatprogram.core.setting.*;
import com.cheatprogram.gui.DeathRecoveryScreen;
import com.cheatprogram.hud.HudContext;
import com.cheatprogram.render.*;
import com.cheatprogram.util.ContainerIndex;
import com.cheatprogram.util.RecoveryRoute;
import com.google.gson.Gson;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import java.nio.file.*;
import java.util.*;

public final class DeathRecovery extends Module {
    public record LostItem(String id,String name,int count) { }
    public record Death(String context,String dimension,int x,int y,int z,long time,List<LostItem> items) {
        public BlockPos position(){return new BlockPos(x,y,z);}
    }
    private final BoolSetting routeEnabled=add(new BoolSetting("route",true));
    private final BoolSetting marker=add(new BoolSetting("marker",true));
    private final NumberSetting historyLimit=add(new NumberSetting("historyLimit",24,1,100,1));
    private final NumberSetting lineWidth=add(new NumberSetting("lineWidth",2,1,6,.5));
    private final List<Death> history=new ArrayList<>();
    private final Path file=Config.directory().resolve("death-recovery.json");
    private Death selected;
    private Object world,player;
    private boolean wasAlive,loaded;
    private List<LostItem> inventory=List.of();
    private RecoveryRoute route;
    private int ticks;
    public DeathRecovery(){super("deathrecovery",Category.UTILITY);}
    public void open(Screen parent){load();mc.gui.setScreen(new DeathRecoveryScreen(parent,this));}
    public List<Death> history(){load();return List.copyOf(history);}
    public Death selected(){return selected;}
    public void select(Death death){selected=death;route=null;}
    public void delete(Death death){history.remove(death);if(selected==death){selected=null;route=null;}save();}
    public String routeStatus(){return route==null?"ui.recovery.noRoute":route.state();}
    private void load(){
        if(loaded)return;loaded=true;
        try{
            if(Files.isRegularFile(file)&&Files.size(file)<1_000_000){
                Death[] entries=new Gson().fromJson(Files.readString(file),Death[].class);
                if(entries!=null)for(Death death:entries)if(death!=null&&death.context!=null&&death.dimension!=null&&death.items!=null&&death.time>0&&history.size()<100){
                    List<LostItem> items=death.items.stream().filter(item->item!=null&&item.id!=null&&item.name!=null&&item.count>0).limit(128).toList();
                    history.add(new Death(death.context,death.dimension,death.x,death.y,death.z,death.time,items));
                }
            }
        }catch(Exception e){CheatProgram.LOGGER.warn("Could not read death recovery history",e);}
    }
    private void save(){
        synchronized(Config.persistenceLock()) {
        if(!Config.persistenceAllowed())return;
        try{Files.createDirectories(file.getParent());Path pending=file.resolveSibling("death-recovery.json.tmp");Files.writeString(pending,new Gson().toJson(history));
            try{Files.move(pending,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException ignored){Files.move(pending,file,StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception e){CheatProgram.LOGGER.warn("Could not save death recovery history",e);}
        }
    }
    public void checkLifecycle(){if(!inGame()){world=player=null;wasAlive=false;inventory=List.of();route=null;}}
    @Override public void onDisable(){world=player=null;route=null;inventory=List.of();wasAlive=false;}
    @Override public void onTick(){
        load();ticks++;
        // A replaced player/world is a new observation context, not an alive -> dead edge.
        if(world!=mc.level||player!=mc.player){world=mc.level;player=mc.player;wasAlive=mc.player.isAlive();route=null;inventory=List.of();}
        boolean alive=mc.player.isAlive();
        if(wasAlive&&!alive){
            BlockPos p=mc.player.blockPosition();
            Death death=new Death(ContainerIndex.context(),mc.level.dimension().identifier().toString(),p.getX(),p.getY(),p.getZ(),System.currentTimeMillis(),inventory);
            history.addFirst(death);while(history.size()>historyLimit.intVal())history.removeLast();select(death);save();
        }
        wasAlive=alive;player=mc.player;
        if(alive){
            var next=new ArrayList<LostItem>();
            for(int i=0;i<mc.player.getInventory().getContainerSize();i++){
                var stack=mc.player.getInventory().getItem(i);if(!stack.isEmpty())next.add(new LostItem(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getHoverName().getString(),stack.getCount()));
            }
            inventory=List.copyOf(next);
        }
        if(!alive||!validSelection()||!routeEnabled.on()){route=null;return;}
        BlockPos start=mc.player.blockPosition();
        if(route==null||ticks%40==0&&(start.distSqr(route.start())>9||route.state().equals("ui.recovery.noPath"))
                ||ticks%20==0&&!route.stillClear())route=new RecoveryRoute(mc.level,start,selected.position());
        route.advance();
    }
    private boolean validSelection(){return selected!=null&&selected.context.equals(ContainerIndex.context())&&selected.dimension.equals(mc.level.dimension().identifier().toString());}
    @Override public void onRender3D(Render3D renderer){
        if(!inGame()||!validSelection())return;
        Vec3 point=Vec3.atBottomCenterOf(selected.position());
        var lines=new ArrayList<WorldLines.Segment>();
        if(marker.on()){
            lines.add(new WorldLines.Segment(point.add(-.4,.05,0),point.add(.4,.05,0),0xffd8f3ff));
            lines.add(new WorldLines.Segment(point.add(0,.05,-.4),point.add(0,.05,.4),0xffd8f3ff));
            lines.add(new WorldLines.Segment(point,point.add(0,4,0),0x80d8f3ff));
        }
        if(routeEnabled.on()&&route!=null){List<Vec3> path=route.path();for(int i=1;i<path.size();i++)lines.add(new WorldLines.Segment(path.get(i-1),path.get(i),0xccffffff));}
        WorldLines.draw(renderer,lines,lineWidth.floatVal(),true);
    }
    @Override public void onRender2D(HudContext hud){
        if(!inGame()||!validSelection())return;
        double distance=mc.player.position().distanceTo(Vec3.atCenterOf(selected.position()));
        long seconds=Math.max(0,(System.currentTimeMillis()-selected.time)/1000);
        String line=Lang.t("ui.recovery.hud",String.format(Locale.ROOT,"%.0f",distance),seconds/60,seconds%60);
        hud.c.textMid(Fonts.Face.REGULAR,line,hud.width/2,hud.height-48,10,0xffe2f3ff);
    }
}
