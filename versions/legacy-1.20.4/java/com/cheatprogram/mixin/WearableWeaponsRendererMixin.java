package com.cheatprogram.mixin;

import com.cheatprogram.render.WearableWeaponsLayer;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
public abstract class WearableWeaponsRendererMixin extends LivingEntityRenderer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    protected WearableWeaponsRendererMixin(EntityRendererProvider.Context context, PlayerModel<AbstractClientPlayer> model, float shadowRadius) {
        super(context, model, shadowRadius);
    }
    @Inject(method = "<init>", at = @At("TAIL"))
    private void cheatprogram$wearableWeapons(EntityRendererProvider.Context context, boolean slim, CallbackInfo ci) {
        addLayer(new WearableWeaponsLayer((PlayerRenderer)(Object)this));
    }
}
