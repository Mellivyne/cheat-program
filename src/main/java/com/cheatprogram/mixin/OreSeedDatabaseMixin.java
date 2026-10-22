package com.cheatprogram.mixin;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional SCX companion uses local observations; its remote seed database stays disconnected. */
@Pseudo
@Mixin(targets = "kaptainwutax.seedcrackerX.util.Database", remap = false)
public abstract class OreSeedDatabaseMixin {
	@Inject(method = "fetchSeeds", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private static void cheatprogram$noSeedDatabaseDownload(CallbackInfo info) { info.cancel(); }

	@Inject(method = "handleDatabaseCall", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private static void cheatprogram$noSeedDatabaseUpload(Long seed, CallbackInfo info) { info.cancel(); }

	@Inject(method = "joinFakeServerForAuth", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
	private static void cheatprogram$noSeedDatabaseAuthentication(CallbackInfoReturnable<Component> info) {
		info.setReturnValue(Component.translatable("cheatprogram.seedDatabaseDisabled"));
	}
}
