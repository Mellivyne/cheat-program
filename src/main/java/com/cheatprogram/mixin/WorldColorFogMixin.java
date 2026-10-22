package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.WorldColors;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.FogParameters;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(FogRenderer.class)
public abstract class WorldColorFogMixin {
    @ModifyReturnValue(method="computeFogColor",at=@At("RETURN"))
    private static Vector4f cheatprogram$color(Vector4f original,Camera camera,float partial,ClientLevel level,int distance,float darken){
        WorldColors colors=WorldColors.active();return colors==null?original:colors.fogColor(original,camera);
    }
    @ModifyReturnValue(method="setupFog",at=@At("RETURN"))
    private static FogParameters cheatprogram$fog(FogParameters original,Camera camera,FogRenderer.FogMode mode,Vector4f color,float distance,boolean thick,float partial){
        WorldColors colors=WorldColors.active();return colors==null?original:colors.fog(original,camera);
    }
}
