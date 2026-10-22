package com.cheatprogram.mixin;

import com.cheatprogram.util.AuraRotations;
import com.cheatprogram.util.InventoryPoses;
import com.cheatprogram.util.ScaffoldRotations;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerRenderer.class)
public abstract class AuraPlayerRenderMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/client/player/AbstractClientPlayer;Lnet/minecraft/client/renderer/entity/state/PlayerRenderState;F)V", at = @At("TAIL"))
	private void cheatprogram$auraPose(AbstractClientPlayer player, PlayerRenderState state, float partialTick, CallbackInfo ci) {
		if (AuraRotations.hasRenderPose()) AuraRotations.applyRenderPose(player, state, partialTick);
		else if (!AuraRotations.active() && !ScaffoldRotations.applyRenderPose(player, state, partialTick))
			InventoryPoses.applyRenderPose(player, state, partialTick);
	}
}
