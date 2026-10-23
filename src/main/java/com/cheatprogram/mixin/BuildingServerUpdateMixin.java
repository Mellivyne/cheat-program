package com.cheatprogram.mixin;

import com.cheatprogram.core.ModuleManager;
import com.cheatprogram.module.world.BuildingBlueprints;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Remote building progress acknowledges authoritative packets after vanilla handles them. */
@Mixin(ClientPacketListener.class)
public abstract class BuildingServerUpdateMixin {
	@Inject(method = "handleBlockUpdate", at = @At("TAIL"))
	private void cheatprogram$buildingBlockUpdate(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		BuildingBlueprints building = ModuleManager.get(BuildingBlueprints.class);
		if (building != null) building.serverBlockUpdated(packet.getPos(), packet.getBlockState());

		com.cheatprogram.util.OreBackfill.onServerBlockUpdate(packet.getPos(),packet.getBlockState());
	}
	@Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
	private void cheatprogram$buildingSectionUpdate(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
		BuildingBlueprints building = ModuleManager.get(BuildingBlueprints.class);
		packet.runUpdates((position,state)->{
			if(building!=null)building.serverBlockUpdated(position,state);

			com.cheatprogram.util.OreBackfill.onServerBlockUpdate(position,state);
		});
	}
	@Inject(method = "handleSystemChat", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/chat/ChatListener;handleSystemMessage(Lnet/minecraft/network/chat/Component;Z)V"), cancellable = true)
	private void cheatprogram$buildingVerification(ClientboundSystemChatPacket packet, CallbackInfo ci) {
		// This point follows vanilla's ensureRunningOnSameThread and precedes chat display/storage.
		BuildingBlueprints building = ModuleManager.get(BuildingBlueprints.class);
		if (building != null && building.serverVerificationMessage(packet.content().getString(), packet.overlay())) ci.cancel();
	}
}
