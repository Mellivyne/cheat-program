package com.cheatprogram.mixin;

import com.cheatprogram.module.visual.Freecam;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class FreecamCameraMixin {
	@Shadow private boolean detached;
	@Shadow protected abstract void setPosition(Vec3 position);
	@Shadow protected abstract void setRotation(float yaw, float pitch);

	@Inject(method = "setup", at = @At("TAIL"))
	private void cheatprogram$freeCamera(Level level, Entity entity, boolean thirdPerson, boolean inverse, float partialTick, CallbackInfo ci) {
		if (!Freecam.isActive()) return;
		detached = true;
		setPosition(Freecam.cameraPosition(partialTick));
		setRotation(Freecam.cameraYaw(partialTick), Freecam.cameraPitch(partialTick));
	}
}
