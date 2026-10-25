package com.cheatprogram.mixin;
import com.cheatprogram.module.visual.CustomCape;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.client.renderer.entity.layers.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(CapeLayer.class)
public abstract class CustomCapeRendererMixin {
 @WrapOperation(method="render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/client/player/AbstractClientPlayer;FFFFFF)V",at=@At(value="INVOKE",target="Lnet/minecraft/client/player/AbstractClientPlayer;isModelPartShown(Lnet/minecraft/world/entity/player/PlayerModelPart;)Z"))
 private boolean cheatprogram$showOwnCape(AbstractClientPlayer player,PlayerModelPart part,Operation<Boolean> original){return CustomCape.appliesTo(player)||original.call(player,part);}
}
