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
