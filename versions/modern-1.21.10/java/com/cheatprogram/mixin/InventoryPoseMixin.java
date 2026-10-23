package com.cheatprogram.mixin;

import com.cheatprogram.util.InventoryPoses;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AvatarRenderer.class)
public abstract class InventoryPoseMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V", at = @At("TAIL"))
	private void cheatprogram$inventoryPose(net.minecraft.world.entity.Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		if (!(avatar instanceof AbstractClientPlayer player)) return;

			InventoryPoses.applyRenderPose(player, state, partialTick);
	}
}
