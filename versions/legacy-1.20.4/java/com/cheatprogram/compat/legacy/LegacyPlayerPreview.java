package com.cheatprogram.compat.legacy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
/** Unticked preview body using the current player's cached skin profile. Never joins the live level. */
public final class LegacyPlayerPreview {
    public static RemotePlayer player(){var mc=Minecraft.getInstance();var p=new RemotePlayer(mc.level,mc.player.getGameProfile());p.setPos(0,0,0);p.setYRot(0);p.setXRot(0);p.yBodyRot=p.yBodyRotO=0;p.yHeadRot=p.yHeadRotO=0;return p;}
}
