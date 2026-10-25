package com.cheatprogram.mixin;

import com.cheatprogram.ai.AiPlayers;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(AbstractClientPlayer.class)
public abstract class AiPlayerSkinMixin {
	@ModifyReturnValue(method = "getSkinTextureLocation", at = @At("RETURN"))
	private ResourceLocation cheatprogram$aiPlayerSkin(ResourceLocation original) { return AiPlayers.skin((AbstractClientPlayer) (Object) this, original); }
    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method="getModelName",at=@At("RETURN"))
    private String cheatprogram$skinModel(String original){return AiPlayers.skin((AbstractClientPlayer)(Object)this,null)!=null?"default":original;}
}
