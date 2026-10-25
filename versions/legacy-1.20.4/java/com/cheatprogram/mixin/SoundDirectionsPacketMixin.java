package com.cheatprogram.mixin;

import com.cheatprogram.module.legit.SoundDirections;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class SoundDirectionsPacketMixin {
    @Inject(method = "handleSoundEvent", at = @At("TAIL"))
    private void cheatprogram$soundDirections(ClientboundSoundPacket packet, CallbackInfo ci) {
        SoundDirections.received(packet.getSound().value().getLocation(), new Vec3(packet.getX(), packet.getY(), packet.getZ()), -1, packet.getVolume());
    }
    @Inject(method = "handleSoundEntityEvent", at = @At("TAIL"))
    private void cheatprogram$entitySoundDirections(ClientboundSoundEntityPacket packet, CallbackInfo ci) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        var entity = level.getEntity(packet.getId());
        if (entity != null) SoundDirections.received(packet.getSound().value().getLocation(), entity.position(), entity.getId(), packet.getVolume());
    }
}
