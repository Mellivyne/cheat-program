package com.cheatprogram.mixin;

import com.cheatprogram.module.legit.NetworkTrends;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class NetworkTrendTimeMixin {
    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void cheatprogram$networkTime(ClientboundSetTimePacket packet, CallbackInfo ci) { NetworkTrends.timeSync(packet.gameTime()); }
}
