package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.WorldColors;
import com.cheatprogram.compat.legacy.LegacyRenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(FogRenderer.class)
public abstract class WorldColorFogMixin {
 @Shadow private static float fogRed;
 @Shadow private static float fogGreen;
 @Shadow private static float fogBlue;
 @Inject(method="setupColor",at=@At("TAIL"))
 private static void cheatprogram$color(Camera camera,float partial,ClientLevel level,int distance,float darken,CallbackInfo ci){
  var colors=WorldColors.active();if(colors==null)return;var value=colors.fogColor(new Vector4f(fogRed,fogGreen,fogBlue,1),camera);fogRed=value.x;fogGreen=value.y;fogBlue=value.z;com.mojang.blaze3d.systems.RenderSystem.clearColor(fogRed,fogGreen,fogBlue,0);
 }
 @Inject(method="setupFog",at=@At("TAIL"))
 private static void cheatprogram$fog(Camera camera,FogRenderer.FogMode mode,float distance,boolean thick,float partial,CallbackInfo ci){var colors=WorldColors.active();if(colors!=null)LegacyRenderSystem.setShaderFog(colors.fog(LegacyRenderSystem.getShaderFog(),camera));}
}
