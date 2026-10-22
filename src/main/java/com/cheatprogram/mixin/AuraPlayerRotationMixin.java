package com.cheatprogram.mixin;

import com.cheatprogram.util.AuraRotations;
import com.cheatprogram.util.ScaffoldRotations;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LocalPlayer.class)
public abstract class AuraPlayerRotationMixin {
	@WrapOperation(method = {"sendPosition", "tick"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getYRot()F"))
	private float cheatprogram$auraYaw(LocalPlayer player, Operation<Float> original) {
		float yaw = original.call(player);
		return AuraRotations.active() ? AuraRotations.packetYaw(yaw) : ScaffoldRotations.packetYaw(yaw);
	}

	@WrapOperation(method = {"sendPosition", "tick"}, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getXRot()F"))
	private float cheatprogram$auraPitch(LocalPlayer player, Operation<Float> original) {
		float pitch = original.call(player);
		return AuraRotations.active() ? AuraRotations.packetPitch(pitch) : ScaffoldRotations.packetPitch(pitch);
	}
}
