package com.cheatprogram.compat;
import com.cheatprogram.mixin.ClientInputCompatAccessor;
import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;
public final class InputBridge {
    private InputBridge(){}
    public static float forward(ClientInput input){return input.getMoveVector().y;}
    public static float sideways(ClientInput input){return input.getMoveVector().x;}
    public static void forward(ClientInput input,float value){((ClientInputCompatAccessor)input).cheatprogram$moveVector(new Vec2(input.getMoveVector().x,value));}
    public static void sideways(ClientInput input,float value){((ClientInputCompatAccessor)input).cheatprogram$moveVector(new Vec2(value,input.getMoveVector().y));}
    public static void set(ClientInput input,float forward,float sideways){
        if(!Float.isFinite(forward)||!Float.isFinite(sideways)){forward=0;sideways=0;}
        Vec2 vector=new Vec2(sideways,forward);
        if(vector.lengthSquared()>1)vector=vector.normalized();
        ((ClientInputCompatAccessor)input).cheatprogram$moveVector(vector);
    }
}
