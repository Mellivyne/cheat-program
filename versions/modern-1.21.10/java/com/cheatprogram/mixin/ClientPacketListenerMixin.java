package com.cheatprogram.mixin;

import com.cheatprogram.module.combat.Velocity;
import com.cheatprogram.module.utility.KnockbackDelay;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@WrapOperation(method = "handleSetEntityMotion", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;lerpMotion(Lnet/minecraft/world/phys/Vec3;)V"))
	private void cheatprogram$velocity(Entity entity, Vec3 received, Operation<Void> original) {
		if (entity != Minecraft.getInstance().player) {
			original.call(entity, received);
			return;
		}
		Vec3 motion = Velocity.apply(received);
		if (KnockbackDelay.intercept(motion)) return;
		original.call(entity, motion);
	}

	@ModifyExpressionValue(method = "handleExplosion", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ClientboundExplodePacket;playerKnockback()Ljava/util/Optional;"))
	private Optional<Vec3> cheatprogram$explosion(Optional<Vec3> original) {
		return original.map(Velocity::applyExplosion);
	}
}
