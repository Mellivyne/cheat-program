package com.cheatprogram.mixin;

import com.cheatprogram.module.movement.FlatFlight;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class FlatFlightPacketMixin {
    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void cheatprogram$acceptThenRebase(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        FlatFlight.onPositionCorrection();
        com.cheatprogram.util.FallProtection.onPositionCorrection();
        com.cheatprogram.module.movement.LandingRescue.onPositionCorrection();
        com.cheatprogram.module.world.TargetMotionSupport.onPositionCorrection();
        com.cheatprogram.module.movement.TeleportAura.onServerCorrection();
        com.cheatprogram.util.InteractionLook.onServerCorrection();
        com.cheatprogram.module.combat.KillAura.onServerCorrection();
        com.cheatprogram.module.combat.Criticals.onServerCorrection();
        com.cheatprogram.module.world.Scaffold.onServerCorrection();
    }

    @Inject(method = "handleBlockChangedAck", at = @At("TAIL"))
    private void cheatprogram$confirmBridgePrediction(ClientboundBlockChangedAckPacket packet, CallbackInfo ci) {
        com.cheatprogram.module.world.Scaffold.onBlockChangedAck(packet.sequence());
    }

    @Inject(method = "handlePlayerAbilities", at = @At("TAIL"))
    private void cheatprogram$authoritativeFlightAbilities(ClientboundPlayerAbilitiesPacket packet, CallbackInfo ci) {
        com.cheatprogram.module.movement.Flight.onAbilitiesChanged();
        FlatFlight.onAbilitiesChanged();
    }
}
