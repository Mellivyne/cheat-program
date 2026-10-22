package com.cheatprogram.mixin;

import com.cheatprogram.module.movement.AirWalk;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Single-player's server twin recognizes the same player-only collision surface. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class AirWalkIntegratedServerMixin {
    @ModifyReturnValue(method = "noBlocksAround", at = @At("RETURN"))
    private boolean cheatprogram$recognizeLocalVirtualFloor(boolean original, Entity entity) {
        return original && !AirWalk.integratedSupport(entity);
    }
}
