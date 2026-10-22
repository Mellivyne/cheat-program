package com.cheatprogram.compat;
public final class NativeUi262 {
    private NativeUi262() { }
    public static void setHidden(net.minecraft.client.Minecraft mc,boolean hidden) {
        if(mc.gui.hud.isHidden()!=hidden)mc.gui.hud.toggle();
    }
}
