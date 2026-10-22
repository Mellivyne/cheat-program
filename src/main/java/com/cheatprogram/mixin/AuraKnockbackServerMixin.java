package com.cheatprogram.mixin;

import com.cheatprogram.util.AuraKnockbackFilter;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** Observes the exact integrated-server melee call, without changing damage or velocity. */
@Mixin(Player.class)
public abstract class AuraKnockbackServerMixin {
	@WrapMethod(method = "attack")
	private void cheatprogram$observeServerAttack(Entity target, Operation<Void> original) {
		var probe = AuraKnockbackFilter.beginServerAttack((Player) (Object) this, target);
		var previous = AuraKnockbackFilter.enterServerAttack(probe);
		boolean completed = false;
		try { original.call(target); completed = true; }
		finally { AuraKnockbackFilter.finishServerAttack(probe, previous, completed); }
	}
}
