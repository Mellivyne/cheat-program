package com.cheatprogram.mixin;

import com.cheatprogram.module.visual.CustomCape;
import com.cheatprogram.util.CapeTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.layers.WingsLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WingsLayer.class)
public abstract class CustomCapeWingsMixin {
	@Inject(method = "getPlayerElytraTexture", at = @At("HEAD"), cancellable = true)
	private static void cheatprogram$preserveWings(HumanoidRenderState state, CallbackInfoReturnable<ResourceLocation> cir) {
		var local = Minecraft.getInstance().player;
		if (local != null && state instanceof PlayerRenderState player && player.id == local.getId()
				&& CapeTextures.TEXTURE.equals(player.skin.capeTexture()))
			cir.setReturnValue(CustomCape.originalWingsTexture());
	}
}
