package com.cheatprogram.mixin;

import com.cheatprogram.module.visual.CustomCape;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractClientPlayer.class)
public abstract class CustomCapeSkinMixin {
	@ModifyReturnValue(method = "getCloakTextureLocation", at = @At("RETURN"))
	private ResourceLocation cheatprogram$localCape(ResourceLocation original) { return CustomCape.skin((AbstractClientPlayer) (Object) this, original); }
    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method="isCapeLoaded",at=@At("RETURN"))
    private boolean cheatprogram$localCapeLoaded(boolean original){return CustomCape.appliesTo((AbstractClientPlayer)(Object)this)||original;}
}
