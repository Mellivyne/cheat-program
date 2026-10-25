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
   for(String name:java.util.List.of("com.cheatprogram.module.combat.KillAura","com.cheatprogram.module.world.Scaffold","com.cheatprogram.module.movement.Flight","com.cheatprogram.core.Friends")){
    try{Class.forName(name);throw new AssertionError("Unexpected implementation: "+name);}catch(ClassNotFoundException expected){}
   }
   ClientSettings.INSTANCE.language.set("zh");mc.options.pauseOnLostFocus=false;
   String world="open-preview-validation";
   if(mc.getLevelSource().levelExists(world))mc.createWorldOpenFlows().openWorld(world,()->mc.setScreen(new TitleScreen()));
   else mc.createWorldOpenFlows().createFreshLevel(world,new LevelSettings(world,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(FeatureFlags.DEFAULT_FLAGS),WorldDataConfiguration.DEFAULT),new WorldOptions(20261022L,true,false),WorldPresets::createNormalWorldDimensions,new TitleScreen());stage++;}
 case 1->{if(mc.player==null||mc.screen!=null)return;try{ModelCatalogueCheck.start();InteractionRegressionCheck.run();ParticleDesignerCheck.run();com.cheatprogram.ai.AiImageProtocolCheck.run();com.cheatprogram.dev.ParticleDesignerWireCheck.run();}catch(Exception e){throw new RuntimeException(e);}try{var building=ModuleManager.get(com.cheatprogram.module.world.BuildingBlueprints.class);building.previewBuilding(BuildingDesignerCheck.run());var ui=new BuildingBlueprintScreen(null,building);var page=BuildingBlueprintScreen.class.getDeclaredField("page");page.setAccessible(true);page.setInt(ui,2);mc.setScreen(ui);stage=20;wait=100;}catch(Exception e){throw new RuntimeException(e);}}
 case 20->{if(!ModelCatalogueCheck.finish())return;shot(mc,"building-materials-v11");try{var floor=BuildingBlueprintScreen.class.getDeclaredMethod("setFloor",int.class);floor.setAccessible(true);floor.invoke(mc.screen,1);}catch(Exception e){throw new RuntimeException(e);}stage=22;wait=60;}
 case 22->{shot(mc,"building-floor-v11");try{var f=BuildingBlueprintScreen.class.getDeclaredField("preview");f.setAccessible(true);var renderer=(com.cheatprogram.render.BuildingModelPreview)f.get(mc.screen);renderer.set(ModuleManager.get(com.cheatprogram.module.world.BuildingBlueprints.class).loaded());renderer.tour(true);}catch(Exception e){throw new RuntimeException(e);}stage=21;wait=60;}
 case 21->{shot(mc,"building-tour-v11");try{var building=ModuleManager.get(com.cheatprogram.module.world.BuildingBlueprints.class);var ui=new BuildingBlueprintScreen(null,building);var page=BuildingBlueprintScreen.class.getDeclaredField("page");page.setAccessible(true);page.setInt(ui,3);mc.setScreen(ui);stage=23;wait=60;}catch(Exception e){throw new RuntimeException(e);}}
 case 23->{shot(mc,"building-ai-v11");var ui=new AiParticleScreen(null,ModuleManager.get(ParticleEffects.class));try{var result=AiParticleScreen.class.getDeclaredField("result");result.setAccessible(true);result.set(ui,com.cheatprogram.ai.AiParticleDesigner.decode(com.google.gson.JsonParser.parseString("{\"rounds\":[{\"name\":\"Native orbit\",\"duration\":200,\"emitters\":[{\"particle\":\"minecraft:end_rod\",\"every\":2,\"count\":2,\"path\":{\"kind\":\"circle\",\"radius\":1.5}}]}]}").getAsJsonObject()));}catch(Exception e){throw new RuntimeException(e);}mc.setScreen(ui);stage=24;wait=100;}
 case 24->{shot(mc,"particle-ai-v11");try{ModelCatalogueCheck.openPicker(mc.screen,"models");}catch(Exception e){throw new RuntimeException(e);}stage=25;wait=40;}
 case 25->{shot(mc,"particle-model-picker");mc.setScreen(new AlbumSpaceCheck("floor"));stage=26;wait=60;}
 case 26->{shot(mc,"albums-floor");mc.setScreen(new AlbumSpaceCheck("stars"));stage=27;wait=60;}
 case 27->{shot(mc,"albums-stars");mc.setScreen(new AlbumSpaceCheck("spiral"));stage=28;wait=60;}
 case 28->{shot(mc,"albums-spiral");AlbumSpaceCheck.cleanup();mc.setScreen(new ClickGuiScreen());stage=2;wait=60;}
 case 2->{shot(mc,"open-features");((ClickGuiScreen)mc.screen).devTab(2);stage=7;wait=60;}
 case 7->{shot(mc,"open-ai-control");((ClickGuiScreen)mc.screen).devTab(3);stage=8;wait=60;}
 case 8->{shot(mc,"open-ai-players");mc.setScreen(new ModelWearScreen(null,ModuleManager.get(ModelWear.class)));stage=3;wait=60;}
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
