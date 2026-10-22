package com.cheatprogram.mixin;

import com.cheatprogram.module.movement.AirWalk;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class AirWalkPlayerMixin {
    @Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
    private void cheatprogram$virtualFloorHasNoEdge(Vec3 movement, MoverType type, CallbackInfoReturnable<Vec3> ci) {
        if ((type == MoverType.SELF || type == MoverType.PLAYER) && AirWalk.supported((Player) (Object) this)) {
            ci.setReturnValue(movement);
        }
    }
}
