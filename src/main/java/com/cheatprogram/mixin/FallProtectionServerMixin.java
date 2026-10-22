package com.cheatprogram.mixin;

import com.cheatprogram.util.FallProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only the integrated host's published player is protected; LAN peers and remote servers are unchanged. */
@Mixin(ServerPlayer.class)
public abstract class FallProtectionServerMixin {
    @Inject(method="checkFallDamage",at=@At("HEAD"),cancellable=true)
    private void cheatprogram$protectHostFall(double distance,boolean onGround,BlockState state,BlockPos pos,CallbackInfo ci){
        ServerPlayer player=(ServerPlayer)(Object)this;
        if(FallProtection.protectServerFall(player,onGround))ci.cancel();
    }
}
