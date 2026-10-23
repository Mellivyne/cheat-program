package com.cheatprogram.dev;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.gui.AppCastScreen;
import com.cheatprogram.module.visual.AppCast;
import com.cheatprogram.module.visual.FloatingSubtitles;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Runs only in the isolated development world, never in release artifacts. */
final class InteractionRegressionCheck {
    static void run() throws Exception {
        CurvedSurfaceCheck.run();
        var mc=Minecraft.getInstance();
        var cast=ModuleManager.get(AppCast.class);
        checkCurveSettings(cast);
        checkMultipleScreens(cast);
        int originalKey=cast.interactionKey(); boolean enabled=cast.isEnabled(),interaction=cast.interactionEnabled();
        var previous=mc.screen;
        try {
            cast.setEnabled(true);cast.interaction(true);
            var screen=new AppCastScreen(null,cast);mc.setScreen(screen);
            var binding=AppCastScreen.class.getDeclaredField("binding");binding.setAccessible(true);
            for(int key:new int[]{GLFW.GLFW_KEY_R,GLFW.GLFW_KEY_R,GLFW.GLFW_KEY_T,GLFW.GLFW_KEY_ESCAPE}){
                binding.setBoolean(screen,true);
                if(AppCast.key(key,GLFW.GLFW_PRESS))throw new AssertionError("Cast stole settings key");
                screen.keyPressed(key,0,0);
                if(binding.getBoolean(screen)||cast.interactionKey()!=(key==GLFW.GLFW_KEY_ESCAPE?-1:key))throw new AssertionError("Rebind failed");
            }
        } finally {cast.interactionKey(originalKey);cast.interaction(interaction);cast.setEnabled(enabled);mc.setScreen(previous);}

        var lyrics=ModuleManager.get(FloatingSubtitles.class);
        String originalMode=lyrics.anchor.get();var originalPosition=mc.player.position();
        var method=FloatingSubtitles.class.getDeclaredMethod("phraseAge",String.class,int.class,long.class,boolean.class);method.setAccessible(true);
        var placement=FloatingSubtitles.class.getDeclaredField("placement");placement.setAccessible(true);
        var display=FloatingSubtitles.class.getDeclaredField("displayPosition");display.setAccessible(true);
        try {
            lyrics.anchor.set("lyrics_phrase");lyrics.recenter();
            method.invoke(lyrics,"track:0",0,1L,false);Object first=placement.get(lyrics);var firstDisplay=display.get(lyrics);
            mc.player.setPos(originalPosition.add(3,0,0));
            method.invoke(lyrics,"track:0",0,2L,false);
            if(placement.get(lyrics)!=first||!display.get(lyrics).equals(firstDisplay))throw new AssertionError("Line followed player continuously");
            method.invoke(lyrics,"track:1",1,3L,false);
            if(placement.get(lyrics)==first||display.get(lyrics).equals(firstDisplay))throw new AssertionError("New line did not relocate");
            lyrics.anchor.set("lyrics_fixed");lyrics.recenter();
            method.invoke(lyrics,"track:0",0,4L,false);Object fixed=placement.get(lyrics);
            mc.player.setPos(originalPosition);
            method.invoke(lyrics,"track:1",1,5L,false);
            if(placement.get(lyrics)!=fixed)throw new AssertionError("Fixed placement moved");
        } finally {mc.player.setPos(originalPosition);lyrics.anchor.set(originalMode);lyrics.recenter();}
        System.out.println("INTERACTION_CHECK_PASS repeated-rebind phrase-relocation fixed-placement");
    }


