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
        var mc=Minecraft.getInstance();
        var cast=ModuleManager.get(AppCast.class);
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
}
