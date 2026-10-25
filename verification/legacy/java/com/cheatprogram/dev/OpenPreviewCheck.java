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
 private static int stage,wait; private static boolean positioned; private static long start=System.currentTimeMillis();
 public static void tick(){var mc=Minecraft.getInstance();if(System.currentTimeMillis()-start>240000){mc.stop();throw new IllegalStateException("Open check timeout");}if(wait-->0)return;
 switch(stage){
 case 0->{if(mc.getOverlay()!=null)return;if(!(mc.screen instanceof TitleScreen)){if(mc.screen!=null&&mc.screen.getClass().getSimpleName().contains("Accessibility"))mc.setScreen(new TitleScreen());return;}
   if(ModuleManager.byId("killaura")!=null||ModuleManager.byId("scaffold")!=null)throw new AssertionError("Excluded feature registered");
   if(ModuleManager.all().size()!=47)throw new AssertionError("Unexpected feature count: "+ModuleManager.all().size());
   for(String name:java.util.List.of("com.cheatprogram.module.combat.KillAura","com.cheatprogram.module.world.Scaffold","com.cheatprogram.module.movement.Flight","com.cheatprogram.core.Friends")){
    try{Class.forName(name);throw new AssertionError("Unexpected implementation: "+name);}catch(ClassNotFoundException expected){}
   }
   ClientSettings.INSTANCE.language.set("zh");mc.options.pauseOnLostFocus=false;
   String world="legacy-preview-validation-"+net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString()+"-"+System.currentTimeMillis();
   var settings=new LevelSettings(world,GameType.CREATIVE,false,Difficulty.PEACEFUL,true,new GameRules(),WorldDataConfiguration.DEFAULT);
   java.util.function.Function<net.minecraft.core.RegistryAccess,net.minecraft.world.level.levelgen.WorldDimensions> dimensions=WorldPresets::createNormalWorldDimensions;
   try{var flows=mc.createWorldOpenFlows();try{flows.getClass().getMethod("createFreshLevel",String.class,LevelSettings.class,WorldOptions.class,java.util.function.Function.class,net.minecraft.client.gui.screens.Screen.class).invoke(flows,world,settings,new WorldOptions(20261022L,true,false),dimensions,new TitleScreen());}catch(NoSuchMethodException old){flows.getClass().getMethod("createFreshLevel",String.class,LevelSettings.class,WorldOptions.class,java.util.function.Function.class).invoke(flows,world,settings,new WorldOptions(20261022L,true,false),dimensions);}}catch(Exception e){throw new RuntimeException(e);}
   stage++;}
 case 1->{if(mc.player==null||mc.screen!=null)return;if(!positioned){positioned=true;mc.player.getAbilities().flying=true;mc.player.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);var id=mc.player.getUUID();var server=mc.getSingleplayerServer();server.execute(()->{var player=server.getPlayerList().getPlayer(id);if(player!=null){player.getAbilities().flying=true;player.onUpdateAbilities();player.connection.teleport(22.5,120,77.5,0,10);}});wait=40;return;}try{ParticleDesignerCheck.run();com.cheatprogram.ai.AiImageProtocolCheck.run();com.cheatprogram.dev.ParticleDesignerWireCheck.run();}catch(Exception e){throw new RuntimeException(e);}try{var building=ModuleManager.get(com.cheatprogram.module.world.BuildingBlueprints.class);building.previewBuilding(BuildingDesignerCheck.run());var ui=new BuildingBlueprintScreen(null,building);var page=BuildingBlueprintScreen.class.getDeclaredField("page");page.setAccessible(true);page.setInt(ui,2);mc.setScreen(ui);stage=20;wait=100;}catch(Exception e){throw new RuntimeException(e);}}
 case 20->{shot(mc,"building-materials-v11");try{var floor=BuildingBlueprintScreen.class.getDeclaredMethod("setFloor",int.class);floor.setAccessible(true);floor.invoke(mc.screen,1);}catch(Exception e){throw new RuntimeException(e);}stage=22;wait=60;}
 case 22->{shot(mc,"building-floor-v11");try{var f=BuildingBlueprintScreen.class.getDeclaredField("preview");f.setAccessible(true);var renderer=(com.cheatprogram.render.BuildingModelPreview)f.get(mc.screen);renderer.set(ModuleManager.get(com.cheatprogram.module.world.BuildingBlueprints.class).loaded());renderer.tour(true);}catch(Exception e){throw new RuntimeException(e);}stage=21;wait=60;}
 case 21->{shot(mc,"building-tour-v11");try{var building=ModuleManager.get(com.cheatprogram.module.world.BuildingBlueprints.class);var ui=new BuildingBlueprintScreen(null,building);var page=BuildingBlueprintScreen.class.getDeclaredField("page");page.setAccessible(true);page.setInt(ui,3);mc.setScreen(ui);stage=23;wait=60;}catch(Exception e){throw new RuntimeException(e);}}
 case 23->{shot(mc,"building-ai-v11");var ui=new AiParticleScreen(null,ModuleManager.get(ParticleEffects.class));try{var result=AiParticleScreen.class.getDeclaredField("result");result.setAccessible(true);result.set(ui,com.cheatprogram.ai.AiParticleDesigner.decode(com.google.gson.JsonParser.parseString("{\"rounds\":[{\"name\":\"Native orbit\",\"duration\":200,\"emitters\":[{\"particle\":\"minecraft:end_rod\",\"every\":2,\"count\":2,\"path\":{\"kind\":\"circle\",\"radius\":1.5}}]}]}").getAsJsonObject()));}catch(Exception e){throw new RuntimeException(e);}mc.setScreen(ui);stage=24;wait=100;}
 case 24->{
 try{var field=AiParticleScreen.class.getDeclaredField("player");field.setAccessible(true);if(!((com.cheatprogram.render.ParticlePlayerPreview)field.get(mc.screen)).ready())throw new AssertionError("Native player preview unavailable");}catch(Exception e){throw new RuntimeException(e);}
 for(var shader:new com.cheatprogram.render.Shaders.Program[]{com.cheatprogram.render.Shaders.GLASS,com.cheatprogram.render.Shaders.RRECT,com.cheatprogram.render.Shaders.BLUR_UP,com.cheatprogram.render.Shaders.BLUR_DOWN})if(com.cheatprogram.render.Shaders.get(shader)==null)throw new AssertionError("Native glass shader unavailable");
 try{var f=AiParticleScreen.class.getDeclaredField("particles");f.setAccessible(true);if(((com.cheatprogram.render.ParticleLayoutPreview)f.get(mc.screen)).alive()<1)throw new AssertionError("No native particles emitted");}catch(Exception e){throw new RuntimeException(e);}
 particlePixels(mc);shot(mc,"particle-ai-v11");mc.setScreen(new ClickGuiScreen());stage=2;wait=60;}
 case 2->{shot(mc,"open-features");((ClickGuiScreen)mc.screen).devTab(2);stage=7;wait=60;}
 case 7->{shot(mc,"open-ai-control");((ClickGuiScreen)mc.screen).devTab(3);stage=8;wait=60;}
 case 8->{shot(mc,"open-ai-players");mc.setScreen(new ModelWearScreen(null,ModuleManager.get(ModelWear.class)));stage=3;wait=60;}
 case 3->{shot(mc,"open-player-preview");try {
 var data=new com.cheatprogram.util.WearModelData.Model(java.util.List.of(new com.cheatprogram.util.WearModelData.Part(-1,new float[]{-.5f,1,0,0,0,1,0,0,1, .5f,1,0,0,0,1,0,0,1, 0,2,0,0,0,1,0,0,1})),java.util.List.of(),1);
 var model=ModuleManager.get(ModelWear.class);model.asset(com.cheatprogram.util.WearModelStore.install(com.cheatprogram.util.WearModelData.encode(data)));model.move(1,0,0);
 }catch(Exception e){throw new RuntimeException(e);}stage=6;wait=60;}
 case 6->{shot(mc,"open-model-with-player");ModuleManager.get(ModelWear.class).asset("");mc.setScreen(null);ModuleManager.get(Hud.class).setEnabled(true);stage=4;wait=120;}
 case 4->{shot(mc,"open-clear-island");System.out.println("LEGACY_CHECK_PASS features=47 preview-rendered");stage++;wait=20;}
 case 5->{mc.stop();stage++;}
 }
 }
 private static void particlePixels(Minecraft mc){
  try{
   var player=AiParticleScreen.class.getDeclaredField("player");player.setAccessible(true);
   var f=com.cheatprogram.render.ParticlePlayerPreview.class.getDeclaredField("target");f.setAccessible(true);
   var target=(com.mojang.blaze3d.pipeline.TextureTarget)f.get(player.get(mc.screen));
   int read=org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER_BINDING),bright=0;
   var pixels=org.lwjgl.system.MemoryUtil.memAlloc(target.width*target.height*4);
   try{
    org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,target.frameBufferId);
    org.lwjgl.opengl.GL11.glReadPixels(0,0,target.width,target.height,org.lwjgl.opengl.GL11.GL_RGBA,org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,pixels);
    // The player body stays within the center. White native end-rod pixels outside it
    // prove particles reached the preview framebuffer, rather than merely being alive.
    for(int y=0;y<target.height;y++)for(int x=0;x<target.width;x++){
     if(Math.abs(x-target.width/2)<40)continue;int i=(y*target.width+x)*4;
     if((pixels.get(i)&255)>160&&(pixels.get(i+1)&255)>160&&(pixels.get(i+2)&255)>160&&(pixels.get(i+3)&255)>32)bright++;
    }
   }finally{org.lwjgl.opengl.GL30.glBindFramebuffer(org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER,read);org.lwjgl.system.MemoryUtil.memFree(pixels);}
   if(bright<2)throw new AssertionError("Native particles did not reach preview pixels: "+bright);
   System.out.println("NATIVE_PARTICLE_PIXELS_PASS bright-pixels="+bright);
  }catch(ReflectiveOperationException e){throw new RuntimeException(e);}
 }
 private static void shot(Minecraft mc,String name){Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),m->System.out.println(m.getString()));}
}