    private static java.lang.reflect.Field field(Class<?> type,String name)throws Exception{
        var field=type.getDeclaredField(name);field.setAccessible(true);return field;
    }
    @SuppressWarnings("unchecked")
    private static void checkMultipleScreens(AppCast cast)throws Exception{
        var saved=new java.util.HashMap<String,com.google.gson.JsonElement>();
        for(var setting:cast.settings())saved.put(setting.id,setting.save().deepCopy());
        var screens=(com.cheatprogram.core.setting.TextSetting)cast.settings().stream().filter(s->s.id.equals("screens")).findFirst().orElseThrow();
        var camera=field(AppCast.class,"renderCamera");Object oldCamera=camera.get(cast);
        var captures=new java.util.ArrayList<com.cheatprogram.capture.AppCaptureEngine>();
        try{
            screens.set("[{\"settings\":{}},{\"settings\":{}},{\"settings\":{}}]");cast.screenEntries();
            var slots=(java.util.List<Object>)field(AppCast.class,"slots").get(cast);
            var origin=new net.minecraft.world.phys.Vec3(0,250,0);camera.set(cast,origin);
            Class<?> slotType=slots.getFirst().getClass();
            Class<?> sessionType=Class.forName("com.cheatprogram.capture.AppCaptureEngine$Session");
            var constructor=sessionType.getDeclaredConstructor(com.cheatprogram.capture.AppCaptureEngine.class,com.cheatprogram.capture.AppCaptureEngine.Window.class,boolean.class);constructor.setAccessible(true);
            var queues=new java.util.ArrayList<java.util.Queue<String>>();
            var sessions=new java.util.ArrayList<Object>();
            for(int i=0;i<3;i++){
                Object slot=slots.get(i);
                var values=(java.util.Map<String,com.google.gson.JsonElement>)field(slotType,"values").get(slot);
                values.put("x",new com.google.gson.JsonPrimitive(0));values.put("y",new com.google.gson.JsonPrimitive(0));values.put("z",new com.google.gson.JsonPrimitive(3));
                values.put("yaw",new com.google.gson.JsonPrimitive(0));values.put("throughWalls",new com.google.gson.JsonPrimitive(true));
                values.put("curved",new com.google.gson.JsonPrimitive(i==1));values.put("curvature",new com.google.gson.JsonPrimitive(60));
                field(slotType,"base").set(slot,origin);field(slotType,"baseYaw").setFloat(slot,0);
                var window=new com.cheatprogram.capture.AppCaptureEngine.Window(100+i,0,"test","test",false);
                field(slotType,"selected").set(slot,window);
                var capture=(com.cheatprogram.capture.AppCaptureEngine)field(slotType,"capture").get(slot);captures.add(capture);
                Object session=constructor.newInstance(capture,window,true);sessions.add(session);
                field(sessionType,"viewReady").setBoolean(session,true);field(capture.getClass(),"session").set(capture,session);
                field(capture.getClass(),"image").set(capture,new com.cheatprogram.capture.AppCaptureEngine.Texture(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cheatprogram","test/screen"+i),640,360,640,360));
                field(capture.getClass(),"textureReady").setBoolean(capture,true);field(capture.getClass(),"status").set(capture,"ui.appcast.live");
                queues.add((java.util.Queue<String>)field(sessionType,"commands").get(session));
            }
            // Sync the selected controls once, without overwriting the test placements.
            var load=AppCast.class.getDeclaredMethod("loadCurrent");load.setAccessible(true);load.invoke(cast);
            var pick=AppCast.class.getDeclaredMethod("interactionTarget",net.minecraft.world.phys.Vec3.class);pick.setAccessible(true);
            var ray=new net.minecraft.world.phys.Vec3(0,0,1);
            // All three overlap. The last rendered one, not the first list entry,
            // owns the visible pixels. Both front and back faces must be usable.
            for(int expected:new int[]{2,1,0}){
                Object target=pick.invoke(cast,ray);
                if(target==null||field(target.getClass(),"slot").get(target)!=slots.get(expected))throw new AssertionError("Wrong visible projection: "+expected);
                cast.selectScreen(expected);queues.forEach(java.util.Queue::clear);
                if(!cast.inputMove(.25,.5)||!cast.inputButton(0,true,.25,.5)||!cast.inputDrag(.5,.5)||!cast.inputButton(0,false,.5,.5)||!cast.inputScroll(0,1,.5,.5))throw new AssertionError("Secondary projection rejected input");
                var commands=new java.util.ArrayList<>(queues.get(expected));
                if(!commands.equals(java.util.List.of("M\t160\t180","B\t0\t1\t160\t180","M\t320\t180","B\t0\t0\t320\t180","W\t0\t120\t320\t180")))throw new AssertionError("Wrong input sequence: "+commands);
                for(int j=0;j<3;j++)if(j!=expected&&!queues.get(j).isEmpty())throw new AssertionError("Input leaked to another screen");
                field(slotType,"base").set(slots.get(expected),origin.add(30,0,0));
            }
            // Return to the third screen after visiting the first; readiness and
            // window identity must belong to that source, not to slot zero.
            field(slotType,"base").set(slots.get(2),origin);
            var target=pick.invoke(cast,ray);if(target==null||field(target.getClass(),"slot").get(target)!=slots.get(2))throw new AssertionError("Could not return to third projection");
            cast.selectScreen(2);queues.forEach(java.util.Queue::clear);
            if(!cast.inputScroll(1,-1,.1,.9)||queues.get(2).isEmpty()||!queues.get(0).isEmpty()||!queues.get(1).isEmpty())throw new AssertionError("Return input routing failed");
            field(sessionType,"viewReady").setBoolean(sessions.get(2),false);
            if(captures.get(2).inputReady()||cast.inputButton(0,true,.5,.5))throw new AssertionError("Stale frame accepted input");
            field(captures.get(2).getClass(),"textureReady").setBoolean(captures.get(2),false);
            if(pick.invoke(cast,ray)!=null)throw new AssertionError("Blank projection intercepted input");
            System.out.println("MULTISCREEN_CHECK_PASS three-sources overlap backfaces curved-screen click-drag-scroll return-to-third stale-frame");
        }finally{
            for(var capture:captures){field(capture.getClass(),"image").set(capture,null);capture.stop();}
            for(var setting:cast.settings())setting.load(saved.get(setting.id));cast.screenEntries();camera.set(cast,oldCamera);
        }
    }

    private static void checkCurveSettings(AppCast cast){
        var saved=new java.util.HashMap<String,com.google.gson.JsonElement>();
        for(var setting:cast.settings())saved.put(setting.id,setting.save().deepCopy());
        var curved=(com.cheatprogram.core.setting.BoolSetting)cast.settings().stream().filter(s->s.id.equals("curved")).findFirst().orElseThrow();
        var angle=(com.cheatprogram.core.setting.NumberSetting)cast.settings().stream().filter(s->s.id.equals("curvature")).findFirst().orElseThrow();
        var axis=(com.cheatprogram.core.setting.ModeSetting)cast.settings().stream().filter(s->s.id.equals("curveAxis")).findFirst().orElseThrow();
        var screens=(com.cheatprogram.core.setting.TextSetting)cast.settings().stream().filter(s->s.id.equals("screens")).findFirst().orElseThrow();
        try{
            screens.set("[{\"settings\":{}},{\"settings\":{}}]");cast.screenEntries();cast.selectScreen(0);
            curved.set(true);angle.set(110.0);axis.set("appcast_curve_vertical");cast.plane(1);
            cast.selectScreen(1);
            if(curved.on()||angle.val()!=60||!axis.is("appcast_curve_horizontal"))throw new AssertionError("Legacy screen inherited curve settings");
            curved.set(true);angle.set(-45.0);cast.plane(1);cast.selectScreen(0);
            if(!curved.on()||angle.val()!=110||!axis.is("appcast_curve_vertical"))throw new AssertionError("Per-screen curve not preserved");
            cast.selectScreen(1);if(angle.val()!=-45)throw new AssertionError("Second curve not preserved");
            for(boolean vertical:new boolean[]{false,true})for(float a:new float[]{-120,0,120}){
                var plane=new com.cheatprogram.render.AppCastPlane(new net.minecraft.world.phys.Vec3(4,70,-6),3,2,37,24,-13,a,vertical,48);
                for(double u:new double[]{.001,.25,.5,.999})for(double v:new double[]{.001,.5,.999}){
                    var target=plane.point(u,v);var normal=plane.normal(u,v);var hit=plane.pick(target.add(normal.scale(.4)),normal.scale(-1),true);
                    if(hit==null||Math.abs(hit.u()-u)>.00001||Math.abs(hit.v()-v)>.00001)throw new AssertionError("Rotated curve picking mismatch");
                }
            }
            System.out.println("CURVED_WORLD_CHECK_PASS rotated-picking per-screen-settings legacy-defaults");
        }finally{for(var setting:cast.settings())setting.load(saved.get(setting.id));cast.screenEntries();}
    }
}
