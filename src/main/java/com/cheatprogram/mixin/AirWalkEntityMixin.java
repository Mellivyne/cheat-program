package com.cheatprogram.mixin;

import com.cheatprogram.module.movement.AirWalk;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(Entity.class)
public abstract class AirWalkEntityMixin {
    @ModifyReturnValue(method = "collectColliders", at = @At("RETURN"))
    private static List<VoxelShape> cheatprogram$virtualWalkingSurface(List<VoxelShape> original,
            Entity entity, Level level, List<VoxelShape> entities, AABB bounds) {
        return AirWalk.appendSurface(entity, bounds, original);
    }

    @Inject(method = "move", at = @At("HEAD"))
    private void cheatprogram$virtualFloorFall(MoverType type, Vec3 movement, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (AirWalk.supported(self)) self.resetFallDistance();
    }
}
