package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.WorldColors;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import java.nio.ByteBuffer;
@Mixin(FogRenderer.class)
public abstract class WorldColorFogMixin {
    @ModifyReturnValue(method="computeFogColor",at=@At("RETURN"))
    private Vector4f cheatprogram$color(Vector4f original,Camera camera,float partial,ClientLevel level,int distance,float darken,boolean thick){
        WorldColors colors=WorldColors.active();return colors==null?original:colors.fogColor(original,camera);
    }
    @WrapOperation(method="setupFog",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/fog/FogRenderer;updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V"))
    private void cheatprogram$fog(FogRenderer renderer,ByteBuffer buffer,int offset,Vector4f color,
                                 float environmentalStart,float environmentalEnd,float renderStart,float renderEnd,float skyEnd,float cloudEnd,
                                 Operation<Void> original,@Local(argsOnly=true) Camera camera){
        FogData data=new FogData();data.environmentalStart=environmentalStart;data.environmentalEnd=environmentalEnd;
        data.renderDistanceStart=renderStart;data.renderDistanceEnd=renderEnd;data.skyEnd=skyEnd;data.cloudEnd=cloudEnd;
        WorldColors colors=WorldColors.active();if(colors!=null)colors.fog(data,camera);
        original.call(renderer,buffer,offset,color,data.environmentalStart,data.environmentalEnd,data.renderDistanceStart,data.renderDistanceEnd,data.skyEnd,data.cloudEnd);
    }
}
