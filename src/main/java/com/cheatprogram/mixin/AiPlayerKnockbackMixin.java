package com.cheatprogram.mixin;

import com.cheatprogram.ai.AiPlayers;
import com.cheatprogram.ai.BotPlayers;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class AiPlayerKnockbackMixin {
	@WrapOperation(method = "attack", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
	private void cheatprogram$preserveActorKnockback(Entity victim, Vec3 beforeAttack, Operation<Void> original) {
		// Vanilla returns ServerPlayer motion to its pre-hit value after handing the impulse to its client.
		// Actors run their own server-side travel and have no client to apply that packet, so keep the
		// velocity already computed by vanilla damage, sprinting and enchantment knockback instead.
		if (!AiPlayers.isActor(victim) && !BotPlayers.isActor(victim)) original.call(victim, beforeAttack);
	}
}
