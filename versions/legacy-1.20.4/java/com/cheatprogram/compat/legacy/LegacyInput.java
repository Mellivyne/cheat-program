package com.cheatprogram.compat.legacy;
public record LegacyInput(boolean forward,boolean backward,boolean left,boolean right,boolean jump,boolean shift,boolean sprint){
    public static final LegacyInput EMPTY=new LegacyInput(false,false,false,false,false,false,false);
    public void apply(net.minecraft.client.player.Input input){input.up=forward;input.down=backward;input.left=left;input.right=right;input.jumping=jump;input.shiftKeyDown=shift;}
}
