package com.cheatprogram.mixin;

import com.cheatprogram.module.movement.AirWalk;
import com.cheatprogram.module.movement.FlatFlight;
import com.cheatprogram.module.world.Scaffold;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Queued vanilla auto-jumps must not overwrite platform height controls or a planned Telly jump. */
@Mixin(LocalPlayer.class)
public abstract class AirWalkLocalPlayerMixin {
    @Shadow private int autoJumpTime;

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void cheatprogram$clearQueuedPlatformJump(CallbackInfo ci) {
        if (AirWalk.capturesVerticalInput() || Scaffold.ownsJumpCycle() || FlatFlight.ownsMotion()) autoJumpTime = 0;
    }

    @ModifyReturnValue(method = "canAutoJump", at = @At("RETURN"))
    private boolean cheatprogram$platformOwnsHeight(boolean original) {
        return original && !AirWalk.capturesVerticalInput() && !Scaffold.ownsJumpCycle() && !FlatFlight.ownsMotion();
    }
}
