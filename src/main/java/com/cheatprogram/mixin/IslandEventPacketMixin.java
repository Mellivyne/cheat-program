package com.cheatprogram.mixin;

import com.cheatprogram.hud.IslandEvents;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the real local-player totem event after vanilla handles it on the client thread. */
@Mixin(ClientPacketListener.class)
public abstract class IslandEventPacketMixin {
	@Inject(method = "handleEntityEvent", at = @At("TAIL"))
	private void cheatprogram$islandEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		IslandEvents.entityEvent(packet);
	}
}
