package com.cheatprogram.mixin;

import com.cheatprogram.module.movement.FlatFlight;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(Entity.class)
public abstract class FlatFlightMoveMixin {
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vec3 cheatprogram$horizontalFlight(Vec3 movement, MoverType type, Vec3 original) {
        return FlatFlight.applyMove((Entity) (Object) this, type, movement);
    }
}
