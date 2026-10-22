package com.cheatprogram.dev;
import com.cheatprogram.core.*;
import com.cheatprogram.gui.*;
import com.cheatprogram.module.visual.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.*;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
public final class OpenPreviewCheck {
 private static int stage,wait; private static long start=System.currentTimeMillis();
 public static void tick(){var mc=Minecraft.getInstance();if(System.currentTimeMillis()-start>180000){mc.stop();throw new IllegalStateException("Open check timeout");}if(wait-->0)return;
 switch(stage){
 case 0->{if(mc.getOverlay()!=null)return;if(!(mc.screen instanceof TitleScreen)){if(mc.screen!=null&&mc.screen.getClass().getSimpleName().contains("Accessibility"))mc.setScreen(new TitleScreen());return;}
   if(ModuleManager.byId("killaura")!=null||ModuleManager.byId("scaffold")!=null)throw new AssertionError("Excluded feature registered");
   if(ModuleManager.all().size()!=47)throw new AssertionError("Unexpected feature count: "+ModuleManager.all().size());
   ClientSettings.INSTANCE.language.set("zh");mc.options.pauseOnLostFocus=false;
   String world="open-preview-validation";
   if(mc.getLevelSource().levelExists(world))mc.createWorldOpenFlows().openWorld(world,()->mc.setScreen(new TitleScreen()));
   else mc.createWorldOpenFlows().createFreshLevel(world,new LevelSettings(world,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(FeatureFlags.DEFAULT_FLAGS),WorldDataConfiguration.DEFAULT),new WorldOptions(20261022L,true,false),WorldPresets::createNormalWorldDimensions,new TitleScreen());stage++;}
 case 1->{if(mc.player==null||mc.screen!=null)return;mc.setScreen(new ClickGuiScreen());stage++;wait=60;}
 case 2->{shot(mc,"open-features");mc.setScreen(new ModelWearScreen(null,ModuleManager.get(ModelWear.class)));stage++;wait=60;}
 case 3->{shot(mc,"open-player-preview");try {
 var data=new com.cheatprogram.util.WearModelData.Model(java.util.List.of(new com.cheatprogram.util.WearModelData.Part(-1,new float[]{-.5f,1,0,0,0,1,0,0,1, .5f,1,0,0,0,1,0,0,1, 0,2,0,0,0,1,0,0,1})),java.util.List.of(),1);
 var model=ModuleManager.get(ModelWear.class);model.asset(com.cheatprogram.util.WearModelStore.install(com.cheatprogram.util.WearModelData.encode(data)));model.move(1,0,0);
 }catch(Exception e){throw new RuntimeException(e);}stage=6;wait=60;}
 case 6->{shot(mc,"open-model-with-player");ModuleManager.get(ModelWear.class).asset("");mc.setScreen(null);ModuleManager.get(Hud.class).setEnabled(true);stage=4;wait=120;}
 case 4->{shot(mc,"open-clear-island");System.out.println("OPEN_CHECK_PASS features=47 preview-rendered");stage++;wait=20;}
 case 5->{mc.stop();stage++;}
 }
 }
 private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),m->System.out.println(m.getString()));}
}
