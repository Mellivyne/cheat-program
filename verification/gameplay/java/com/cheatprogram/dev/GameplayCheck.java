package com.cheatprogram.dev;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.visual.Hud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.lwjgl.glfw.GLFW;

/** Optional normal-gameplay probe. Every world and screenshot belongs to run-gameplay/<version>. */
public final class GameplayCheck {
    private static int stage,wait;
    private static final long START=System.currentTimeMillis();
    private static final AtomicBoolean prepared=new AtomicBoolean();
    private static final BlockPos TARGET=new BlockPos(0,102,3),ANCHOR=new BlockPos(0,102,4);
    public static void tick() throws Exception {
        Minecraft mc=Minecraft.getInstance();
        if(System.currentTimeMillis()-START>180000){mc.stop();throw new AssertionError("Gameplay probe timed out at "+stage);}
        if(wait-->0)return;
        switch(stage){
            case 0->{
                if(mc.getOverlay()!=null)return;
                if(!(mc.screen instanceof TitleScreen)){if(mc.screen!=null&&mc.screen.getClass().getSimpleName().contains("Accessibility"))mc.setScreen(new TitleScreen());return;}
                mc.options.pauseOnLostFocus=false;mc.options.renderDistance().set(2);
                for(var module:ModuleManager.all())module.setEnabled(false);
                ModuleManager.get(Hud.class).setEnabled(true);
                for(var setting:ModuleManager.get(Hud.class).settings())if(setting instanceof com.cheatprogram.core.setting.BoolSetting b&&(setting.id.equals("inventoryGlass")||setting.id.equals("titleIntro")))b.set(false);
                createWorld(mc);stage=1;
            }
            case 1->{
                if(mc.player==null||mc.level==null||mc.screen!=null)return;
                var server=mc.getSingleplayerServer();String player=mc.player.getScoreboardName();
                server.execute(()->{try{
                    for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++)server.overworld().getChunk(cx,cz);
                    String[] commands={"gamerule doDaylightCycle false","time set noon","weather clear","fill -10 100 -8 10 100 12 minecraft:stone","fill -7 101 -7 7 108 9 minecraft:air","setblock 0 102 3 minecraft:stone","setblock 0 102 4 minecraft:stone","gamemode creative "+player,"tp "+player+" 0.5 101 0.5 0 0","item replace entity "+player+" weapon.mainhand with minecraft:diamond_pickaxe","summon minecraft:sheep -1.5 101 3 {NoAI:1b,Silent:1b}","summon minecraft:cow 2.5 101 3 {NoAI:1b,Silent:1b}"};
                    Object commandsObject=call(server,"getCommands"),source=call(server,"createCommandSourceStack");
                    for(String command:commands)call(commandsObject,"performPrefixedCommand",source,command);
                    prepared.set(true);
                }catch(Exception e){throw new RuntimeException(e);}});stage=2;wait=80;
            }
            case 2->{
                if(!prepared.get())return;
                if(!mc.level.getBlockState(TARGET).is(Blocks.STONE))throw new AssertionError("Probe target stone not initialized; player="+mc.player.position());
                if(mc.getCameraEntity()!=mc.player)throw new AssertionError("Detached gameplay camera");
                shot(mc,"gameplay-hand-entities");
                System.out.println("GAMEPLAY_CAMERA_PASS player-camera HUD-enabled");
                pressKey(mc,GLFW.GLFW_KEY_E,GLFW.GLFW_PRESS);pressKey(mc,GLFW.GLFW_KEY_E,GLFW.GLFW_RELEASE);stage=3;wait=5;
            }
            case 3->{
                if(mc.screen==null||!mc.screen.getClass().getSimpleName().contains("Inventory"))throw new AssertionError("E did not open inventory: "+(mc.screen==null?"null":mc.screen.getClass().getName()));
                System.out.println("GAMEPLAY_INVENTORY_PASS "+mc.screen.getClass().getSimpleName());shot(mc,"gameplay-inventory");stage=4;wait=20;
            }
            case 4->{mc.screen.onClose();stage=5;wait=15;}
            case 5->{
                if(mc.screen!=null)throw new AssertionError("Inventory did not close");
                mc.player.setItemInHand(InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
                mc.hitResult=hit(TARGET);System.out.println("GAMEPLAY_ATTACK_RESULT "+call(mc,"startAttack")+" held="+mc.player.getMainHandItem()+" pos="+mc.player.position());stage=6;wait=12;
            }
            case 6->{
                if(!mc.level.getBlockState(TARGET).isAir()||!mc.getSingleplayerServer().submit(()->mc.getSingleplayerServer().overworld().getBlockState(TARGET).isAir()).get(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Native attack did not remove target block");
                System.out.println("GAMEPLAY_MINING_PASS native-startAttack server-confirmed");
                var server=mc.getSingleplayerServer();String player=mc.player.getScoreboardName();prepared.set(false);
                server.execute(()->{try{call(call(server,"getCommands"),"performPrefixedCommand",call(server,"createCommandSourceStack"),"item replace entity "+player+" weapon.mainhand with minecraft:gold_block");prepared.set(true);}catch(Exception e){throw new RuntimeException(e);}});stage=7;wait=12;
            }
            case 7->{if(!prepared.get())return;mc.hitResult=hit(ANCHOR);call(mc,"startUseItem");stage=8;wait=12;}
            case 8->{
                if(!mc.level.getBlockState(TARGET).is(Blocks.GOLD_BLOCK)||!mc.getSingleplayerServer().submit(()->mc.getSingleplayerServer().overworld().getBlockState(TARGET).is(Blocks.GOLD_BLOCK)).get(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Native item use did not place gold block");
                System.out.println("GAMEPLAY_PLACEMENT_PASS native-startUseItem server-confirmed");shot(mc,"gameplay-after-actions");stage=9;wait=20;
            }
            case 9->{System.out.println("GAMEPLAY_CHECK_PASS inventory mining placement hand-entity-screenshots");stage=10;mc.stop();}
        }
    }
    private static BlockHitResult hit(BlockPos pos){return new BlockHitResult(new Vec3(.5,102.5,pos.getZ()),Direction.NORTH,pos,false);}
    private static void pressKey(Minecraft mc,int key,int action)throws Exception{
        long window=0;try{window=((Number)call(mc.getWindow(),"getWindow")).longValue();}catch(NoSuchMethodException e){window=((Number)call(mc.getWindow(),"handle")).longValue();}
        for(Method m:mc.keyboardHandler.getClass().getDeclaredMethods())if(m.getName().equals("keyPress")){
            m.setAccessible(true);
            if(m.getParameterCount()==5)m.invoke(mc.keyboardHandler,window,key,0,action,0);
            else {Class<?> event=m.getParameterTypes()[2];Object value=event.getDeclaredConstructor(int.class,int.class,int.class).newInstance(key,0,0);m.invoke(mc.keyboardHandler,window,action,value);}
            return;
        }throw new NoSuchMethodException("keyPress");
    }
    private static void createWorld(Minecraft mc)throws Exception{
        String name="gameplay-"+System.currentTimeMillis();
        Class<?> settings=Class.forName("net.minecraft.world.level.LevelSettings"),gameType=Class.forName("net.minecraft.world.level.GameType"),difficulty=Class.forName("net.minecraft.world.Difficulty"),configuration=Class.forName("net.minecraft.world.level.WorldDataConfiguration");
        Object creative=gameType.getField("CREATIVE").get(null),peaceful=difficulty.getField("PEACEFUL").get(null),defaults=configuration.getField("DEFAULT").get(null),levelSettings=null;
        for(Constructor<?> ctor:settings.getConstructors()){
            if(ctor.getParameterCount()==7){
                Class<?> rules=ctor.getParameterTypes()[5];Object ruleSet;
                try{ruleSet=rules.getConstructor().newInstance();}catch(NoSuchMethodException e){Class<?> flags=Class.forName("net.minecraft.world.flag.FeatureFlags");Object set=flags.getField("DEFAULT_FLAGS").get(null);ruleSet=rules.getConstructors()[0].newInstance(set);}
                levelSettings=ctor.newInstance(name,creative,false,peaceful,true,ruleSet,defaults);
            }else if(ctor.getParameterCount()==5){Class<?> ds=ctor.getParameterTypes()[2];Object diff=ds.getDeclaredConstructor(difficulty,boolean.class,boolean.class).newInstance(peaceful,false,false);levelSettings=ctor.newInstance(name,creative,diff,true,defaults);}
        }
        Object options=Class.forName("net.minecraft.world.level.levelgen.WorldOptions").getConstructor(long.class,boolean.class,boolean.class).newInstance(20261025L,true,false);
        java.util.function.Function<Object,Object> dimensions=registry->{try{return callStatic(Class.forName("net.minecraft.world.level.levelgen.presets.WorldPresets"),"createNormalWorldDimensions",registry);}catch(Exception e){throw new RuntimeException(e);}};
        Object flows=mc.createWorldOpenFlows();
        try{call(flows,"createFreshLevel",name,levelSettings,options,dimensions,new TitleScreen());}catch(NoSuchMethodException e){call(flows,"createFreshLevel",name,levelSettings,options,dimensions);}
    }
    private static Object callStatic(Class<?> type,String name,Object...args)throws Exception{return invoke(null,type,name,args);}
    private static Object call(Object target,String name,Object...args)throws Exception{return invoke(target,target.getClass(),name,args);}
    private static Object invoke(Object target,Class<?> type,String name,Object[] args)throws Exception{
        for(Class<?> c=type;c!=null;c=c.getSuperclass())for(Method m:c.getDeclaredMethods())if(m.getName().equals(name)&&m.getParameterCount()==args.length){
            boolean match=true;for(int i=0;i<args.length;i++)if(args[i]!=null&&!m.getParameterTypes()[i].isPrimitive()&&!m.getParameterTypes()[i].isInstance(args[i]))match=false;
            if(!match)continue;m.setAccessible(true);return m.invoke(target,args);
        }throw new NoSuchMethodException(type.getName()+"."+name);
    }
    private static void shot(Minecraft mc,String name)throws Exception{
        Class<?> screenshot=Class.forName("net.minecraft.client.Screenshot");Consumer<Object> callback=value->System.out.println("GAMEPLAY_SCREENSHOT "+name);
        try{callStatic(screenshot,"grab",mc.gameDirectory,name+".png",mc.getMainRenderTarget(),callback);}catch(NoSuchMethodException e){callStatic(screenshot,"grab",mc.gameDirectory,name+".png",mc.getMainRenderTarget(),1,callback);}
    }
}
