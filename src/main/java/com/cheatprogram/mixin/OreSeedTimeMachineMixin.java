package com.cheatprogram.mixin;

import com.cheatprogram.util.OreSeedSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Harvest only on SCX's own cracking worker, after its mutable HashSet has settled. */
@Pseudo
@Mixin(targets = "kaptainwutax.seedcrackerX.cracker.storage.TimeMachine", remap = false)
public abstract class OreSeedTimeMachineMixin {
	@Unique private final ThreadLocal<OreSeedSource.CrackTicket> cheatprogram$seedTicket = new ThreadLocal<>();

	@Inject(method = "poke", at = @At("HEAD"), require = 0, remap = false)
	private void cheatprogram$captureSeedContext(CallbackInfo info) {
		cheatprogram$seedTicket.set(OreSeedSource.captureCrack(this));
	}

	@Inject(method = "poke", at = @At("RETURN"), require = 0, remap = false)
	private void cheatprogram$harvestSeedResult(CallbackInfo info) {
		OreSeedSource.CrackTicket ticket = cheatprogram$seedTicket.get();
		cheatprogram$seedTicket.remove();
		OreSeedSource.finishedCrack(ticket, this);
	}
}
