package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.WorldColors;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(FogRenderer.class)
public abstract class WorldColorFogMixin {
    // 26.1 computes color into an output parameter, then returns the complete
    // extraction state. Alter that state before its native GPU upload.
    @ModifyReturnValue(method="setupFog",at=@At("RETURN"))
    private FogData cheatprogram$fog(FogData original,Camera camera,int distance,DeltaTracker delta,float darken,ClientLevel level){
        WorldColors colors=WorldColors.active();
        if(colors!=null){original.color=colors.fogColor(original.color,camera);colors.fog(original,camera);}
        return original;
    }
}
