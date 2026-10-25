package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.Fullbright;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
 @Shadow @Final private NativeImage lightPixels;
 @Shadow @Final private DynamicTexture lightTexture;
 @Inject(method="updateLightTexture",at=@At("TAIL"))
 private void cheatprogram$brightMap(float partial,CallbackInfo ci){if(!Fullbright.active())return;for(int y=0;y<lightPixels.getHeight();y++)for(int x=0;x<lightPixels.getWidth();x++)lightPixels.setPixelRGBA(x,y,0xffffffff);lightTexture.upload();}
}
