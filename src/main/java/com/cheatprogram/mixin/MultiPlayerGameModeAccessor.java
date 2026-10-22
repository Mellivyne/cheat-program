package com.cheatprogram.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {
	@Accessor("destroyDelay")
	void cheatprogram$setDestroyDelay(int ticks);

	/** Send a normal carried-item packet and update vanilla's carriedIndex cache together. */
	@Invoker("ensureHasSentCarriedItem")
	void cheatprogram$ensureHasSentCarriedItem();
}
