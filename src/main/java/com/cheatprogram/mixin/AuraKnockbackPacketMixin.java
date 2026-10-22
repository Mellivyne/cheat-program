package com.cheatprogram.mixin;

import com.cheatprogram.util.AuraKnockbackFilter;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class AuraKnockbackPacketMixin {
	@Inject(method = "handleDamageEvent", at = @At("TAIL"))
	private void cheatprogram$receivedMeleeAttribution(ClientboundDamageEventPacket packet, CallbackInfo ci) {
		AuraKnockbackFilter.receivedDamageEvent((ClientPacketListener) (Object) this, packet);
	}

	@Inject(method = "handleSetEntityMotion", at = @At("TAIL"))
	private void cheatprogram$receivedServerMotion(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
		AuraKnockbackFilter.receivedMotion((ClientPacketListener) (Object) this, packet);
	}

	@Inject(method = "handleMoveEntity", at = @At("TAIL"))
	private void cheatprogram$receivedRelativePosition(ClientboundMoveEntityPacket packet, CallbackInfo ci) {
		var level = Minecraft.getInstance().level;
		if (level != null && packet.hasPosition())
			AuraKnockbackFilter.receivedPosition((ClientPacketListener) (Object) this, packet.getEntity(level), true);
	}

	@Inject(method = "handleEntityPositionSync", at = @At("TAIL"))
	private void cheatprogram$receivedPositionSync(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
		var level = Minecraft.getInstance().level;
		if (level != null)
			AuraKnockbackFilter.receivedPosition((ClientPacketListener) (Object) this, level.getEntity(packet.id()), true);
	}

	@Inject(method = "handleTeleportEntity", at = @At("TAIL"))
	private void cheatprogram$resetPositionTrend(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
		var level = Minecraft.getInstance().level;
		if (level != null)
			AuraKnockbackFilter.receivedPosition((ClientPacketListener) (Object) this, level.getEntity(packet.id()), false);
	}
}
