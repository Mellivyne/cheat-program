package com.cheatprogram.mixin;

import com.cheatprogram.module.combat.Reach;
import com.cheatprogram.module.movement.SafeWalk;
import com.cheatprogram.module.movement.Speed;
import com.cheatprogram.module.world.Scaffold;
import com.cheatprogram.util.Targets;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method="causeFallDamage",at=@At("HEAD"),cancellable=true)
	private void cheatprogram$localFallProtection(float distance,float multiplier,net.minecraft.world.damagesource.DamageSource source,CallbackInfoReturnable<Boolean> ci){
		if(com.cheatprogram.util.FallProtection.owns((Player)(Object)this))ci.setReturnValue(false);
	}
	@ModifyReturnValue(method = "entityInteractionRange", at = @At("RETURN"))
	private double cheatprogram$entityRange(double original) {
		return Targets.isLocalPlayer(this) ? Reach.entityRange(original) : original;
	}

	@ModifyReturnValue(method = "blockInteractionRange", at = @At("RETURN"))
	private double cheatprogram$blockRange(double original) {
		return Targets.isLocalPlayer(this) ? Reach.blockRange(original) : original;
	}

	@ModifyReturnValue(method = "isStayingOnGroundSurface", at = @At("RETURN"))
	private boolean cheatprogram$safeWalk(boolean original) {
		return original || (Object) this == Minecraft.getInstance().player && SafeWalk.active();
	}

	@ModifyReturnValue(method = "getSpeed", at = @At("RETURN"))
	private float cheatprogram$speed(float original) {
		return (Object) this == Minecraft.getInstance().player ? Speed.scale(original) : original;
	}

	@ModifyReturnValue(method = "getFlyingSpeed", at = @At("RETURN"))
	private float cheatprogram$airWalkingSpeed(float original) {
		return (Object) this == Minecraft.getInstance().player ? Speed.scale(original) : original;
	}

	@Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"))
	private void cheatprogram$prepareAcceleratedBridge(Vec3 movement, MoverType type, CallbackInfoReturnable<Vec3> cir) {
		if ((Object) this == Minecraft.getInstance().player && (type == MoverType.SELF || type == MoverType.PLAYER))
			Scaffold.prepareMove(movement);
	}

	@ModifyReturnValue(method = "maybeBackOffFromEdge", at = @At("RETURN"))
	private Vec3 cheatprogram$continuousBridgeSupport(Vec3 original, Vec3 movement, MoverType type) {
		return (Object) this == Minecraft.getInstance().player && (type == MoverType.SELF || type == MoverType.PLAYER)
				? Scaffold.constrainMove(original) : original;
	}
}
