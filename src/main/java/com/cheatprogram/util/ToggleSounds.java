package com.cheatprogram.util;
import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.visual.Hud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

/** Two original, short interface tones. One cue per toggle, even with both notification hosts enabled. */
public final class ToggleSounds {
    private static long last;
    private ToggleSounds(){ }
    public static void play(boolean enabled,boolean togglingHud){
        Minecraft mc=Minecraft.getInstance();Hud hud=ModuleManager.get(Hud.class);
        if(hud==null||!hud.toggleSoundEnabled()||com.cheatprogram.core.Config.restartRequired())return;
        if(!mc.isSameThread()){mc.execute(()->play(enabled,togglingHud));return;}
        float volume=Math.max(0,Math.min(1,hud.toggleSoundVolume()));if(volume<=0)return;
        long now=System.nanoTime();if(now-last<35_000_000L)return;last=now;
        SoundEvent sound=SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("cheatprogram",enabled?"toggle_on":"toggle_off"));
        // A missing/reloading resource must still yield an interface cue, using vanilla's cached UI sound.
        if(mc.getSoundManager().getSoundEvent(sound.location())==null)sound=SoundEvents.UI_BUTTON_CLICK.value();
        mc.getSoundManager().play(SimpleSoundInstance.forUI(sound,enabled?1.05f:.88f,volume));
    }
}
