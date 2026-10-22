package com.cheatprogram.mixin;

import com.cheatprogram.module.visual.CustomCape;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class CustomCapeRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void cheatprogram$showOwnCape(net.minecraft.world.entity.Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		if (!(avatar instanceof AbstractClientPlayer player)) return;
		if (CustomCape.appliesTo(player)) state.showCape = true;
	}
}
