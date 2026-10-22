package com.cheatprogram.mixin;

import com.cheatprogram.module.movement.SwimState;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class SwimStateMixin {
    @Inject(method = "updateSwimming", at = @At("TAIL"))
    private void cheatprogram$explicitSwimState(CallbackInfo ci) { SwimState.applyPose((Player)(Object)this); }
}
