package com.cheatprogram.mixin;

import com.cheatprogram.module.inventory.ChestStealer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ChestStealerPacketMixin {
	@Inject(method = "handleContainerContent", at = @At("TAIL"))
	private void cheatprogram$realContainerContents(ClientboundContainerSetContentPacket packet, CallbackInfo ci) { ChestStealer.receivedContent(packet); }
	@Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
	private void cheatprogram$realContainerSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) { ChestStealer.receivedSlot(packet); }
}
