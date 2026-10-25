package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.CustomCape;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.client.renderer.entity.layers.ElytraLayer;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import com.cheatprogram.util.CapeTextures;
import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.At;
@Mixin(ElytraLayer.class)
public abstract class CustomCapeWingsMixin {
 @WrapOperation(method="render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",at=@At(value="INVOKE",target="Lnet/minecraft/client/player/AbstractClientPlayer;getCloakTextureLocation()Lnet/minecraft/resources/ResourceLocation;"))
 private ResourceLocation cheatprogram$preserveWings(AbstractClientPlayer skin,Operation<ResourceLocation> original){ResourceLocation cape=original.call(skin);return CapeTextures.TEXTURE.equals(cape)?CustomCape.originalWingsTexture():cape;}
}
