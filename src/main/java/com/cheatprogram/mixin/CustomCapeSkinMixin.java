package com.cheatprogram.mixin;

import com.cheatprogram.module.visual.CustomCape;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractClientPlayer.class)
public abstract class CustomCapeSkinMixin {
	@ModifyReturnValue(method = "getSkin", at = @At("RETURN"))
	private PlayerSkin cheatprogram$localCape(PlayerSkin original) { return CustomCape.skin((AbstractClientPlayer) (Object) this, original); }
}
